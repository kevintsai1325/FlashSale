package com.flashsale.payment.adapter.persistence;

import com.flashsale.payment.domain.PaymentRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRecordJpaRepository extends JpaRepository<PaymentRecord, Long> {}
