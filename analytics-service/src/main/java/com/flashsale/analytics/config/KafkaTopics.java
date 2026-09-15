package com.flashsale.analytics.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * P6：Flink 算好的即時指標。由消費端宣告 —— Flink 的 producer 只會 auto-create，
 * 而 auto-create 出來的 topic 拿的是 broker 預設值（1 分區、預設保留期）。
 *
 * 保留期刻意設成 10 分鐘：這些是「現在幾點幾分賣了多少」的瞬時值，
 * 十分鐘前的那一秒對任何人都沒有意義。權威的數字在 order_db，聚合在讀取模型裡。
 */
@Configuration
public class KafkaTopics {

    @Bean
    public NewTopic realtimeMetricsTopic(@Value("${app.kafka.realtime-metrics-topic}") String topic) {
        return TopicBuilder.name(topic)
            .partitions(1)
            .replicas((short) 1)
            .config("retention.ms", String.valueOf(10 * 60 * 1000))
            .build();
    }
}
