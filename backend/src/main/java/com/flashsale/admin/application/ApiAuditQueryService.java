package com.flashsale.admin.application;

import com.flashsale.admin.application.dto.ApiAuditLogView;
import com.flashsale.admin.application.dto.PagedResult;
import com.flashsale.common.web.ApiAuditLog;
import com.flashsale.common.web.ApiAuditLogJpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Filtered/paged read access over {@code api_audit_logs} (Task 2) for the admin API-logs screen.
 * Optional filters combined with plain {@code Specification.and(...)} — not worth a generic
 * dynamic-query framework for this few fields.
 */
@Service
public class ApiAuditQueryService {

    private final ApiAuditLogJpaRepository repository;

    public ApiAuditQueryService(ApiAuditLogJpaRepository repository) {
        this.repository = repository;
    }

    public PagedResult<ApiAuditLogView> search(String method, String pathTemplate, Integer status, Long userId,
                                                String traceId, Instant from, Instant to, int page, int size) {
        Specification<ApiAuditLog> spec = Specification.where(null);
        if (method != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("method"), method));
        }
        if (pathTemplate != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("pathTemplate"), pathTemplate));
        }
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (userId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("userId"), userId));
        }
        if (traceId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("traceId"), traceId));
        }
        if (from != null) {
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("occurredAt"), from));
        }
        if (to != null) {
            spec = spec.and((root, query, cb) -> cb.lessThanOrEqualTo(root.get("occurredAt"), to));
        }

        Page<ApiAuditLog> result = repository.findAll(spec,
            PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "occurredAt")));

        return new PagedResult<>(
            result.getContent().stream().map(ApiAuditLogView::from).toList(),
            result.getTotalElements(),
            page,
            size);
    }
}
