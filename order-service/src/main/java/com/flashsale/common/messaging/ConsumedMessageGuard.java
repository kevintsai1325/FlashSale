package com.flashsale.common.messaging;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ConsumedMessageGuard {

    private final ConsumedMessageJpaRepository repository;

    public ConsumedMessageGuard(ConsumedMessageJpaRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public boolean tryConsume(String messageId, String consumerName) {
        return repository.insertIfAbsent(messageId, consumerName) == 1;
    }
}
