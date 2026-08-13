package com.flashsale.common.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    public static final String ORDER_EXCHANGE = "order.exchange";

    public static final String CREATE_ORDER_QUEUE = "order.create.queue";
    public static final String CREATE_ORDER_ROUTING_KEY = "order.create";
    public static final String CREATE_ORDER_DLX = "order.create.dlx";
    public static final String CREATE_ORDER_DLQ = "order.create.queue.dlq";

    public static final String STOCK_RELEASE_QUEUE = "stock.release.queue";
    public static final String STOCK_RELEASE_ROUTING_KEY = "stock.release";
    public static final String STOCK_RELEASE_DLX = "stock.release.dlx";
    public static final String STOCK_RELEASE_DLQ = "stock.release.queue.dlq";

    @Bean
    public DirectExchange orderExchange() {
        return new DirectExchange(ORDER_EXCHANGE);
    }

    @Bean
    public DirectExchange createOrderDlx() {
        return new DirectExchange(CREATE_ORDER_DLX);
    }

    @Bean
    public Queue createOrderQueue() {
        return QueueBuilder.durable(CREATE_ORDER_QUEUE)
            .withArgument("x-dead-letter-exchange", CREATE_ORDER_DLX)
            .withArgument("x-dead-letter-routing-key", CREATE_ORDER_ROUTING_KEY)
            .build();
    }

    @Bean
    public Queue createOrderDlq() {
        return QueueBuilder.durable(CREATE_ORDER_DLQ).build();
    }

    @Bean
    public Binding createOrderBinding(Queue createOrderQueue, DirectExchange orderExchange) {
        return BindingBuilder.bind(createOrderQueue).to(orderExchange).with(CREATE_ORDER_ROUTING_KEY);
    }

    @Bean
    public Binding createOrderDlqBinding(Queue createOrderDlq, DirectExchange createOrderDlx) {
        return BindingBuilder.bind(createOrderDlq).to(createOrderDlx).with(CREATE_ORDER_ROUTING_KEY);
    }

    @Bean
    public DirectExchange stockReleaseDlx() {
        return new DirectExchange(STOCK_RELEASE_DLX);
    }

    @Bean
    public Queue stockReleaseQueue() {
        return QueueBuilder.durable(STOCK_RELEASE_QUEUE)
            .withArgument("x-dead-letter-exchange", STOCK_RELEASE_DLX)
            .withArgument("x-dead-letter-routing-key", STOCK_RELEASE_ROUTING_KEY)
            .build();
    }

    @Bean
    public Queue stockReleaseDlq() {
        return QueueBuilder.durable(STOCK_RELEASE_DLQ).build();
    }

    @Bean
    public Binding stockReleaseBinding(Queue stockReleaseQueue, DirectExchange orderExchange) {
        return BindingBuilder.bind(stockReleaseQueue).to(orderExchange).with(STOCK_RELEASE_ROUTING_KEY);
    }

    @Bean
    public Binding stockReleaseDlqBinding(Queue stockReleaseDlq, DirectExchange stockReleaseDlx) {
        return BindingBuilder.bind(stockReleaseDlq).to(stockReleaseDlx).with(STOCK_RELEASE_ROUTING_KEY);
    }
}
