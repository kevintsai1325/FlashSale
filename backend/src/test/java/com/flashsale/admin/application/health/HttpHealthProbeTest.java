package com.flashsale.admin.application.health;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HttpHealthProbeTest {

    @Test
    void mapsSuccessAndNonSuccessWithoutExposingTheUrlOrBody() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<Void> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(503);
        when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        var result = new HttpHealthProbe(client, URI.create("http://secret-host:9999/password"));

        assertThat(result.check()).isEqualTo(
            new HttpHealthProbe.Result(ServiceHealthStatus.DOWN, "狀態異常"));
    }

    @Test
    void sanitizesIoExceptionsAsUnknown() throws Exception {
        HttpClient client = mock(HttpClient.class);
        when(client.send(any(), any(HttpResponse.BodyHandler.class)))
            .thenThrow(new IOException("password at http://secret-host"));

        var result = new HttpHealthProbe(client, URI.create("http://secret-host"));

        assertThat(result.check()).isEqualTo(
            new HttpHealthProbe.Result(ServiceHealthStatus.UNKNOWN, "無法連線"));
    }

    @Test
    void disabledProbeIsUnknownWithoutMakingANetworkCall() {
        var result = new HttpHealthProbe("").check();

        assertThat(result).isEqualTo(
            new HttpHealthProbe.Result(ServiceHealthStatus.UNKNOWN, "未設定探測"));
    }
}
