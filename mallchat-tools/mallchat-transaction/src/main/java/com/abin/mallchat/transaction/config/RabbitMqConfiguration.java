package com.abin.mallchat.transaction.config;

import com.abin.mallchat.transaction.constant.RabbitMqConstant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AnonymousQueue;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Description: RabbitMQ基础配置
 * Author: <a href="https://github.com/zongzibinbin">abin</a>
 * Date: 2026-04-19
 */
@Slf4j
@EnableRabbit
@Configuration
public class RabbitMqConfiguration {

    @Bean
    public MessageConverter rabbitMessageConverter() {
        // 生产者和 @RabbitListener 共用 JSON 序列化，保证 DTO 反序列化一致。
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter rabbitMessageConverter) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(rabbitMessageConverter);
        // 开启 mandatory 后，消息路由不到队列会触发 returnsCallback。
        rabbitTemplate.setMandatory(true);
        rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            if (!ack) {
                log.error("RabbitMQ send confirm fail correlationData:{},cause:{}", correlationData, cause);
            }
        });
        rabbitTemplate.setReturnsCallback(returned -> log.error("RabbitMQ message returned exchange:{},routingKey:{},replyCode:{},replyText:{}",
                returned.getExchange(), returned.getRoutingKey(), returned.getReplyCode(), returned.getReplyText()));
        return rabbitTemplate;
    }

    @Bean
    public DirectExchange sendMsgExchange() {
        // 聊天消息只允许一个实例做后置处理，使用 direct exchange + 共享队列。
        return new DirectExchange(RabbitMqConstant.SEND_MSG_EXCHANGE, true, false);
    }

    @Bean
    public Queue sendMsgQueue() {
        // durable 队列用于承接 chat_send_msg，避免服务重启时丢失待处理消息。
        return new Queue(RabbitMqConstant.SEND_MSG_QUEUE, true);
    }

    @Bean
    public Binding sendMsgBinding(@Qualifier("sendMsgQueue") Queue sendMsgQueue,
                                  @Qualifier("sendMsgExchange") DirectExchange sendMsgExchange) {
        return BindingBuilder.bind(sendMsgQueue).to(sendMsgExchange).with(RabbitMqConstant.SEND_MSG_ROUTING_KEY);
    }

    @Bean
    public FanoutExchange pushExchange() {
        // WebSocket 连接在各实例内存中，fanout 用来通知所有在线实例。
        return new FanoutExchange(RabbitMqConstant.PUSH_EXCHANGE, true, false);
    }

    @Bean
    public Queue pushQueue() {
        // 匿名队列代表当前应用实例，实例下线后队列自动删除。
        return new AnonymousQueue();
    }

    @Bean
    public Binding pushBinding(@Qualifier("pushQueue") Queue pushQueue,
                               @Qualifier("pushExchange") FanoutExchange pushExchange) {
        return BindingBuilder.bind(pushQueue).to(pushExchange);
    }

    @Bean
    public FanoutExchange loginMsgExchange() {
        // 登录成功通知需要广播到所有实例，实际只有持有扫码连接的实例会处理。
        return new FanoutExchange(RabbitMqConstant.LOGIN_MSG_EXCHANGE, true, false);
    }

    @Bean
    public Queue loginMsgQueue() {
        // 每个实例独立监听一条匿名队列，模拟 RocketMQ BROADCASTING。
        return new AnonymousQueue();
    }

    @Bean
    public Binding loginMsgBinding(@Qualifier("loginMsgQueue") Queue loginMsgQueue,
                                   @Qualifier("loginMsgExchange") FanoutExchange loginMsgExchange) {
        return BindingBuilder.bind(loginMsgQueue).to(loginMsgExchange);
    }

    @Bean
    public FanoutExchange scanMsgExchange() {
        // 扫码成功通知同样广播，避免请求落到非 WebSocket 所在实例。
        return new FanoutExchange(RabbitMqConstant.SCAN_MSG_EXCHANGE, true, false);
    }

    @Bean
    public Queue scanMsgQueue() {
        // 临时队列只保存当前在线实例需要接收的扫码状态通知。
        return new AnonymousQueue();
    }

    @Bean
    public Binding scanMsgBinding(@Qualifier("scanMsgQueue") Queue scanMsgQueue,
                                  @Qualifier("scanMsgExchange") FanoutExchange scanMsgExchange) {
        return BindingBuilder.bind(scanMsgQueue).to(scanMsgExchange);
    }
}
