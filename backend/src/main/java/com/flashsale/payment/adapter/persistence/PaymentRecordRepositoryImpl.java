package com.flashsale.payment.adapter.persistence;

import com.flashsale.payment.application.PaymentRecordRepository;
import com.flashsale.payment.domain.PaymentRecord;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentRecordRepositoryImpl implements PaymentRecordRepository {

    private final PaymentRecordJpaRepository jpaRepository;

    public PaymentRecordRepositoryImpl(PaymentRecordJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public PaymentRecord save(PaymentRecord record) {
        return jpaRepository.save(record);
    }
}
