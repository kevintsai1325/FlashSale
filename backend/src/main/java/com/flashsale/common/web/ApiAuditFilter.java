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
 * security filter chain at {@code SecurityProperties.DEFAULT_FILTER_ORDER}
 * ({@code Ordered.HIGHEST_PRECEDENCE + 100}); this filter uses one more than that so it always
 * runs later in the chain (lower order = earlier).
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

            ApiAuditLog log = new ApiAuditLog(
                request.getMethod(),
                pathTemplate,
                response.getStatus(),
                resolveUserId(),
                request.getRequestId(),
                (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE),
                (int) durationMs,
                request.getRemoteAddr(),
                truncate(request.getHeader("User-Agent"), MAX_USER_AGENT_LENGTH),
                (String) request.getAttribute(ERROR_CODE_ATTRIBUTE));

            auditWriter.record(log);
        }
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
