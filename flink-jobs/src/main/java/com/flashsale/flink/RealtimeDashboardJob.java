package com.flashsale.flink;

import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.connector.base.DeliveryGuarantee;
import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;
import org.apache.flink.connector.kafka.sink.KafkaSink;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.windowing.assigners.TumblingEventTimeWindows;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;
import org.apache.flink.streaming.api.functions.windowing.ProcessAllWindowFunction;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * P6：即時大屏的三個數字 —— 秒級 GMV、每秒訂單數、熱門商品 Top N。
 *
 * <h2>為什麼用事件時間而不是處理時間</h2>
 * 視窗以事件自己的 {@code createdAt} 切，不是以 Flink 收到它的時間。這兩者在正常情況下
 * 差幾毫秒，但在「消費端落後之後追上」時差很多 —— 用處理時間的話，追上的那一秒會把
 * 前面累積的全部算進同一個視窗，大屏上出現一根不存在的尖峰。
 *
 * <h2>遲到事件</h2>
 * watermark 允許 2 秒亂序（Kafka 的分區間沒有全域順序，同一秒的事件可能分散在三個分區）。
 * 這個值同時也是**大屏的延遲下限**：watermark 是「已見的最大事件時間減去這個值」，
 * 所以最後 2 秒的視窗要等到更晚的事件到達才會定案。調大它換到的是對亂序的容忍度，
 * 付出的是畫面的即時性 —— 兩者不可能同時要。
 * 超過的用 {@code allowedLateness} 再寬限 10 秒，那段時間內遲到的事件會讓已經發出的視窗
 * **重新計算並再發一次** —— 大屏因此要以「同一個 windowEnd 的後到值覆蓋先前的值」來處理，
 * 而不是累加。
 *
 * <h2>GMV 的定義</h2>
 * 用 OrderCreated 的金額，也就是**下單 GMV**，不是已付款 GMV。這個系統的付款是模擬的、
 * 由使用者手動觸發，用已付款金額做即時大屏會是一條幾乎不動的線。
 * 驗收時與 {@code order_db} 的 {@code SUM(total_amount)} 對照，用的是同一個定義。
 */
public final class RealtimeDashboardJob {

    private static final String DEFAULT_BOOTSTRAP = "kafka:9092";
    private static final String SOURCE_TOPIC = "flashsale.order-events";
    private static final String SINK_TOPIC = "flashsale.realtime-metrics";
    private static final int TOP_N = 5;

    public static void main(String[] args) throws Exception {
        String bootstrap = System.getenv().getOrDefault("KAFKA_BOOTSTRAP_SERVERS", DEFAULT_BOOTSTRAP);

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        // checkpoint 是 Flink 的狀態恢復機制。TaskManager 重啟之後，視窗裡累積到一半的
        // 資料要能回來，否則「重啟後聚合仍正確」這條驗收條件就不成立。
        env.enableCheckpointing(Duration.ofSeconds(10).toMillis());

        KafkaSource<String> source = KafkaSource.<String>builder()
            .setBootstrapServers(bootstrap)
            .setTopics(SOURCE_TOPIC)
            .setGroupId("flink-realtime-dashboard")
            // earliest：作業重新部署時要能重放，這是把事件放在 Kafka 的理由。
            .setStartingOffsets(OffsetsInitializer.earliest())
            .setValueOnlyDeserializer(new SimpleStringSchema())
            .build();

        KafkaSink<String> sink = KafkaSink.<String>builder()
            .setBootstrapServers(bootstrap)
            .setRecordSerializer(KafkaRecordSerializationSchema.builder()
                .setTopic(SINK_TOPIC)
                .setValueSerializationSchema(new SimpleStringSchema())
                .build())
            // AT_LEAST_ONCE 而不是 EXACTLY_ONCE：下游是一個「以 windowEnd 覆蓋」的大屏，
            // 重複投遞同一個視窗的結果是冪等的。EXACTLY_ONCE 要開 Kafka 交易，
            // 代價是端到端延遲被 checkpoint 間隔綁住 —— 對即時大屏那是錯的取捨。
            .setDeliveryGuarantee(DeliveryGuarantee.AT_LEAST_ONCE)
            .build();

        DataStream<OrderEvent> orders = env
            .fromSource(source, WatermarkStrategy.noWatermarks(), "order-events")
            .map(OrderEvent::parseCreated)
            .filter(java.util.Objects::nonNull)
            .assignTimestampsAndWatermarks(
                WatermarkStrategy.<OrderEvent>forBoundedOutOfOrderness(Duration.ofSeconds(2))
                    .withTimestampAssigner((event, timestamp) -> event.getCreatedAtMillis()))
            .name("order-created");

        // 作業一：秒級 GMV 與每秒訂單數。兩個數字同一個視窗算完一起送 ——
        // 分成兩個作業會讓大屏收到兩則時間戳相同但可能不同步抵達的訊息。
        orders
            .windowAll(TumblingEventTimeWindows.of(Time.seconds(1)))
            .allowedLateness(Time.seconds(10))
            .process(new GmvWindow())
            .name("gmv-per-second")
            .sinkTo(sink).name("gmv-sink");

        // 作業二：熱門商品 Top N。視窗開 10 秒 —— 一秒的視窗在低流量時只會有零星幾筆，
        // 排行榜會抖動得無法閱讀。
        orders
            .windowAll(TumblingEventTimeWindows.of(Time.seconds(10)))
            .allowedLateness(Time.seconds(10))
            .process(new TopProductsWindow())
            .name("top-products")
            .sinkTo(sink).name("top-products-sink");

        env.execute("flashsale-realtime-dashboard");
    }

    /** 一個視窗內的成交金額與筆數。 */
    public static final class GmvWindow extends ProcessAllWindowFunction<OrderEvent, String, TimeWindow> {

        private static final long serialVersionUID = 1L;

        @Override
        public void process(Context context, Iterable<OrderEvent> elements, Collector<String> out) {
            BigDecimal amount = BigDecimal.ZERO;
            long count = 0;
            for (OrderEvent event : elements) {
                amount = amount.add(event.getTotalAmount());
                count++;
            }
            out.collect(RealtimeMetric.gmv(context.window().getEnd(), amount, count));
        }
    }

    /** 一個視窗內售出數量最高的前 N 個商品。 */
    public static final class TopProductsWindow extends ProcessAllWindowFunction<OrderEvent, String, TimeWindow> {

        private static final long serialVersionUID = 1L;

        @Override
        public void process(Context context, Iterable<OrderEvent> elements, Collector<String> out) {
            out.collect(RealtimeMetric.topProducts(context.window().getEnd(), rank(elements, TOP_N)));
        }
    }

    /**
     * 排名的純函式版本，抽出來是為了能被測試 —— Flink 的 window Context 是內部抽象類別，
     * 為了驗證排序邏輯而去偽造整個運算元，成本遠高於它能抓到的錯誤。
     *
     * 同一個商品在一個視窗內可能出現多次（多筆訂單），所以是累加數量而不是計次。
     */
    static List<String> rank(Iterable<OrderEvent> elements, int topN) {
        Map<Long, long[]> quantityByProduct = new HashMap<>();
        Map<Long, String> nameByProduct = new HashMap<>();
        for (OrderEvent event : elements) {
            quantityByProduct.computeIfAbsent(event.getProductId(), key -> new long[1])[0] += event.getQuantity();
            nameByProduct.put(event.getProductId(), event.getProductName());
        }
        List<Map.Entry<Long, long[]>> ranked = new ArrayList<>(quantityByProduct.entrySet());
        ranked.sort(Comparator.<Map.Entry<Long, long[]>>comparingLong(entry -> entry.getValue()[0]).reversed()
            // 數量相同時以 productId 排序：沒有這個 tiebreaker，排行榜會在每個視窗之間
            // 無意義地跳動，而那看起來像資料有問題。
            .thenComparingLong(Map.Entry::getKey));

        List<String> items = new ArrayList<>();
        for (Map.Entry<Long, long[]> entry : ranked.subList(0, Math.min(topN, ranked.size()))) {
            items.add(RealtimeMetric.productEntry(entry.getKey(),
                nameByProduct.getOrDefault(entry.getKey(), "(unknown)"), entry.getValue()[0]));
        }
        return items;
    }

    private RealtimeDashboardJob() {}
}
