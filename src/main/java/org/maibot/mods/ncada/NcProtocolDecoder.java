package org.maibot.mods.ncada;


import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.maibot.mods.ncada.msgevt.MessageEvent;
import org.maibot.mods.ncada.msgevt.MessageEventFactory;
import org.maibot.sdk.SeqGenerator;
import org.maibot.sdk.TaskExecutorService;
import org.maibot.sdk.ioc.AutoInject;
import org.maibot.sdk.ioc.Value;
import org.maibot.sdk.model.msgevt.MessageMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import static org.maibot.mods.ncada.NapcatAdapterMod.PLATFORM_NAME;

public class NcProtocolDecoder extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    private static final Logger log = LoggerFactory.getLogger(NcProtocolDecoder.class);


    private final TaskExecutorService taskExecutorService;
    private final NapcatReqManager    napcatReqManager;

    /// 配置项
    private final    AtomicReference<Config> config                = new AtomicReference<>();
    /// 心跳线程唤醒条件
    /// 用于在连接断开时通知心跳线程退出
    private final    ReentrantLock           heartbeatLock         = new ReentrantLock();
    private final    Condition               heartbeatCondition    = heartbeatLock.newCondition();
    /// QQ表情映射
    private final    QQFace                  qqFace                = new QQFace();
    /// Json映射器
    private final    JsonMapper              jsonMapper            = new JsonMapper();
    /// 元事件反序列化器
    private final    MetaEventDeserializer   metaEventDeserializer = new MetaEventDeserializer();
    /// 消息反序列化器
    private final    MessageDeserializer     messageDeserializer   = new MessageDeserializer();
    /// Adapter运行状态
    private volatile boolean                 running               = false;


    @AutoInject
    public NcProtocolDecoder(
      TaskExecutorService taskExecutorService,
      NapcatReqManager napcatReqManager,
      @Value("${napcat-adapter:*}") Config config
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

        var mdcCtx = MDC.getCopyOfContextMap(); // 获取当前 MDC 上下文

        // 发起异步解析
        taskExecutorService.submit(
          () -> {
              // 传递 MDC 上下文
              MDC.setContextMap(mdcCtx);
              MDC.put("evtId", Integer.toHexString(System.identityHashCode(jsonNode)));

              // 根据事件类型进行处理
              log.debug("Full event JSON: \n{}", jsonNode.toPrettyString());
              try {
                  var postTypeJsonNode = jsonNode.get("post_type");
                  var postType = postTypeJsonNode == null ? "" : postTypeJsonNode.asString();
                  switch (postType) {
                      // 元事件
                      // 心跳、状态上报等
                      case "meta_event" -> this.metaEventDeserializer.deserialize(ctx, jsonNode);
                      // 消息事件
                      // 私聊、群聊消息等
                      case "message" -> {
                          var msgEvt = this.messageDeserializer.deserialize(ctx, jsonNode);
                          // TODO: 处理消息事件（将其放入Core的事件队列中）
                      }
                      // 通知事件
                      // 加入/退出群等
                      case "notice" -> noticeDeserializer(ctx, jsonNode);
                      // 请求事件
                      // 加好友请求、加群请求等
                      case "request" -> { /* 暂不处理请求事件 */ }
                      // 请求的返回值
                      // 详细信息等
                      case "" -> echoReplyDeserializer(ctx, jsonNode);
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
     * 应用消息过滤器
     *
     * @param senderId   发送者ID
     * @param groupId    群ID（私聊消息可为null）
     * @param streamType 消息类型
     * @return 是否过滤该消息
     */
    private boolean applyFilters(String senderId, String groupId, MessageMeta.StreamType streamType) {
        var config = this.config.get();

        // 检查是否为全局黑名单用户
        if (config.banned().bannedUserSet().contains(senderId)) {
            log.debug("全局黑名单用户发送的消息被过滤，发送者ID: {}", senderId);
            return true;
        }

        switch (streamType) {
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

    private void noticeDeserializer(ChannelHandlerContext ctx, JsonNode jsonNode) {

    }

    private void echoReplyDeserializer(ChannelHandlerContext ctx, JsonNode jsonNode) {
        if (!jsonNode.has("echo")) {
            log.warn("NapCat 回复消息缺少 echo 字段.");
            return;
        }
        var echo = jsonNode.get("echo").asString();
        napcatReqManager.putResponse(echo, jsonNode);
    }

    private class MetaEventDeserializer {
        public void deserialize(ChannelHandlerContext ctx, JsonNode jsonNode) {
            var metaEventType = jsonNode.get("meta_event_type").asString();
            var subTypeObj = jsonNode.get("sub_type");

            switch (metaEventType) {
                case "lifecycle" -> handleLifecycle(ctx, subTypeObj);
                case "heartbeat" -> handleHeartbeat(jsonNode);
                default -> log.warn("未知的 meta_event_type: {}", metaEventType);
            }
        }


        private void handleHeartbeat(JsonNode jsonNode) {
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

        private void heartbeatWatchdog(ChannelHandlerContext ctx) {
            log.trace("NapCat心跳监测线程已启动");
            while (running) {
                try {
                    heartbeatLock.lock();
                    if (!heartbeatCondition.await(60, TimeUnit.SECONDS)) {
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
        }

        private void handleLifecycle(ChannelHandlerContext ctx, JsonNode subTypeObj) {
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
                    taskExecutorService.submit(() -> heartbeatWatchdog(ctx), true);
                }
            }
        }
    }

    private class MessageDeserializer {
        private static String getDisplayName(JsonNode memberInfo) {
            String displayName;
            // 优先使用群名片/备注名
            if (memberInfo.has("card") && !memberInfo.get("card").asString().isBlank()) {
                displayName = memberInfo.get("card").asString();
            } else if (memberInfo.has("nickname") && !memberInfo.get("nickname").asString().isBlank()) {
                displayName = memberInfo.get("nickname").asString();
            } else {
                displayName = "未知用户";
            }
            return displayName;
        }

        public MessageEvent deserialize(ChannelHandlerContext ctx, JsonNode rawJsonNode) {
            var msgEventFactory = new MessageEventFactory();

            // 消息ID（可用于识别，但进入数据库时不能用来作为主键索引，原因见NapCat实现）
            msgEventFactory.putExtra("message_id", rawJsonNode.get("message_id").asString());
            // 原始消息
            //msgEventFactory.putExtra("raw_message", jsonNode.get("raw_message").asString());
            // 平台标识
            msgEventFactory.setPlatform(PLATFORM_NAME);
            // 消息时间戳（秒级，由NapCat生成）
            msgEventFactory.setTimestamp(rawJsonNode.get("time").asLong());
            // 消息序列号
            msgEventFactory.setSequence(SeqGenerator.nextSeq());

            // 消息类型（私聊/群聊）
            var messageType = rawJsonNode.get("message_type").asString();
            var subTypeObj = rawJsonNode.get("sub_type");

            if (subTypeObj == null) {
                log.warn("Private message missing sub_type");
                return null;
            }
            var subType = subTypeObj.asString();

            // 提取消息来源信息（发送者、群组等）
            msgEventFactory.setStreamInfo(extractStreamInfo(rawJsonNode, messageType, subType));
            if (msgEventFactory.getStreamInfo() == null) {
                return null;
            } else {
                // 发送者信息
                msgEventFactory.setSenderInfo(msgEventFactory.getStreamInfo().privateInfo());
            }

            // 消息内容
            var message = parseMessageContent(ctx, rawJsonNode.get("message"), false, msgEventFactory.getStreamInfo());
            if (message == null) {
                return null;
            }
            msgEventFactory.setMessage(message);

            var msgEvent = msgEventFactory.build();
            log.debug(msgEvent.toString());
            return msgEvent;
        }

        private void extractText(JsonNode msgData, List<MessageEvent.MessageSeg> messageSegments) {
            messageSegments.add(MessageEvent.MessageSeg.textSeg(msgData.get("text").asString()));
        }

        private void extractQQFace(JsonNode msgData, List<MessageEvent.MessageSeg> messageSegments) {
            var faceId = msgData.get("id").asString();
            var mappedFace = qqFace.getMapping(faceId);

            if (mappedFace == null) {
                log.warn("未知的 QQ 表情 ID: {}", faceId);
                mappedFace = "[未知表情]";
            }

            messageSegments.add(MessageEvent.MessageSeg.textSeg(mappedFace));
        }

        private void extractReply(
          ChannelHandlerContext ctx,
          boolean inReply,
          JsonNode msgData,
          List<MessageEvent.MessageSeg> messageSegments,
          MessageMeta.StreamInfo streamInfo
        ) {
            if (inReply) {
                log.debug("嵌套的回复消息被忽略");
            } else {
                var msgId = msgData.get("id").asString();
                var replyDetailJsonNode = napcatReqManager.getMsgDetail(ctx, msgId);

                if (replyDetailJsonNode == null) {
                    messageSegments.add(MessageEvent.MessageSeg.textSeg("[无法定位被回复消息]"));
                } else {
                    var replyMsgSeg = parseMessageContent(ctx, replyDetailJsonNode.get("message"), true, streamInfo);
                    if (replyMsgSeg == null) {
                        replyMsgSeg = MessageEvent.MessageSeg.textSeg("无法解析的回复内容");
                    }

                    var senderInfoJsonNode = replyDetailJsonNode.get("sender");

                    String displayName = getDisplayName(senderInfoJsonNode);

                    String displayId;
                    if (senderInfoJsonNode.get("user_id") != null && !senderInfoJsonNode.get("user_id")
                                                                                        .asString()
                                                                                        .isBlank()) {
                        displayId = senderInfoJsonNode.get("user_id").asString();
                    } else {
                        displayId = "未知ID";
                    }

                    messageSegments.add(MessageEvent.MessageSeg.textSeg(String.format(
                      "回复 [<%s:%s>：",
                      displayName,
                      displayId
                    )));
                    messageSegments.add(replyMsgSeg);
                    messageSegments.add(MessageEvent.MessageSeg.textSeg("] 说："));
                }

            }
        }

        private void extractImage(JsonNode msgData, List<MessageEvent.MessageSeg> messageSegments) {
            var imageSubTypeJsonNode = msgData.get("sub_type");
            var imageSummaryJsonNode = msgData.get("summary");

            var imageSummary = imageSummaryJsonNode == null ? "" : imageSummaryJsonNode.asString();

            boolean isEmoji;

            if (imageSubTypeJsonNode != null) {
                var imageSubType = imageSubTypeJsonNode.asInt();
                switch (imageSubType) {
                    case 0 -> // 普通图片
                      isEmoji = false;
                    case 1, 2, 3, 7, 8, 10, 13 -> // 视为表情包
                      isEmoji = true;
                    case 4, 9 -> {
                        log.debug("暂不支持的图片子类型: {}", imageSubType);
                        return;
                    }
                    default -> {
                        log.warn("未知的图片子类型: {}", imageSubType);
                        return;
                    }
                }
            } else {
                isEmoji = msgData.has("emoji_id") && msgData.has("emoji_package_id");
            }

            byte[] imageData = Utils.getImgData(msgData.get("url").asString());

            if (imageData == null) {
                var defaultPrompt = isEmoji ? "[未知表情包]" : "[未知图片]";
                messageSegments.add(MessageEvent.MessageSeg.textSeg(imageSummary.isBlank() ? defaultPrompt : imageSummary));
            } else {
                if (isEmoji) {
                    messageSegments.add(MessageEvent.MessageSeg.emojiSeg(imageData));
                } else {
                    messageSegments.add(MessageEvent.MessageSeg.imageSeg(imageData));
                }
            }
        }

        private void extractAt(
          ChannelHandlerContext ctx,
          JsonNode msgData,
          List<MessageEvent.MessageSeg> messageSegments,
          MessageMeta.StreamInfo streamInfo
        ) {
            var targetId = msgData.get("qq").asString();

            if (targetId.equals(config.get().qqAccount())) {
                // At 了机器人自己
                var selfInfo = napcatReqManager.getSelfInfo(ctx);
                if (selfInfo == null) {
                    messageSegments.add(MessageEvent.MessageSeg.atSeg(
                      String.format("@<%s:%s> ", config.get().nickname(), config.get().qqAccount()),
                      true
                    ));
                } else {
                    messageSegments.add(MessageEvent.MessageSeg.atSeg(
                      String.format(
                        "@<%s:%s> ",
                        selfInfo.get("nickname").asString(),
                        selfInfo.get("user_id").asString()
                      ),
                      true
                    ));
                }
            } else {
                // At 了其他人 （一定是在群聊中，私聊没有 At）
                var memberInfo = napcatReqManager.getGroupMemberInfo(
                  ctx,
                  streamInfo.groupInfo().platformId(),
                  targetId
                );
                if (memberInfo == null) {
                    messageSegments.add(MessageEvent.MessageSeg.atSeg(
                      String.format("@<未知用户:%s> ", targetId),
                      false
                    ));
                } else {
                    String displayName = getDisplayName(memberInfo);

                    messageSegments.add(MessageEvent.MessageSeg.atSeg(
                      String.format("@<%s:%s>", displayName, targetId),
                      false
                    ));
                }
            }
        }

        private void extractRps(
          JsonNode msgData,
          List<MessageEvent.MessageSeg> messageSegments
        ) {
            var result = msgData.get("result").asString();

            switch (result) {
                case "1" -> messageSegments.add(MessageEvent.MessageSeg.textSeg("[石头剪刀布: 布]"));
                case "2" -> messageSegments.add(MessageEvent.MessageSeg.textSeg("[石头剪刀布: 剪刀]"));
                case "3" -> messageSegments.add(MessageEvent.MessageSeg.textSeg("[石头剪刀布: 石头]"));
                default -> log.warn("未知的石头剪刀布结果: {}", result);
            }
        }

        private void extractDice(
          JsonNode msgData,
          List<MessageEvent.MessageSeg> messageSegments
        ) {
            var result = msgData.get("result").asString();
            messageSegments.add(MessageEvent.MessageSeg.textSeg(String.format("[掷骰子: %s]", result)));
        }

        private MessageEvent.MessageSeg parseMessageContent(
          ChannelHandlerContext ctx,
          JsonNode messageJson,
          boolean inReply,
          MessageMeta.StreamInfo streamInfo
        ) {
            // 解析消息内容为 MessageSeg 对象
            if (messageJson == null) {
                log.warn("消息内容为空");
                return null;
            } else if (!messageJson.isArray()) {
                log.warn("消息内容格式错误，预期为数组");
                return null;
            }

            List<MessageEvent.MessageSeg> messageSegments = new ArrayList<>(messageJson.size());

            for (JsonNode subMsg : messageJson.values()) {
                var msgData = subMsg.get("data");
                switch (subMsg.get("type").asString()) {
                    case "text" -> // 纯文本消息
                      extractText(msgData, messageSegments);
                    case "face" -> // QQ表情消息
                      extractQQFace(msgData, messageSegments);
                    case "reply" -> // 回复消息
                      extractReply(ctx, inReply, msgData, messageSegments, streamInfo);
                    case "image" -> // 图片消息
                      extractImage(msgData, messageSegments);
                    case "record" -> {
                        // 语音消息
                    }
                    case "video" -> {
                        // 视频消息
                    }
                    case "at" -> // @消息
                      extractAt(ctx, msgData, messageSegments, streamInfo);
                    case "rps" -> // 石头剪刀布
                      extractRps(msgData, messageSegments);
                    case "dice" -> // 掷骰子
                      extractDice(msgData, messageSegments);
                    case "shake" -> {
                        // 抖动消息
                    }
                    case "share" -> {
                        // 分享消息
                    }
                    case "forward" -> // 转发消息
                      new ForwardMessageExtractor().extractForward(ctx, msgData, messageSegments);
                    case "node" -> {
                        // 转发消息节点
                    }
                    default -> log.warn("未知的消息子类型: {}", subMsg.get("type").asString());
                }
            }

            if (messageSegments.isEmpty()) {
                log.warn("消息内容解析后为空");
                return null;
            } else {
                return MessageEvent.MessageSeg.listSeg(messageSegments);
            }
        }

        private MessageMeta.StreamInfo extractStreamInfo(
          JsonNode rawJsonNode,
          String messageType,
          String subType
        ) {
            switch (messageType) {
                case "private" -> {
                    switch (subType) {
                        case "friend" -> {
                            // 处理好友私聊消息
                            if (applyFilters(
                              rawJsonNode.get("sender").get("user_id").asString(),
                              null,
                              MessageMeta.StreamType.PRIVATE
                            )) {
                                // 过滤该消息
                                return null;
                            }

                            var senderInfoJson = rawJsonNode.get("sender");

                            return new MessageMeta.StreamInfo(
                              new MessageMeta.EntityInfo(
                                senderInfoJson.get("user_id").asString(),
                                senderInfoJson.get("nickname").asString(),
                                senderInfoJson.get("card").asString()
                              ),
                              null,
                              MessageMeta.StreamType.PRIVATE
                            );
                        }
                        case "group" -> {
                            // 处理群内私聊消息（临时会话）
                            // 暂不做支持
                            //msgEventFactory.setMessageType(MessageEvent.MessageType.GROUP_TEMP);

                            return null;
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
                              MessageMeta.StreamType.GROUP
                            )) {
                                return null;
                            }

                            // 处理普通群消息
                            var senderInfoJson = rawJsonNode.get("sender");

                            return new MessageMeta.StreamInfo(
                              new MessageMeta.EntityInfo(
                                senderInfoJson.get("user_id").asString(),
                                senderInfoJson.get("nickname").asString(),
                                senderInfoJson.get("card").asString()
                              ),
                              new MessageMeta.GroupInfo(
                                rawJsonNode.get("group_id").asString(),
                                rawJsonNode.get("group_name").asString()
                              ),
                              MessageMeta.StreamType.GROUP
                            );
                        }
                        case "anonymous" -> {
                            // 处理匿名群消息（弃用）
                            return null;
                        }
                        default -> log.warn("Unknown group message sub_type: {}", subType);
                    }
                }
            }
            return null;
        }

        private class ForwardMessageExtractor {
            private MessageEvent.MessageSeg recursivelyExtractForward(
              ChannelHandlerContext ctx,
              JsonNode forwardJson
            ) {
                if (forwardJson == null) {
                    log.warn("转发消息内容为空");
                    return null;
                } else if (!forwardJson.isArray()) {
                    log.warn("转发消息内容格式错误，预期为数组");
                    return null;
                } else if (forwardJson.isEmpty()) {
                    log.warn("转发消息内容为空数组");
                    return null;
                }

                List<MessageEvent.MessageSeg> forwardSegments = new ArrayList<>();

                var firstMsg = forwardJson.get(0);

                var streamInfo = extractStreamInfo(
                  firstMsg,
                  firstMsg.get("message_type").asString(),
                  firstMsg.get("sub_type").asString()
                );
                if (streamInfo == null) {
                    log.warn("无法提取转发消息的流信息");
                    return null;
                }

                switch (streamInfo.streamType()) {
                    case PRIVATE -> forwardSegments.add(MessageEvent.MessageSeg.textSeg("[私聊的聊天记录："));
                    case GROUP -> forwardSegments.add(MessageEvent.MessageSeg.textSeg("[群聊的聊天记录："));
                    case GROUP_TEMP, GROUP_ANONYMOUS -> {
                        log.warn("不支持的转发消息流类型: {}", streamInfo.streamType());
                        return null;
                    }
                }

                for (JsonNode node : forwardJson) {
                    var senderInfoJsonNode = node.get("sender");

                    String displayName = getDisplayName(senderInfoJsonNode);

                    String displayId = senderInfoJsonNode.has("user_id")
                      ? senderInfoJsonNode.get("user_id").asString()
                      : "未知ID";

                    forwardSegments.add(MessageEvent.MessageSeg.textSeg(String.format(
                      "\n[<%s:%s>:",
                      displayName,
                      displayId
                    )));

                    forwardSegments.add(parseMessageContent(ctx, node.get("message"), false, streamInfo));

                    forwardSegments.add(MessageEvent.MessageSeg.textSeg("]"));
                }

                forwardSegments.add(MessageEvent.MessageSeg.textSeg("]"));

                return MessageEvent.MessageSeg.listSeg(forwardSegments);
            }

            private void extractForward(
              ChannelHandlerContext ctx,
              JsonNode msgData,
              List<MessageEvent.MessageSeg> messageSegments
            ) {
                var forwardMsgDetailJsonNode = napcatReqManager.getForwardMsgDetail(
                  ctx,
                  msgData.get("id").asString()
                );
                if (forwardMsgDetailJsonNode != null) {
                    var forwardMessage = recursivelyExtractForward(
                      ctx,
                      forwardMsgDetailJsonNode.get("messages")
                    );
                    if (forwardMessage != null) {
                        // 解析图片

                        messageSegments.add(forwardMessage);
                        return;
                    }
                }
                messageSegments.add(MessageEvent.MessageSeg.textSeg("[转发消息解析失败]"));
            }
        }
    }
}
