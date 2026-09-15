package com.flashsale.common.messaging;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConsumedMessageJpaRepository extends JpaRepository<ConsumedMessage, Long> {

    @Modifying
    @Query(value = "INSERT INTO consumed_messages (message_id, consumer_name, consumed_at) " +
        "VALUES (:messageId, :consumerName, now()) ON CONFLICT (message_id) DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("messageId") String messageId, @Param("consumerName") String consumerName);
}
