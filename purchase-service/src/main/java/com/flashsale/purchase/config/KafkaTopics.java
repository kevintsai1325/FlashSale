package com.flashsale.purchase.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * topic 名稱與分區數是跨服務的契約，所以兩邊各存一份字面值（與佇列名稱同一套做法）。
 * 分區數必須一致：`NewTopic` 對已存在的 topic 不會改動它，兩邊寫不同的數字只會讓
 * 「先啟動的那個說了算」，而那是一種很難查的不一致。
 */
@Configuration
public class KafkaTopics {

    public static final String PURCHASE_EVENTS = "flashsale.purchase-events";

    @Bean
    public NewTopic purchaseEventsTopic() {
        return TopicBuilder.name(PURCHASE_EVENTS).partitions(3).replicas((short) 1).build();
    }
}
