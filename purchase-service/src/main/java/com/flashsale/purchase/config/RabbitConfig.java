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
 * stock.release 的 routing key 也在這裡，因為步驟 1 兩個服務共用 outbox_events 表，
 * 任何一邊的發佈器都可能撈到對方寫入的列，兩邊都必須認得全部的 event type。
 */
@Configuration
public class RabbitConfig {

    public static final String ORDER_EXCHANGE = "order.exchange";

    public static final String CREATE_ORDER_ROUTING_KEY = "order.create";
    public static final String STOCK_RELEASE_ROUTING_KEY = "stock.release";

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
