package com.flashsale.notification.adapter.mail;

import com.flashsale.notification.application.NotificationDeliveryRepository;
import com.flashsale.notification.domain.NotificationDelivery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

interface NotificationDeliveryJpaRepository extends JpaRepository<NotificationDelivery, Long> {}

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
}
