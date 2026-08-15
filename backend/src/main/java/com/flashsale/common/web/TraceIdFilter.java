package com.flashsale.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Establishes a trace id for every request: reuses the caller-supplied {@code X-Trace-Id} header
 * if present, otherwise generates one. The trace id is exposed to downstream code via a request
 * attribute ({@link ApiAuditFilter} reads it), echoed back on the response header, and placed in
 * the SLF4J MDC for future structured logging (Week 5) — nothing consumes the MDC value yet, but
 * setting it is harmless and the MDC key is always cleared in a {@code finally} block since
 * threads are reused across requests via the servlet container's thread pool.
 *
 * <p>Registered with {@link Ordered#HIGHEST_PRECEDENCE} so it runs before every other filter,
 * including Spring Security's filter chain (which Spring Boot registers at
 * {@code SecurityProperties.DEFAULT_FILTER_ORDER} — despite the name, this is <b>not</b>
 * {@code HIGHEST_PRECEDENCE + 100}; its actual value is
 * {@code OrderedFilter.REQUEST_WRAPPER_FILTER_MAX_ORDER - 100}, i.e. {@code -100}) — the trace id
 * must exist before any other filter or the security chain runs.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String TRACE_ID_ATTRIBUTE = "traceId";
    private static final String MDC_KEY = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String traceId = request.getHeader(TRACE_ID_HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }

        request.setAttribute(TRACE_ID_ATTRIBUTE, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);
        MDC.put(MDC_KEY, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
