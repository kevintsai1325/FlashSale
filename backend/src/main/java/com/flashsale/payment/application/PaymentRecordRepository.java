package com.flashsale.payment.application;

import com.flashsale.payment.domain.PaymentRecord;

public interface PaymentRecordRepository {
    PaymentRecord save(PaymentRecord record);
}
