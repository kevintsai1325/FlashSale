package com.flashsale.analytics.web;

import com.flashsale.analytics.consumer.RealtimeMetricsHub;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 即時大屏的資料流。**這是 analytics-service 唯一一個對外的端點** ——
 * 其餘都是 /internal/，只有 platform 在叢集內部呼叫。
 *
 * 需要 ADMIN：大屏顯示的是全站的成交金額。
 */
@RestController
public class RealtimeMetricsController {

    private final RealtimeMetricsHub hub;

    public RealtimeMetricsController(RealtimeMetricsHub hub) {
        this.hub = hub;
    }

    @GetMapping(path = "/api/realtime/metrics", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return hub.subscribe();
    }
}
