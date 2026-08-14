package com.flashsale.notification.adapter.mail;

import com.flashsale.notification.application.NotificationDeliveryRepository;
import com.flashsale.notification.domain.NotificationChannel;
import com.flashsale.notification.domain.NotificationDelivery;
import com.flashsale.notification.domain.NotificationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

interface NotificationDeliveryJpaRepository extends JpaRepository<NotificationDelivery, Long>,
        JpaSpecificationExecutor<NotificationDelivery> {
    List<NotificationDelivery> findByStatusAndAttemptCountLessThan(NotificationStatus status, int attemptCount);

    List<NotificationDelivery> findByIdIn(List<Long> ids);

    long countByRead(boolean read);
}

@Repository
class NotificationDeliveryRepositoryImpl implements NotificationDeliveryRepository {

    private final NotificationDeliveryJpaRepository jpaRepository;

    NotificationDeliveryRepositoryImpl(NotificationDeliveryJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public NotificationDelivery save(NotificationDelivery delivery) {
        return jpaRepository.save(delivery);
    }

    @Override
    public Optional<NotificationDelivery> findById(Long id) {
        return jpaRepository.findById(id);
    }

    @Override
    public List<NotificationDelivery> findFailedWithAttemptsBelow(int maxAttempts) {
        return jpaRepository.findByStatusAndAttemptCountLessThan(NotificationStatus.FAILED, maxAttempts);
    }

    // Admin notification center (com.flashsale.admin). Four independently-optional filters
    // combined with plain Specification.and(...) - same discipline as ApiAuditQueryService,
    // not worth a generic dynamic-query framework for this few fields.
    @Override
    public Page<NotificationDelivery> search(Long userId, NotificationChannel channel, NotificationStatus status,
                                              Boolean read, Pageable pageable) {
        Specification<NotificationDelivery> spec = Specification.where(null);
        if (userId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("userId"), userId));
        }
        if (channel != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("channel"), channel));
        }
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (read != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("read"), read));
        }
        return jpaRepository.findAll(spec, pageable);
    }

    @Override
    public List<NotificationDelivery> findByIdIn(List<Long> ids) {
        return jpaRepository.findByIdIn(ids);
    }

    @Override
    public long countByRead(boolean read) {
        return jpaRepository.countByRead(read);
    }
}
