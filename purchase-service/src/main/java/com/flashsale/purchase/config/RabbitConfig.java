package com.flashsale.purchase.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 這些名稱是兩個服務之間的契約，所以在兩邊各存一份、字面值必須一致。
 * 共用一個 constants library 會讓兩個服務綁在同一個版本上，正是拆分要解掉的東西。
 *
 * purchase-service 發佈 order.create，並消費 purchase.resolved。
 * stock.release 的常數在步驟 2 一併移除：它只存在於「兩個服務共用同一張 outbox 表」
 * 的那個階段，現在各有各的表，這個服務永遠不會發佈那種事件。
 */
@Configuration
public class RabbitConfig {

    public static final String ORDER_EXCHANGE = "order.exchange";

    public static final String CREATE_ORDER_ROUTING_KEY = "order.create";

    public static final String PURCHASE_RESOLVED_QUEUE = "purchase.resolved.queue";
    public static final String PURCHASE_RESOLVED_ROUTING_KEY = "purchase.resolved";
    public static final String PURCHASE_RESOLVED_DLX = "purchase.resolved.dlx";
    public static final String PURCHASE_RESOLVED_DLQ = "purchase.resolved.queue.dlq";

    @Bean
    public DirectExchange orderExchange() {
        return new DirectExchange(ORDER_EXCHANGE);
    }

    @Bean
    public DirectExchange purchaseResolvedDlx() {
        return new DirectExchange(PURCHASE_RESOLVED_DLX);
    }

    @Bean
    public Queue purchaseResolvedQueue() {
        return QueueBuilder.durable(PURCHASE_RESOLVED_QUEUE)
            .withArgument("x-dead-letter-exchange", PURCHASE_RESOLVED_DLX)
            .withArgument("x-dead-letter-routing-key", PURCHASE_RESOLVED_ROUTING_KEY)
            .build();
    }

    @Bean
    public Queue purchaseResolvedDlq() {
        return QueueBuilder.durable(PURCHASE_RESOLVED_DLQ).build();
    }

    @Bean
    public Binding purchaseResolvedBinding(Queue purchaseResolvedQueue, DirectExchange orderExchange) {
        return BindingBuilder.bind(purchaseResolvedQueue).to(orderExchange).with(PURCHASE_RESOLVED_ROUTING_KEY);
    }

    @Bean
    public Binding purchaseResolvedDlqBinding(Queue purchaseResolvedDlq, DirectExchange purchaseResolvedDlx) {
        return BindingBuilder.bind(purchaseResolvedDlq).to(purchaseResolvedDlx).with(PURCHASE_RESOLVED_ROUTING_KEY);
    }
}
