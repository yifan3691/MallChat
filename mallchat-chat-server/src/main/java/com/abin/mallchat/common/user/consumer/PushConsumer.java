package com.abin.mallchat.common.user.consumer;

import com.abin.mallchat.common.common.domain.dto.PushMessageDTO;
import com.abin.mallchat.common.user.domain.enums.WSPushTypeEnum;
import com.abin.mallchat.common.user.service.WebSocketService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Description:
 * Author: <a href="https://github.com/zongzibinbin">abin</a>
 * Date: 2023-08-12
 */
@Component
public class PushConsumer {
    @Autowired
    private WebSocketService webSocketService;

    // 监听当前实例的匿名队列，fanout exchange 会把推送通知广播到所有在线实例。
    @RabbitListener(queues = "#{pushQueue.name}")
    public void onMessage(PushMessageDTO message) {
        WSPushTypeEnum wsPushTypeEnum = WSPushTypeEnum.of(message.getPushType());
        switch (wsPushTypeEnum) {
            case USER:
                message.getUidList().forEach(uid -> {
                    webSocketService.sendToUid(message.getWsBaseMsg(), uid);
                });
                break;
            case ALL:
                webSocketService.sendToAllOnline(message.getWsBaseMsg(), null);
                break;
        }
    }
}
