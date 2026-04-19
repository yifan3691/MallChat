package com.abin.mallchat.common.user.consumer;

import com.abin.mallchat.common.common.domain.dto.ScanSuccessMessageDTO;
import com.abin.mallchat.common.user.service.WebSocketService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Description: 将扫码成功的信息发送给对应的用户,等待授权
 * Author: <a href="https://github.com/zongzibinbin">abin</a>
 * Date: 2023-08-12
 */
@Component
public class ScanSuccessConsumer {
    @Autowired
    private WebSocketService webSocketService;

    // 扫码成功事件广播到所有实例，用本地 code 匹配等待授权的 WebSocket 连接。
    @RabbitListener(queues = "#{scanMsgQueue.name}")
    public void onMessage(ScanSuccessMessageDTO scanSuccessMessageDTO) {
        webSocketService.scanSuccess(scanSuccessMessageDTO.getCode());
    }

}
