package com.flashsale;

import com.flashsale.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class RedisRabbitConnectivityIT extends AbstractIntegrationTest {

    @Autowired StringRedisTemplate redisTemplate;
    @Autowired RabbitTemplate rabbitTemplate;

    @Test
    void applicationContextLoadsWithRedisAndRabbitMqReachable() {
        redisTemplate.opsForValue().set("smoke-test-key", "ok");
        assertThat(redisTemplate.opsForValue().get("smoke-test-key")).isEqualTo("ok");
        assertThat(rabbitTemplate.getConnectionFactory().createConnection().isOpen()).isTrue();
    }
}
