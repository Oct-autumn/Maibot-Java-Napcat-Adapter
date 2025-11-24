package org.maibot.mods.ncada.decoder;


import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.maibot.mods.ncada.Config;
import org.maibot.mods.ncada.NapcatReqManager;
import org.maibot.mods.ncada.QQFace;
import org.maibot.mods.ncada.evtbuffer.BarrierOrderedEvtQueue;
import org.maibot.sdk.SNoGenerator;
import org.maibot.sdk.TaskExecuteService;
import org.maibot.sdk.ioc.AutoInject;
import org.maibot.sdk.ioc.Value;
import org.maibot.sdk.manager.*;
import org.maibot.sdk.storage.GlobalCacheManager;
import org.maibot.sdk.storage.db.DatabaseService;
import org.maibot.sdk.storage.domain.StreamType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.cache.Cache;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class NcProtocolDecoder extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    private static final Logger log = LoggerFactory.getLogger(NcProtocolDecoder.class);


    final TaskExecuteService       taskExecuteService;
    final ObjectMapper             objectMapper;
    final DatabaseService          databaseService;
    final InteractionEntityManager interactionEntityManager;
    final InteractionGroupManager  interactionGroupManager;
    final GroupMemberManager       groupMemberManager;
    final InteractionStreamManager interactionStreamManager;
    final BinFileManager           binFileManager;

    /// Napcat请求管理器
    final NapcatReqManager       napcatReqManager;
    /// 消息事件缓冲器
    final BarrierOrderedEvtQueue barrierOrderedEvtQueue;
    /// 二进制数据缓存
    final Cache<String, byte[]>  binDataCache;

    /// 配置项
    final AtomicReference<Config> config             = new AtomicReference<>();
    /// 心跳线程唤醒锁
    final ReentrantLock           heartbeatLock      = new ReentrantLock();
    /// 心跳线程唤醒条件
    /// 用于在连接断开时通知心跳线程退出
    final Condition               heartbeatCondition = heartbeatLock.newCondition();
    /// QQ表情映射
    final QQFace                  qqFace             = new QQFace();

    /// 元事件反序列化器
    private final MetaEventDeserializer metaEventDeserializer = new MetaEventDeserializer(this);
    /// 消息反序列化器
    private final MessageDeserializer   messageDeserializer   = new MessageDeserializer(this);
    /// Adapter运行状态
    volatile      boolean               running               = false;


    @AutoInject
    public NcProtocolDecoder(
      TaskExecuteService taskExecuteService,
      ObjectMapper objectMapper,
      DatabaseService databaseService,
      InteractionEntityManager interactionEntityManager,
      InteractionGroupManager interactionGroupManager,
      GroupMemberManager groupMemberManager,
      InteractionStreamManager interactionStreamManager,
      BinFileManager binFileManager,
      GlobalCacheManager globalCacheManager,
      NapcatReqManager napcatReqManager,
      BarrierOrderedEvtQueue barrierOrderedEvtQueue,
      @Value("${napcat-adapter:*}") Config config
    ) {
        this.taskExecuteService = taskExecuteService;
        this.objectMapper = objectMapper;
        this.databaseService = databaseService;
        this.interactionEntityManager = interactionEntityManager;
        this.interactionGroupManager = interactionGroupManager;
        this.groupMemberManager = groupMemberManager;
        this.interactionStreamManager = interactionStreamManager;
        this.binFileManager = binFileManager;
        this.napcatReqManager = napcatReqManager;
        this.barrierOrderedEvtQueue = barrierOrderedEvtQueue;


        this.barrierOrderedEvtQueue.startBuffering();

        this.binDataCache = globalCacheManager.createCache(
          "ncada_bin_data_cache",
          String.class,
          byte[].class,
          50,
          100,
          0,
          null
        );


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
        var receiveTime = System.currentTimeMillis();

        // 解析 JSON
        JsonNode jsonNode;
        try {
            jsonNode = objectMapper.readTree(msg.text());
        } catch (JacksonException e) {
            log.warn("无法解析 NapCat 事件内容: {}", msg.text(), e);
            return;
        }

        var mdcCtx = MDC.getCopyOfContextMap(); // 获取当前 MDC 上下文

        // 发起异步解析
        taskExecuteService.submit(
          () -> {
              var sNo = SNoGenerator.nextSeq();
              // 传递 MDC 上下文
              MDC.setContextMap(mdcCtx);
              MDC.put("sNo", sNo.toHexString());

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
                          barrierOrderedEvtQueue.startDeserializing(sNo, receiveTime);
                          var msgEvt = this.messageDeserializer.deserialize(ctx, sNo, jsonNode);
                          if (msgEvt == null) {
                              barrierOrderedEvtQueue.failDeserializing(sNo);
                          } else {
                              barrierOrderedEvtQueue.finishDeserializing(msgEvt);
                          }
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
              } finally {
                  MDC.clear();
              }
          }, false
        ).exceptionally(throwable -> {
            log.warn("异步反序列化 NapCat 事件时发生异常", throwable);
            return null;
        });
    }

    /**
     * 应用消息过滤器
     *
     * @param senderId   发送者ID
     * @param groupId    群ID（私聊消息可为null）
     * @param streamType 消息类型
     * @return 是否过滤该消息
     */
    boolean applyFilters(String senderId, String groupId, StreamType streamType) {
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
}
