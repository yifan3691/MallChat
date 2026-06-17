package com.abin.mallchat.transaction.constant;

/**
 * Description: RabbitMQ配置常量
 * Author: <a href="https://github.com/zongzibinbin">abin</a>
 * Date: 2026-04-19
 */
public interface RabbitMqConstant {

    /**
     * 聊天消息发送后置处理：共享队列，保证多实例下只被一个实例消费。
     */
    String SEND_MSG_TOPIC = "chat_send_msg";
    String SEND_MSG_QUEUE = "chat_send_msg_group";
    String SEND_MSG_EXCHANGE = "mallchat.chat.send.direct";
    String SEND_MSG_ROUTING_KEY = "chat_send_msg";

    /**
     * WebSocket 推送：fanout 广播到每个在线实例的本地队列。
     */
    String PUSH_TOPIC = "websocket_push";
    String PUSH_EXCHANGE = "mallchat.websocket.push.fanout";

    /**
     * 扫码授权完成后的登录通知：fanout 广播，找到持有本地 WebSocket 连接的实例。
     */
    String LOGIN_MSG_TOPIC = "user_login_send_msg";
    String LOGIN_MSG_EXCHANGE = "mallchat.user.login.fanout";

    /**
     * 扫码成功等待授权通知：fanout 广播，找到持有本地 WebSocket 连接的实例。
     */
    String SCAN_MSG_TOPIC = "user_scan_send_msg";
    String SCAN_MSG_EXCHANGE = "mallchat.user.scan.fanout";

    /**
     * fanout exchange 不依赖 routing key，统一传空字符串。
     */
    String DEFAULT_FANOUT_ROUTING_KEY = "";

    /**
     * 保留原 RocketMQ KEYS 语义，方便排查可靠消息发送。
     */
    String MESSAGE_KEY_HEADER = "KEYS";
}
