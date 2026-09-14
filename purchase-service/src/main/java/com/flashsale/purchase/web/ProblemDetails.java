package com.flashsale.purchase.web;

import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * 錯誤回應的形狀是對外契約的一部分：前端解析的是 code 這個欄位，而 purchase-service
 * 現在直接面向同一批前端程式碼。所以這裡與 backend 的格式必須逐欄位一致。
 */
public final class ProblemDetails {

    private ProblemDetails() {
    }

    public static ProblemDetail of(HttpStatus status, String code, String detail, String requestUri) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, detail);
        problemDetail.setTitle(status.getReasonPhrase());
        problemDetail.setInstance(URI.create(requestUri));
        problemDetail.setProperty("code", code);
        problemDetail.setProperty("traceId", UUID.randomUUID().toString());
        return problemDetail;
    }
}
