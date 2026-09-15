package com.flashsale.common.messaging;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "consumed_messages")
public class ConsumedMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "message_id", nullable = false, unique = true)
    private String messageId;

    @Column(name = "consumer_name", nullable = false)
    private String consumerName;

    @Column(name = "consumed_at", nullable = false)
    private Instant consumedAt;

    protected ConsumedMessage() {}
}
