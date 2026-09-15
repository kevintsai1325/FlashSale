package com.flashsale.common.messaging;

import com.flashsale.common.config.KafkaTopics;
import com.flashsale.order.application.event.OrderCreatedEvent;
import com.flashsale.testsupport.AbstractIntegrationTest;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P4 步驟 3 的驗收條件：**同一個 flashSaleId 的事件落在同一個分區，而且順序正確。**
 *
 * 為什麼這件事需要一個測試守著：Kafka 只保證單一分區內的順序。分區鍵一旦漏給
 * （partitionKey 為 null），producer 會輪詢分區、事件被打散，而**功能上完全看不出來**——
 * 訊息還是全部送到了，只是順序沒了。P6 的視窗聚合會因此算錯，而且錯得不明顯。
 *
 * 測試同時確認「不同的鍵真的會分到不同分區」：只有一個分區時前一項斷言會自動通過，
 * 那種綠燈什麼都沒證明。
 */
class OrderEventsPartitioningIT extends AbstractIntegrationTest {

    private static final int EVENTS_PER_SALE = 8;
    private static final List<Long> FLASH_SALE_IDS = List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);

    @Autowired OutboxWriter outboxWriter;
    @Autowired OutboxPublisher outboxPublisher;

    @Test
    void everyEventForOneFlashSaleLandsInOnePartitionInOrder() {
        Map<Long, List<Long>> expectedOrderIds = new LinkedHashMap<>();
        long orderId = 0;
        for (Long flashSaleId : FLASH_SALE_IDS) {
            List<Long> ids = new ArrayList<>();
            for (int i = 0; i < EVENTS_PER_SALE; i++) {
                orderId++;
                ids.add(orderId);
                outboxWriter.write("Order", String.valueOf(orderId), EventTypes.ORDER_CREATED,
                    new OrderCreatedEvent(orderId, "ORD-" + orderId, 1L, flashSaleId, 1L, "item", 1,
                        new BigDecimal("9.99"), new BigDecimal("9.99"), Instant.now()),
                    String.valueOf(flashSaleId));
            }
            expectedOrderIds.put(flashSaleId, ids);
        }
        outboxPublisher.publishPending();

        List<ConsumerRecord<String, String>> records = drainOrderEvents(FLASH_SALE_IDS.size() * EVENTS_PER_SALE);

        Map<String, Set<Integer>> partitionsByKey = records.stream().collect(
            Collectors.groupingBy(ConsumerRecord::key, Collectors.mapping(ConsumerRecord::partition, Collectors.toSet())));
        assertThat(partitionsByKey).allSatisfy((key, partitions) ->
            assertThat(partitions).as("flashSaleId %s must use exactly one partition", key).hasSize(1));

        // 只有一個分區的話上面那條斷言永遠成立、什麼都沒證明。
        assertThat(records.stream().map(ConsumerRecord::partition).collect(Collectors.toSet()))
            .as("the topic must actually spread different keys across partitions")
            .hasSizeGreaterThan(1);

        // 分區內順序：offset 遞增的那一串，orderId 必須與寫入順序相同。
        for (Map.Entry<Long, List<Long>> entry : expectedOrderIds.entrySet()) {
            List<Long> observed = records.stream()
                .filter(r -> r.key().equals(String.valueOf(entry.getKey())))
                .sorted((a, b) -> Long.compare(a.offset(), b.offset()))
                .map(r -> orderIdOf(r.value()))
                .toList();
            assertThat(observed).as("flashSaleId %s event order", entry.getKey()).isEqualTo(entry.getValue());
        }
    }

    private static long orderIdOf(String json) {
        int start = json.indexOf("\"orderId\":") + "\"orderId\":".length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == ' ')) {
            end++;
        }
        return Long.parseLong(json.substring(start, end).trim());
    }

    private List<ConsumerRecord<String, String>> drainOrderEvents(int expected) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        // 手動 assign 而不是 subscribe：這個測試要讀「全部分區的全部訊息」，
        // 用消費者群組還要等 rebalance，而且會與其他測試共用的 group 狀態糾纏。
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "partitioning-it-" + System.nanoTime());
        List<ConsumerRecord<String, String>> collected = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            List<TopicPartition> partitions = new ArrayList<>();
            for (PartitionInfo info : consumer.partitionsFor(KafkaTopics.ORDER_EVENTS)) {
                partitions.add(new TopicPartition(info.topic(), info.partition()));
            }
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            long deadline = System.currentTimeMillis() + 30_000;
            while (collected.size() < expected && System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> polled = consumer.poll(Duration.ofMillis(500));
                polled.forEach(collected::add);
            }
        }
        assertThat(collected).as("expected every published event to arrive").hasSize(expected);
        return collected;
    }
}
