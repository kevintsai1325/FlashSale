package com.flashsale.notification.adapter.mail;

import com.flashsale.notification.application.NotificationDeliveryRepository;
import com.flashsale.notification.domain.NotificationDelivery;
import com.flashsale.notification.domain.NotificationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

interface NotificationDeliveryJpaRepository extends JpaRepository<NotificationDelivery, Long> {
    List<NotificationDelivery> findByStatusAndAttemptCountLessThan(NotificationStatus status, int attemptCount);
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
}
