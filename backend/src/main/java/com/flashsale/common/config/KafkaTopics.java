package com.flashsale.common.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * 領域事件的 topic。**名稱是跨服務的契約**，與 RabbitMQ 的佇列名稱同一套做法：
 * 兩邊各存一份字面值，不抽共用 library。
 *
 * 為什麼分區數是 3 而不是 1：分區是 Kafka 的平行度單位，消費者群組裡最多只有
 * 分區數那麼多個實例能同時工作。1 個分區等於把消費端永遠釘在單執行緒，
 * 而 P6 的 Flink 作業要能擴展。3 對應目前應用層的副本數。
 *
 * 為什麼不是更多：分區改多容易、改少不行（Kafka 不支援減少分區），
 * 而每個分區都是 broker 上的一組檔案與一份 replica 狀態。單節點展示叢集沒有理由先開大。
 *
 * 宣告式建立而不是靠 auto-create：auto-create 出來的 topic 會拿 broker 的預設分區數（1），
 * 症狀是「分區順序測試通過了，因為根本只有一個分區」。
 */
@Configuration
public class KafkaTopics {

    public static final String ORDER_EVENTS = "flashsale.order-events";
    public static final String PURCHASE_EVENTS = "flashsale.purchase-events";

    private static final int PARTITIONS = 3;
    private static final short REPLICAS = 1;

    @Bean
    public NewTopic orderEventsTopic() {
        return TopicBuilder.name(ORDER_EVENTS).partitions(PARTITIONS).replicas(REPLICAS).build();
    }

    /**
     * backend 不發佈搶購事件，但**要能消費**它們（P5-2 的 analytics）。
     * 宣告在這裡是為了讓 topic 一定存在 —— 消費一個不存在的 topic 會讓 consumer 反覆重試。
     */
    @Bean
    public NewTopic purchaseEventsTopic() {
        return TopicBuilder.name(PURCHASE_EVENTS).partitions(PARTITIONS).replicas(REPLICAS).build();
    }
}
