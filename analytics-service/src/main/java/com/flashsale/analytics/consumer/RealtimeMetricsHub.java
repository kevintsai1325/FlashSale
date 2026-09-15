package com.flashsale.analytics.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Flink 算好的即時指標進來，推給正在看大屏的瀏覽器。
 *
 * <h2>為什麼是 SSE 而不是 WebSocket</h2>
 * 大屏是單向推送。SSE 在瀏覽器端有內建的自動重連，在 Nginx 端只要關掉 buffering，
 * 在這裡只是一個 {@link SseEmitter}。WebSocket 要多一套握手、心跳與重連，
 * 換來的雙向能力用不到。
 *
 * <h2>為什麼保留最新一筆</h2>
 * 新開的大屏不該對著空畫面等下一個視窗。每個 type 的最新值留在記憶體裡，
 * 連上就先補一次 —— 這份快取刻意不落地：它最多只值一個視窗的時間（1～10 秒），
 * 為它加一張表只會多一個要清理的東西。
 *
 * <h2>覆蓋而不是累加</h2>
 * Flink 的視窗允許遲到 10 秒，遲到的事件會讓同一個 {@code windowEnd} **重新計算並再送一次**。
 * 下游因此必須以 windowEnd 為鍵覆蓋，累加會把同一秒算兩次。
 */
@Component
public class RealtimeMetricsHub {

    private static final Logger logger = LoggerFactory.getLogger(RealtimeMetricsHub.class);

    private final List<SseEmitter> subscribers = new CopyOnWriteArrayList<>();
    private final Map<String, String> latestByType = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public RealtimeMetricsHub(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // auto.offset.reset 在這裡覆寫成 latest：大屏只關心「現在」。
    // 全域設定是 earliest（讀取模型要能從頭重放），沿用它會讓新開的大屏先播放
    // 幾分鐘前的歷史視窗，看起來像時間倒流。
    @KafkaListener(topics = "${app.kafka.realtime-metrics-topic}",
        groupId = "${app.kafka.realtime-group-id}",
        properties = {"auto.offset.reset:latest"})
    public void onMetric(String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            latestByType.put(node.path("type").asText("unknown"), payload);
        } catch (IOException malformed) {
            logger.warn("無法解析即時指標，略過: {}", malformed.getMessage());
            return;
        }
        broadcast(payload);
    }

    public SseEmitter subscribe() {
        // 大屏會開著一整天，逾時設成無限；斷線由 onCompletion/onError 清掉。
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        emitter.onCompletion(() -> subscribers.remove(emitter));
        emitter.onTimeout(() -> subscribers.remove(emitter));
        emitter.onError(error -> subscribers.remove(emitter));
        subscribers.add(emitter);
        latestByType.values().forEach(payload -> send(emitter, payload));
        return emitter;
    }

    private void broadcast(String payload) {
        for (SseEmitter emitter : subscribers) {
            send(emitter, payload);
        }
    }

    private void send(SseEmitter emitter, String payload) {
        try {
            emitter.send(SseEmitter.event().name("metric").data(payload));
        } catch (IOException | IllegalStateException disconnected) {
            // 瀏覽器關掉分頁時這裡一定會丟例外。那是正常的生命週期，不是錯誤 ——
            // 記成 error 只會讓日誌被大屏的開開關關淹沒。
            subscribers.remove(emitter);
        }
    }

    /** 測試與健康檢查用。 */
    public int subscriberCount() {
        return subscribers.size();
    }
}
