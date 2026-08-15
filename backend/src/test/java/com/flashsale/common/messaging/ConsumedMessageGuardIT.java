package com.flashsale.common.messaging;

import com.flashsale.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class ConsumedMessageGuardIT extends AbstractIntegrationTest {

    @Autowired ConsumedMessageGuard guard;

    @Test
    void firstConsumeSucceedsSecondIsDuplicate() {
        boolean first = guard.tryConsume("msg-1", "test-consumer");
        boolean second = guard.tryConsume("msg-1", "test-consumer");

        assertThat(first).isTrue();
        assertThat(second).isFalse();
    }

    @Test
    void differentMessageIdsAreIndependent() {
        boolean a = guard.tryConsume("msg-a", "test-consumer");
        boolean b = guard.tryConsume("msg-b", "test-consumer");

        assertThat(a).isTrue();
        assertThat(b).isTrue();
    }
}
