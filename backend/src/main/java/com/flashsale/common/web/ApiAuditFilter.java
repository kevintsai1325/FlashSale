package com.flashsale.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;

/**
 * Writes one audit row per HTTP request via {@link ApiAuditWriter}. Observes only — it calls
 * {@code filterChain.doFilter} unconditionally and lets exceptions propagate through untouched;
 * it never handles them.
 *
 * <p>Registered to run <b>after</b> Spring Security's filter chain so {@code SecurityContextHolder}
 * is populated by the time this filter's {@code finally} block reads it. Spring Boot registers the
 * security filter chain at {@code SecurityProperties.DEFAULT_FILTER_ORDER} — despite the name, this
 * is <b>not</b> {@code Ordered.HIGHEST_PRECEDENCE + 100} (that would be {@code Integer.MIN_VALUE +
 * 100}); its actual value is {@code OrderedFilter.REQUEST_WRAPPER_FILTER_MAX_ORDER - 100}, i.e.
 * {@code -100}. This filter uses one more than that ({@code -99}) so it always runs later in the
 * chain (lower order = earlier).
 *
 * <p>Never captures Authorization headers, passwords, JWT contents, or request/response bodies —
 * by construction: nothing here reads the Authorization header, the request/response body streams
 * are never touched, and the only identity data captured is the numeric {@code userId} claim
 * already verified by Spring Security.
 */
@Component
@Order(SecurityProperties.DEFAULT_FILTER_ORDER + 1)
public class ApiAuditFilter extends OncePerRequestFilter {

    private static final String ERROR_CODE_ATTRIBUTE = "apiAuditErrorCode";
    private static final int MAX_USER_AGENT_LENGTH = 255;

    private final ApiAuditWriter auditWriter;

    public ApiAuditFilter(ApiAuditWriter auditWriter) {
        this.auditWriter = auditWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        long startMillis = System.currentTimeMillis();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long durationMs = System.currentTimeMillis() - startMillis;

            // Only populated after the request has been dispatched to a handler, i.e. only
            // readable here, after filterChain.doFilter() returns.
            String pathTemplate = (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            if (pathTemplate == null) {
                pathTemplate = request.getRequestURI();
            }

            // Design spec 3.3: request_id and trace_id store the same value this round — they're
            // only meant to diverge once Week 5 adds real distributed-tracing spans. Both columns
            // come from TraceIdFilter's request attribute, not the servlet container's own id.
            String traceId = (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);

            ApiAuditLog log = new ApiAuditLog(
                request.getMethod(),
                pathTemplate,
                response.getStatus(),
                resolveUserId(),
                traceId,
                traceId,
                (int) durationMs,
                resolveClientIp(request),
                truncate(request.getHeader("User-Agent"), MAX_USER_AGENT_LENGTH),
                (String) request.getAttribute(ERROR_CODE_ATTRIBUTE));

            auditWriter.record(log);
        }
    }

    /**
     * In the deployed stack, requests reach this backend through the Nginx reverse proxy
     * ({@code nginx/nginx.conf}), so {@link HttpServletRequest#getRemoteAddr()} is always Nginx's
     * own container address, not the real client. Nginx forwards the real client address via the
     * {@code X-Real-IP} header (see {@code proxy_set_header X-Real-IP $remote_addr;} in
     * {@code nginx/nginx.conf}), so prefer that when present. Falls back to
     * {@code getRemoteAddr()} for requests that reach the backend directly, bypassing Nginx (e.g.
     * integration tests using MockMvc/TestRestTemplate).
     */
    private static String resolveClientIp(HttpServletRequest request) {
        String realIp = request.getHeader("X-Real-IP");
        return (realIp != null && !realIp.isBlank()) ? realIp : request.getRemoteAddr();
    }

    private Long resolveUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
            Object userId = jwtAuthentication.getToken().getClaim("userId");
            if (userId instanceof Number number) {
                return number.longValue();
            }
        }
        return null;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
