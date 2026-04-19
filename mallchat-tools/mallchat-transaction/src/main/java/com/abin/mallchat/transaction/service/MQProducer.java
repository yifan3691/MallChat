package com.abin.mallchat.transaction.service;

import com.abin.mallchat.transaction.annotation.SecureInvoke;
import com.abin.mallchat.transaction.constant.RabbitMqConstant;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Description: 发送mq工具类
 * Author: <a href="https://github.com/zongzibinbin">abin</a>
 * Date: 2023-08-12
 */
public class MQProducer {

    private static final long CONFIRM_TIMEOUT_SECONDS = 5L;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    public void sendMsg(String topic, Object body) {
        RouteInfo routeInfo = getRouteInfo(topic);
        rabbitTemplate.convertAndSend(routeInfo.exchange, routeInfo.routingKey, body);
    }

    /**
     * 发送可靠消息，在事务提交后保证发送成功
     *
     * @param topic
     * @param body
     */
    @SecureInvoke
    public void sendSecureMsg(String topic, Object body, Object key) {
        RouteInfo routeInfo = getRouteInfo(topic);
        // 每条可靠消息单独关联 confirm，失败时抛异常交给 SecureInvoke 重试。
        CorrelationData correlationData = new CorrelationData(buildCorrelationId(topic, key));
        rabbitTemplate.convertAndSend(routeInfo.exchange, routeInfo.routingKey, body, message -> {
            // 保留原 RocketMQ KEYS 语义，便于从 broker 日志和业务日志追踪 msgId。
            message.getMessageProperties().setHeader(RabbitMqConstant.MESSAGE_KEY_HEADER, key);
            return message;
        }, correlationData);
        waitForConfirm(correlationData, routeInfo);
    }

    private void waitForConfirm(CorrelationData correlationData, RouteInfo routeInfo) {
        try {
            // convertAndSend 只代表发布动作发出，这里必须等待 broker confirm 才算可靠发送成功。
            CorrelationData.Confirm confirm = correlationData.getFuture().get(CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!confirm.isAck()) {
                throw new RuntimeException("RabbitMQ send confirm nack, exchange:" + routeInfo.exchange
                        + ",routingKey:" + routeInfo.routingKey + ",reason:" + confirm.getReason());
            }
            // mandatory return 表示消息到达 exchange 但未路由到任何队列，也必须触发补偿。
            ReturnedMessage returned = correlationData.getReturned();
            if (returned != null) {
                throw new RuntimeException("RabbitMQ message returned, exchange:" + returned.getExchange()
                        + ",routingKey:" + returned.getRoutingKey()
                        + ",replyCode:" + returned.getReplyCode()
                        + ",replyText:" + returned.getReplyText());
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("RabbitMQ send confirm interrupted, exchange:" + routeInfo.exchange
                    + ",routingKey:" + routeInfo.routingKey, e);
        } catch (Exception e) {
            throw new RuntimeException("RabbitMQ send confirm timeout or fail, exchange:" + routeInfo.exchange
                    + ",routingKey:" + routeInfo.routingKey, e);
        }
    }

    private String buildCorrelationId(String topic, Object key) {
        return topic + ":" + key + ":" + UUID.randomUUID();
    }

    private RouteInfo getRouteInfo(String topic) {
        // 对外仍沿用业务 topic，内部映射到 RabbitMQ exchange/routingKey。
        switch (topic) {
            case RabbitMqConstant.SEND_MSG_TOPIC:
                return new RouteInfo(RabbitMqConstant.SEND_MSG_EXCHANGE, RabbitMqConstant.SEND_MSG_ROUTING_KEY);
            case RabbitMqConstant.PUSH_TOPIC:
                return new RouteInfo(RabbitMqConstant.PUSH_EXCHANGE, RabbitMqConstant.DEFAULT_FANOUT_ROUTING_KEY);
            case RabbitMqConstant.LOGIN_MSG_TOPIC:
                return new RouteInfo(RabbitMqConstant.LOGIN_MSG_EXCHANGE, RabbitMqConstant.DEFAULT_FANOUT_ROUTING_KEY);
            case RabbitMqConstant.SCAN_MSG_TOPIC:
                return new RouteInfo(RabbitMqConstant.SCAN_MSG_EXCHANGE, RabbitMqConstant.DEFAULT_FANOUT_ROUTING_KEY);
            default:
                throw new IllegalArgumentException("unknown mq topic:" + topic);
        }
    }

    private static class RouteInfo {
        private final String exchange;
        private final String routingKey;

        private RouteInfo(String exchange, String routingKey) {
            this.exchange = exchange;
            this.routingKey = routingKey;
        }
    }
}
