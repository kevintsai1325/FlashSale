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
 * 這個 client 唯一值得測的是**失敗時的行為**：儀表板不該因為 analytics 掛了而整頁 500。
 */
class AnalyticsClientTest {

    private static final Instant AS_OF = Instant.parse("2026-09-15T10:30:00Z");
    private static final String BASE = "http://analytics-service:8080";

    private record Fixture(AnalyticsClient client, MockRestServiceServer server) {}

    private Fixture fixture() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new Fixture(new AnalyticsClient(builder.build()), server);
    }

    @Test
    void returnsTheSummaryWhenAnalyticsAnswers() {
        Fixture fixture = fixture();
        fixture.server().expect(requestTo(BASE + "/internal/analytics/dashboard-summary"))
            .andRespond(withSuccess("""
                {"totalPurchaseRequests":7,"succeededPurchaseRequests":3,
                 "ordersByStatus":{"PAID":3,"CANCELLED":1},"totalPaidAmount":60.00}
                """, MediaType.APPLICATION_JSON));

        AnalyticsClient.DashboardSummary summary = fixture.client().summary();

        assertThat(summary.totalPurchaseRequests()).isEqualTo(7);
        assertThat(summary.ordersByStatus()).containsEntry("PAID", 3L);
        assertThat(summary.totalPaidAmount()).isEqualByComparingTo("60.00");
    }

    @Test
    void degradesToZeroWhenAnalyticsFails() {
        Fixture fixture = fixture();
        fixture.server().expect(requestTo(BASE + "/internal/analytics/dashboard-summary"))
            .andRespond(withServerError());

        AnalyticsClient.DashboardSummary summary = fixture.client().summary();

        assertThat(summary.totalPurchaseRequests()).isZero();
        assertThat(summary.ordersByStatus()).isEmpty();
    }

    @Test
    void degradesToEmptyTrendsWhenAnalyticsFails() {
        Fixture fixture = fixture();
        fixture.server().expect(requestTo(
                BASE + "/internal/analytics/dashboard-trends?asOf=2026-09-15T10:30:00Z"))
            .andRespond(withServerError());

        AnalyticsClient.DashboardTrends trends = fixture.client().trends(AS_OF);

        assertThat(trends.lastHour()).isEmpty();
        assertThat(trends.last24Hours()).isEmpty();
    }
}
