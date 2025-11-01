package org.maibot.mods.ncada;


import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.maibot.sdk.TaskExecutorService;
import org.maibot.sdk.ioc.AutoInject;
import org.maibot.sdk.ioc.Value;
import org.maibot.sdk.model.MessageEvent;
import org.maibot.sdk.model.MessageEventFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import static org.maibot.mods.ncada.NapcatAdapterMod.PLATFORM_NAME;

public class NcProtocolDecoder extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    private static final Logger log = LoggerFactory.getLogger(NcProtocolDecoder.class);

    private final TaskExecutorService taskExecutorService;
    private final NapcatReqManager    napcatReqManager;

    /// 心跳线程唤醒条件
    /// 用于在连接断开时通知心跳线程退出
    private final    ReentrantLock           heartbeatLock      = new ReentrantLock();
    private final    Condition               heartbeatCondition = heartbeatLock.newCondition();
    /// Json映射器
    private final    JsonMapper              jsonMapper         = new JsonMapper();
    /// 配置项
    private final    AtomicReference<Config> config             = new AtomicReference<>(null);
    /// Adapter运行状态
    private volatile boolean                 running            = false;

    @AutoInject
    public NcProtocolDecoder(
      TaskExecutorService taskExecutorService,
      NapcatReqManager napcatReqManager,
      @Value("${napcat_adapter:*}") Config config
    ) {
        this.taskExecutorService = taskExecutorService;
        this.napcatReqManager = napcatReqManager;
        this.config.set(config);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx)
    throws Exception {
        log.warn("与 NapCat 的连接已断开");
        try {
            heartbeatLock.lock();
            running = false;
            heartbeatCondition.signalAll();
        } finally {
            heartbeatLock.unlock();
        }

        super.channelInactive(ctx);
    }

    @Override
    public void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame msg) {
        // 解析 JSON
        JsonNode jsonNode;
        try {
            jsonNode = jsonMapper.readTree(msg.text());
        } catch (JacksonException e) {
            log.warn("无法解析 NapCat 事件内容: {}", msg.text(), e);
            return;
        }

        // 发起异步解析
        taskExecutorService.submit(
          () -> {
              MDC.put("evtId", Integer.toHexString(System.identityHashCode(jsonNode)));
              // 根据事件类型进行处理
              log.trace("Full event JSON: \n{}", jsonNode.toPrettyString());
              try {
                  String postType = jsonNode.get("post_type").asString();
                  switch (postType) {
                      // 元事件
                      // 心跳、状态上报等
                      case "meta_event" -> metaEventDeserializer(ctx, jsonNode);
                      // 消息事件
                      // 私聊、群聊消息等
                      case "message" -> messageDeserializer(ctx, jsonNode);
                      // 通知事件
                      // 加入/退出群等
                      case "notice" -> noticeDeserializer(ctx, jsonNode);
                      // 请求事件
                      // 加好友请求、加群请求等
                      case "request" -> { /* 暂不处理请求事件 */ }
                      // 请求的返回值
                      // 详细信息等
                      case "" -> replyDeserializer(ctx, jsonNode);
                      default -> log.warn("未知的 message.post_type: {}", postType);
                  }
              } catch (Throwable e) {
                  log.error("处理 NapCat 事件时发生异常", e);
              } finally {
                  MDC.clear();
              }
          }, false
        );
    }

    /**
     * 元事件反序列化
     *
     * @param jsonNode JSON 对象
     */
    private void metaEventDeserializer(ChannelHandlerContext ctx, JsonNode jsonNode) {
        var metaEventType = jsonNode.get("meta_event_type").asString();
        var subTypeObj = jsonNode.get("sub_type");

        switch (metaEventType) {
            case "lifecycle" -> {
                // 处理生命周期事件
                if (subTypeObj == null) {
                    log.warn("元事件 lifecycle 缺少 sub_type 字段.");
                    return;
                }
                var subType = subTypeObj.asString();
                if (subType.equals("connect")) {
                    log.info("与 NapCat 连接成功");

                    if (!running) {
                        running = true;
                        // 启动心跳监测线程
                        taskExecutorService.submit(
                          () -> {
                              log.trace("NapCat心跳监测线程已启动");
                              while (running) {
                                  try {
                                      heartbeatLock.lock();
                                      if (!this.heartbeatCondition.await(60, TimeUnit.SECONDS)) {
                                          // 超时未唤醒，心跳异常，断开连接
                                          log.warn("NapCat心跳包超时，已断开连接。请检查NapCat是否正常运行，或网络连接是否稳定。");
                                          var eventLoop = ctx.channel().eventLoop();
                                          if (eventLoop.isShuttingDown() || eventLoop.isShutdown()) {
                                              ctx.close();
                                          } else {
                                              eventLoop.execute(ctx::close);
                                          }
                                          break;
                                      }
                                  } catch (InterruptedException e) {
                                      // 忽略中断异常
                                      break;
                                  } finally {
                                      heartbeatLock.unlock();
                                  }
                              }
                              log.trace("NapCat心跳监测线程已终止");
                          }, true
                        );
                    }
                }
            }
            case "heartbeat" -> {
                // 处理心跳事件
                var statusJson = jsonNode.get("status");
                if (statusJson.get("online").asBoolean() && statusJson.get("good").asBoolean()) {
                    log.trace("NapCat 心跳正常");
                } else {
                    log.warn(
                      "NapCat 心跳异常: online={}, good={}",
                      statusJson.get("online").asBoolean(),
                      statusJson.get("good").asBoolean()
                    );
                }

                try {
                    heartbeatLock.lock();
                    // 在超时前唤醒心跳监测线程
                    heartbeatCondition.signalAll();
                } finally {
                    heartbeatLock.unlock();
                }
            }
            default -> log.warn("未知的 meta_event_type: {}", metaEventType);
        }
    }

    /**
     * 应用消息过滤器
     *
     * @param senderId    发送者ID
     * @param groupId     群ID（私聊消息可为null）
     * @param messageType 消息类型
     * @return 是否过滤该消息
     */
    private boolean applyFilters(String senderId, String groupId, MessageEvent.MessageType messageType) {
        var config = this.config.get();

        // 检查是否为全局黑名单用户
        if (config.banned().bannedUserSet().contains(senderId)) {
            log.debug("已封禁用户发送的消息被过滤，发送者ID: {}", senderId);
            return true;
        }

        switch (messageType) {
            case PRIVATE, GROUP_TEMP -> {
                // 应用私聊过滤器
                if (config.friendFilter().filterType() == Config.FilterType.WHITELIST) {
                    if (!config.friendFilter().friendSet().contains(senderId)) {
                        log.debug("已过滤非白名单内好友的私聊消息，发送者ID: {}", senderId);
                        return true;
                    }
                } else if (config.friendFilter().filterType() == Config.FilterType.BLACKLIST) {
                    if (config.friendFilter().friendSet().contains(senderId)) {
                        log.debug("已过滤黑名单内好友的私聊消息，发送者ID: {}", senderId);
                        return true;
                    }
                }
            }
            case GROUP -> {
                // 应用群聊过滤器
                if (config.groupFilter().filterType() == Config.FilterType.WHITELIST) {
                    if (!config.groupFilter().groupSet().contains(groupId)) {
                        log.debug("已过滤非白名单内群的群聊消息，群ID: {}", groupId);
                        return true;
                    }
                } else if (config.groupFilter().filterType() == Config.FilterType.BLACKLIST) {
                    if (config.groupFilter().groupSet().contains(groupId)) {
                        log.debug("已过滤黑名单内群的群聊消息，群ID: {}", groupId);
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private MessageEvent.MessageSeg parseMessageContent(JsonNode messageJson) {
        // 解析消息内容为 MessageSeg 对象
        return null;
    }

    /**
     * 消息反序列化
     *
     * @param rawJsonNode JSON 对象
     */
    private void messageDeserializer(ChannelHandlerContext ctx, JsonNode rawJsonNode) {
        var msgEventFactory = new MessageEventFactory();

        // 消息ID（可用于识别，但进入数据库时不能用来作为主键索引，原因见NapCat实现）
        msgEventFactory.putExtra("message_id", rawJsonNode.get("message_id").asString());
        // 原始消息
        //msgEventFactory.putExtra("raw_message", jsonNode.get("raw_message").asString());

        // 平台标识
        msgEventFactory.setPlatform(PLATFORM_NAME);

        // 消息时间戳（秒级，由NapCat生成）
        msgEventFactory.setTimestamp(rawJsonNode.get("time").asLong());

        // 消息类型（私聊/群聊）
        var messageType = rawJsonNode.get("message_type").asString();
        var subTypeObj = rawJsonNode.get("sub_type");

        if (subTypeObj == null) {
            log.warn("Private message missing sub_type");
            return;
        }
        var subType = subTypeObj.asString();

        switch (messageType) {
            case "private" -> {

                switch (subType) {
                    case "friend" -> {
                        // 处理好友私聊消息
                        if (applyFilters(
                          rawJsonNode.get("sender").get("user_id").asString(),
                          null,
                          MessageEvent.MessageType.PRIVATE
                        )) {
                            return;
                        }

                        msgEventFactory.setMessageType(MessageEvent.MessageType.PRIVATE);

                        var senderInfoJson = rawJsonNode.get("sender");
                        msgEventFactory.setSenderInfo(new MessageEvent.EntityInfo(
                          senderInfoJson.get("user_id").asString(),
                          senderInfoJson.get("nickname").asString(),
                          senderInfoJson.get("card").asString()
                        ));
                    }
                    case "group" -> {
                        // 处理群内私聊消息（临时会话）
                        // 暂不做支持
                        //msgEventFactory.setMessageType(MessageEvent.MessageType.GROUP_TEMP);

                        return;
                    }
                    default -> log.warn("Unknown private message sub_type: {}", subType);
                }
            }
            case "group" -> {
                switch (subType) {
                    case "normal" -> {
                        if (applyFilters(
                          rawJsonNode.get("sender").get("user_id").asString(),
                          rawJsonNode.get("group_id").asString(),
                          MessageEvent.MessageType.GROUP
                        )) {
                            return;
                        }

                        // 处理普通群消息
                        msgEventFactory.setMessageType(MessageEvent.MessageType.GROUP);

                        var senderInfoJson = rawJsonNode.get("sender");
                        msgEventFactory.setSenderInfo(new MessageEvent.EntityInfo(
                          senderInfoJson.get("user_id").asString(),
                          senderInfoJson.get("nickname").asString(),
                          senderInfoJson.get("card").asString()
                        ));

                        msgEventFactory.setGroupInfo(new MessageEvent.GroupInfo(
                          rawJsonNode.get("group_id").asString(),
                          rawJsonNode.get("group_name").asString()
                        ));
                    }
                    case "anonymous" -> {
                        // 处理匿名群消息（弃用）
                        return;
                    }
                    default -> log.warn("Unknown group message sub_type: {}", subType);
                }
            }
        }

        // 消息内容
        msgEventFactory.setMessage(parseMessageContent(rawJsonNode));

        log.info(msgEventFactory.build().toString());
    }

    private void noticeDeserializer(ChannelHandlerContext ctx, JsonNode jsonNode) {

    }

    private void replyDeserializer(ChannelHandlerContext ctx, JsonNode jsonNode) {
        if (!jsonNode.has("echo")) {
            log.warn("NapCat 回复消息缺少 echo 字段.");
            return;
        }
        var echo = jsonNode.get("echo").asString();
        napcatReqManager.putResponse(echo, jsonNode);
    }
}
