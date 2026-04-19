package com.abin.mallchat.common.user.consumer;

import com.abin.mallchat.common.common.domain.dto.LoginMessageDTO;
import com.abin.mallchat.common.user.service.WebSocketService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Description: 在本地服务上找寻对应channel，将对应用户登陆，并触发所有用户收到上线事件
 * Author: <a href="https://github.com/zongzibinbin">abin</a>
 * Date: 2023-08-12
 */
@Component
public class MsgLoginConsumer {
    @Autowired
    private WebSocketService webSocketService;

    // 登录成功事件需要广播，只有持有对应扫码连接的实例会真正完成登录。
    @RabbitListener(queues = "#{loginMsgQueue.name}")
    public void onMessage(LoginMessageDTO loginMessageDTO) {
        //尝试登录登录
        webSocketService.scanLoginSuccess(loginMessageDTO.getCode(), loginMessageDTO.getUid());
    }

}
