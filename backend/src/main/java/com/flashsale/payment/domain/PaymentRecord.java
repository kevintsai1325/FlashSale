package com.flashsale.payment.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payment_records")
public class PaymentRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentResult result;

    @Column(name = "simulated_transaction_id", nullable = false)
    private String simulatedTransactionId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PaymentRecord() {}

    public static PaymentRecord record(Long orderId, PaymentResult result) {
        PaymentRecord record = new PaymentRecord();
        record.orderId = orderId;
        record.result = result;
        record.simulatedTransactionId = "SIM-" + UUID.randomUUID();
        record.createdAt = Instant.now();
        return record;
    }

    public Long getId() { return id; }
    public Long getOrderId() { return orderId; }
    public PaymentResult getResult() { return result; }
    public String getSimulatedTransactionId() { return simulatedTransactionId; }
}
