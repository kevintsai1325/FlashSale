package com.flashsale;

import com.flashsale.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P5 之前這支測試也檢查 RabbitMQ。platform 現在不收發任何訊息，所以只剩 Redis ——
 * 排程的分散式鎖住在那裡，那是這個服務對 Redis 唯一的用途。
 */
class RedisConnectivityIT extends AbstractIntegrationTest {

    @Autowired StringRedisTemplate redisTemplate;

    @Test
    void applicationContextLoadsWithRedisReachable() {
        redisTemplate.opsForValue().set("smoke-test-key", "ok");
        assertThat(redisTemplate.opsForValue().get("smoke-test-key")).isEqualTo("ok");
    }
}
