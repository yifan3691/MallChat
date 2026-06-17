# RocketMQ 替换为 RabbitMQ 迁移计划

## 目标

将 MallChat 当前依赖 RocketMQ 的消息发送与消费链路迁移到 RabbitMQ，同时保持现有业务语义：

- 消息发送后的异步路由处理仍然只由一个服务实例处理。
- WebSocket 推送、扫码成功、登录成功通知仍然广播到所有在线服务实例。
- `@SecureInvoke` 可靠调用补偿机制继续生效，避免事务提交后 MQ 发送失败导致业务消息丢失。
- 源码继续保持 Java 8 兼容。

## 当前 RocketMQ 使用点

### 依赖与配置

- `mallchat-tools/mallchat-common-starter/pom.xml`
  - 当前引入 `org.apache.rocketmq:rocketmq-spring-boot-starter:2.2.2`。
- `mallchat-chat-server/src/main/resources/application-test.properties`
  - 当前配置 `rocketmq.name-server`、`rocketmq.access-key`、`rocketmq.secret-key`。
- `mallchat-chat-server/src/main/resources/application-pro.properties`
  - 当前配置生产环境 RocketMQ 连接信息。

### 生产者

- `mallchat-tools/mallchat-transaction/src/main/java/com/abin/mallchat/transaction/service/MQProducer.java`
  - 使用 `RocketMQTemplate`。
  - `sendMsg(String topic, Object body)`：普通发送。
  - `sendSecureMsg(String topic, Object body, Object key)`：带 `@SecureInvoke`，事务提交后可靠重试发送。

### 消费者

- `mallchat-chat-server/src/main/java/com/abin/mallchat/common/chat/consumer/MsgSendConsumer.java`
  - RocketMQ 集群消费。
  - 处理 `chat_send_msg`，刷新会话、联系人、热门房间，并触发 WebSocket 推送。
- `mallchat-chat-server/src/main/java/com/abin/mallchat/common/user/consumer/PushConsumer.java`
  - RocketMQ 广播消费。
  - 处理 `websocket_push`，把消息推给本机在线 WebSocket 连接。
- `mallchat-chat-server/src/main/java/com/abin/mallchat/common/user/consumer/MsgLoginConsumer.java`
  - RocketMQ 广播消费。
  - 处理 `user_login_send_msg`，通知本机对应扫码连接登录成功。
- `mallchat-chat-server/src/main/java/com/abin/mallchat/common/user/consumer/ScanSuccessConsumer.java`
  - RocketMQ 广播消费。
  - 处理 `user_scan_send_msg`，通知本机对应扫码连接等待授权。

### 发送入口

- `MessageSendListener#messageRoute`
  - 发送 `chat_send_msg`，使用 `sendSecureMsg`。
- `WxMsgService#scan`
  - 发送 `user_login_send_msg` 或 `user_scan_send_msg`。
- `WxMsgService#authorize`
  - 发送 `user_login_send_msg`。
- `PushService#sendPushMsg`
  - 发送 `websocket_push`。

## RabbitMQ 语义映射

| 当前 RocketMQ topic | 当前消费模式 | RabbitMQ 建议模型 | 队列策略 |
| --- | --- | --- | --- |
| `chat_send_msg` | 集群消费 | direct exchange | 所有实例共享一个 durable queue，保证一条消息只被一个实例处理 |
| `websocket_push` | 广播消费 | fanout exchange | 每个应用实例一个独立队列，保证所有在线实例都收到 |
| `user_login_send_msg` | 广播消费 | fanout exchange | 每个应用实例一个独立队列，保证所有在线实例都收到 |
| `user_scan_send_msg` | 广播消费 | fanout exchange | 每个应用实例一个独立队列，保证所有在线实例都收到 |

说明：

- RabbitMQ 没有 RocketMQ `MessageModel.BROADCASTING` 的同名模型，需要通过 fanout exchange 加每实例队列模拟。
- `chat_send_msg` 不能用 fanout，否则多实例部署时会重复刷新会话、联系人和热门房间。
- WebSocket 连接保存在本机内存中，广播类队列推荐使用实例级临时队列或带实例标识的自动删除队列；服务下线后无需保留这些广播消息。
- 可靠消息仍使用现有 `@SecureInvoke` 做发送失败补偿，不需要引入 RabbitMQ 事务消息。

## 改造计划

### 1. 依赖替换

- 从 `mallchat-common-starter` 移除 RocketMQ starter。
- 引入 Spring Boot AMQP starter：
  - `org.springframework.boot:spring-boot-starter-amqp`
- 如果后续需要显式版本，优先使用 Spring Boot `2.6.7` 的依赖管理，不单独指定版本。

验收标准：

- 全仓不再存在 `org.apache.rocketmq` 生产代码依赖。
- `mvn -pl mallchat-chat-server -am compile` 不因依赖缺失失败。

### 2. 配置替换

- 将 `application-test.properties`、`application-pro.properties` 中的 `rocketmq.*` 替换为 `spring.rabbitmq.*`。
- 当前目标 RabbitMQ 服务器：
  - 管理台地址：`http://170.9.59.234:15672/#/`
  - 应用连接 host：`170.9.59.234`
  - 应用连接端口：默认使用 AMQP 端口 `5672`，不是管理台端口 `15672`
- 建议配置项：
  - `spring.rabbitmq.host`
  - `spring.rabbitmq.port`
  - `spring.rabbitmq.username`
  - `spring.rabbitmq.password`
  - `spring.rabbitmq.virtual-host`
  - `spring.rabbitmq.publisher-confirm-type=correlated`
  - `spring.rabbitmq.publisher-returns=true`
  - `spring.rabbitmq.listener.simple.acknowledge-mode=auto`
  - `spring.rabbitmq.listener.simple.retry.enabled=true`
- 配置示例：

```properties
##################rabbitmq##################
spring.rabbitmq.host=170.9.59.234
spring.rabbitmq.port=5672
spring.rabbitmq.username=${RABBITMQ_USERNAME}
spring.rabbitmq.password=${RABBITMQ_PASSWORD}
spring.rabbitmq.virtual-host=/
spring.rabbitmq.publisher-confirm-type=correlated
spring.rabbitmq.publisher-returns=true
spring.rabbitmq.listener.simple.acknowledge-mode=auto
spring.rabbitmq.listener.simple.retry.enabled=true
```

- 需要向服务器确认 RabbitMQ 的业务账号、密码和 vhost；不要直接使用管理台 URL 作为应用连接地址。
- 不在源码中写死 RabbitMQ 地址、账号或密码。

验收标准：

- profile 配置中不再出现 `rocketmq.*`。
- 本地和生产环境可通过配置文件切换 RabbitMQ 连接。

### 3. 新增 RabbitMQ 常量与基础配置

- 保留 `MQConstant` 里的业务名称，减少业务代码改动。
- 新增或扩展 RabbitMQ 相关常量：
  - exchange 名称。
  - routing key 名称。
  - durable queue 名称。
  - 广播队列名前缀。
- 新增 RabbitMQ 配置类，建议位置：
  - `mallchat-tools/mallchat-transaction/src/main/java/com/abin/mallchat/transaction/config/RabbitMqConfiguration.java`
  - 或 `mallchat-chat-server/src/main/java/com/abin/mallchat/common/common/config/RabbitMqConfig.java`
- 配置内容：
  - `Jackson2JsonMessageConverter`。
  - `RabbitTemplate` confirm/return callback 日志。
  - `chat_send_msg` direct exchange、queue、binding。
  - `websocket_push`、`user_login_send_msg`、`user_scan_send_msg` fanout exchange。
  - 每实例广播队列和 binding。

每实例广播队列建议：

- 本地开发可使用 `AnonymousQueue`。
- 多实例生产建议使用稳定实例名，例如 `${spring.application.name}-${server.port}-${random.uuid}` 或容器实例 ID。
- 队列设置为 `exclusive` 或 `autoDelete`，避免下线实例积压无意义消息。

验收标准：

- RabbitMQ 管理台能看到 1 个 `chat_send_msg` 共享队列。
- 每个在线服务实例都有各自的 3 个广播队列。

### 4. 替换生产者实现

- 修改 `MQProducer`：
  - 将 `RocketMQTemplate` 替换为 `RabbitTemplate`。
  - `sendMsg(topic, body)` 根据 topic 路由到对应 exchange。
  - `sendSecureMsg(topic, body, key)` 保留 `@SecureInvoke`，使用 RabbitMQ 发送，并把 key 写入 message header。
  - `sendSecureMsg` 必须使用 `CorrelationData` 同步等待 publisher confirm，并检查 mandatory return。
  - broker nack、消息被 return、等待 confirm 超时等情况必须抛出异常，让 `SecureInvokeService` 保留补偿记录并进入下次重试。
- 建议先保留 `MQProducer` 类名和方法签名，减少调用方改动。
- 对未知 topic 做显式异常或日志告警，避免静默发送到错误 exchange。

验收标准：

- `MessageSendListener`、`WxMsgService`、`PushService` 不需要大范围修改。
- `@SecureInvoke` 的记录保存、事务提交后发送、失败重试逻辑不被破坏。
- `sendSecureMsg` 只有在 RabbitMQ 返回 ack 且消息未被 return 后才算发送成功。
- `sendSecureMsg` 遇到 exchange 不存在、路由不到队列、broker nack 或 confirm 超时时会抛异常，`secure_invoke_record` 不会被删除。

### 5. 替换消费者注解

- 将 `@RocketMQMessageListener` 和 `RocketMQListener<T>` 替换为 `@RabbitListener`。
- `MsgSendConsumer`
  - 监听共享队列，例如 `chat_send_msg_group`。
  - 保持原 `onMessage(MsgSendMessageDTO dto)` 业务逻辑。
- `PushConsumer`
  - 监听当前实例的 `websocket_push` 广播队列。
- `MsgLoginConsumer`
  - 监听当前实例的 `user_login_send_msg` 广播队列。
- `ScanSuccessConsumer`
  - 监听当前实例的 `user_scan_send_msg` 广播队列。

验收标准：

- 生产代码中不再出现 `RocketMQMessageListener`、`RocketMQListener`、`MessageModel`。
- 4 个消费者可以正常反序列化对应 DTO。

### 6. 消息可靠性与幂等处理

- `chat_send_msg` 已通过 `sendSecureMsg` 保证发送失败补偿，需要继续保留。
- `sendSecureMsg` 的可靠发送边界是“消息已被 broker confirm ack，且 mandatory return 未返回”；不能只调用 `RabbitTemplate.convertAndSend` 后立即视为成功。
- RabbitMQ 消费失败时由 listener retry 处理；超过重试上限后需要明确策略：
  - 开发阶段可先记录错误日志。
  - 生产建议配置死信交换机和死信队列。
- 检查 `MsgSendConsumer` 业务是否天然幂等：
  - `roomDao.refreshActiveTime`
  - `contactDao.refreshOrCreateActiveTime`
  - `hotRoomCache.refreshActiveTime`
  - `pushService.sendPushMsg`
- 如果 RabbitMQ 发生重复投递，`chat_send_msg` 可能重复触发 WebSocket 推送；如要严格控制，后续可基于 `msgId` 增加消费幂等记录。

验收标准：

- 发送失败可以通过 `secure_invoke_record` 补偿重试。
- 消费失败有日志和可定位的重试或死信处理。

### 7. 测试与验证

建议按以下顺序验证：

1. 编译检查：
   - `mvn -pl mallchat-chat-server -am compile`
2. 单元级验证：
   - 使用 mock 或本地 RabbitMQ 验证 `MQProducer` topic 路由。
   - 验证 `sendSecureMsg` 在 nack、return、confirm 超时时会抛异常。
   - 验证 DTO 发送和 `@RabbitListener` 接收的 JSON 反序列化。
3. 本地集成验证：
   - 启动 RabbitMQ。
   - 启动一个 `mallchat-chat-server` 实例。
   - 发送聊天消息，确认 `MsgSendConsumer` 执行。
   - 扫码登录流程，确认 `ScanSuccessConsumer` 和 `MsgLoginConsumer` 执行。
4. 多实例验证：
   - 启动两个服务实例。
   - 发送聊天消息，确认 `MsgSendConsumer` 只被一个实例消费。
   - 触发 WebSocket 推送，确认两个实例的 `PushConsumer` 都能收到。
5. 可靠性验证：
   - 临时停止 RabbitMQ 后发送消息，确认 `secure_invoke_record` 有待重试记录。
   - 恢复 RabbitMQ，确认补偿任务重试成功并删除记录。

### 8. 上线切换

推荐使用低风险切换：

1. 先部署 RabbitMQ 基础设施和账号权限。
2. 在测试环境完成单实例、多实例和可靠性验证。
3. 停止写入或进入短维护窗口。
4. 确认 RocketMQ 中旧消息已消费完成。
5. 发布 RabbitMQ 版本。
6. 观察 RabbitMQ 队列积压、消费者错误日志、WebSocket 登录和推送链路。
7. 稳定后移除 RocketMQ 运维配置。

如果无法维护窗口：

- 可临时实现双写生产者，但消费者只切一边，验证 RabbitMQ 消息进入队列后再切消费者。
- 双写期间必须避免 `chat_send_msg` 被两套消费者同时处理，否则会导致重复推送和重复刷新。

## 建议提交拆分

1. `pom` 与配置文件替换。
2. RabbitMQ exchange、queue、converter、template 配置。
3. `MQProducer` 替换为 RabbitMQ 实现。
4. 4 个消费者改为 `@RabbitListener`。
5. 测试用例或本地验证脚本补充。
6. 删除残留 RocketMQ import、配置和测试代码。

## 风险清单

- 广播语义风险：RabbitMQ 需要每实例队列模拟广播，不能误用共享队列。
- 重复消费风险：RabbitMQ 至少一次投递，消费端要接受重复投递可能。
- 序列化风险：`RabbitTemplate` 和 `@RabbitListener` 必须使用一致的 JSON converter。
- 可靠发送风险：不能删除或绕过 `@SecureInvoke`，否则事务提交后发送失败会丢消息。
- 可靠发送确认风险：RabbitMQ publisher confirm/return 是异步信号，`sendSecureMsg` 必须同步等待并把失败转为异常，否则 `SecureInvokeService` 会误删补偿记录。
- 上线积压风险：切换前要确认 RocketMQ 旧 topic 已消费完成。
- 配置泄露风险：RabbitMQ 账号密码只能放配置或环境变量，不写进 Java 源码。

## 完成定义

- `mvn -pl mallchat-chat-server -am compile` 通过。
- 全仓生产代码无 RocketMQ import 和 starter 依赖。
- `chat_send_msg` 多实例下只消费一次。
- `websocket_push`、`user_login_send_msg`、`user_scan_send_msg` 多实例下广播到每个在线实例。
- `sendSecureMsg` 失败补偿链路可用。
- `sendSecureMsg` 对 broker nack、mandatory return 和 confirm 超时均能触发 `@SecureInvoke` 重试。
- 旧 RocketMQ 配置从应用 profile 中移除。
