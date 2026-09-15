package com.flashsale.admin.adapter.http;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 這個 client 唯一值得測的是**失敗時的行為**：儀表板不該因為另一個服務掛了而整頁 500。
 */
class PurchaseStatsClientTest {

    private static final Instant AS_OF = Instant.parse("2026-09-15T10:30:00Z");
    private static final String URL =
        "http://purchase-service:8080/internal/purchase-requests/stats?asOf=2026-09-15T10:30:00Z";

    private record Fixture(PurchaseStatsClient client, MockRestServiceServer server) {}

    private Fixture fixture() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://purchase-service:8080");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new Fixture(new PurchaseStatsClient(builder.build()), server);
    }

    @Test
    void returnsTheStatsWhenPurchaseServiceAnswers() {
        Fixture fixture = fixture();
        fixture.server().expect(requestTo(URL))
            .andRespond(withSuccess("""
                {"total":7,"succeeded":3,
                 "lastHour":[{"bucketStart":"2026-09-15T10:30:00Z","count":5}],
                 "last24Hours":[{"bucketStart":"2026-09-15T10:00:00Z","count":6}]}
                """, MediaType.APPLICATION_JSON));

        PurchaseStatsClient.PurchaseStats stats = fixture.client().fetch(AS_OF);

        assertThat(stats.total()).isEqualTo(7);
        assertThat(stats.succeeded()).isEqualTo(3);
        assertThat(stats.countAt(stats.lastHour(), Instant.parse("2026-09-15T10:30:00Z"))).isEqualTo(5);
        // 沒有對應的桶回 0，而不是拋例外——降級時整條趨勢線是一條 0，圖表照樣畫得出來。
        assertThat(stats.countAt(stats.lastHour(), Instant.parse("2026-09-15T10:29:00Z"))).isZero();
    }

    @Test
    void degradesToZeroWhenPurchaseServiceFails() {
        Fixture fixture = fixture();
        fixture.server().expect(requestTo(URL)).andRespond(withServerError());

        PurchaseStatsClient.PurchaseStats stats = fixture.client().fetch(AS_OF);

        assertThat(stats.total()).isZero();
        assertThat(stats.succeeded()).isZero();
        assertThat(stats.lastHour()).isEmpty();
        assertThat(stats.last24Hours()).isEmpty();
    }
}
