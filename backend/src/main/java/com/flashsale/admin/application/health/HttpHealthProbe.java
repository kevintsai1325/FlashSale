package com.flashsale.admin.application.health;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.net.http.HttpTimeoutException;

public final class HttpHealthProbe {

    record Result(ServiceHealthStatus status, String reason) {}

    private final HttpClient client;
    private final URI uri;

    public HttpHealthProbe(String configuredUrl) {
        this(HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(1))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build(), configuredUrl == null || configuredUrl.isBlank() ? null : URI.create(configuredUrl));
    }

    HttpHealthProbe(HttpClient client, URI uri) {
        this.client = client;
        this.uri = uri;
    }

    Result check() {
        if (uri == null) {
            return new Result(ServiceHealthStatus.UNKNOWN, "未設定探測");
        }
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(2)).GET().build();
        try {
            int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            return status >= 200 && status < 300
                ? new Result(ServiceHealthStatus.UP, "可用")
                : new Result(ServiceHealthStatus.DOWN, "狀態異常");
        } catch (HttpTimeoutException exception) {
            return new Result(ServiceHealthStatus.UNKNOWN, "逾時");
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new Result(ServiceHealthStatus.UNKNOWN, "無法連線");
        }
    }
}
