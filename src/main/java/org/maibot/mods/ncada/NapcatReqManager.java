package org.maibot.mods.ncada;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.maibot.sdk.ioc.AutoInject;
import org.maibot.sdk.ioc.Component;
import org.maibot.sdk.storage.GlobalCacheManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.cache.Cache;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

@Component
public class NapcatReqManager {
    private static final Logger log = LoggerFactory.getLogger(NapcatReqManager.class);

    /// 请求内容缓存
    private final Cache<String, JsonNode>                  requestCache;
    /// 消息监听器
    private final Map<String, CompletableFuture<JsonNode>> eventListeners = new ConcurrentHashMap<>();
    /// JSON 序列化器（用于安全地构造请求载荷，避免手工拼接字符串时转义出错）
    private final ObjectMapper                             objectMapper;

    @AutoInject
    private NapcatReqManager(GlobalCacheManager globalCacheManager, ObjectMapper objectMapper) {
        this.requestCache = globalCacheManager.createCacheIfAbsent(
          "ncada_request_cache",
          String.class,
          JsonNode.class,
          128,
          4,
          0,
          null
        );
        this.objectMapper = objectMapper;
    }

    /// 将 NapCat 回复消息反馈给对应的监听器
    ///
    /// @param echo 唯一标识符
    /// @param data 回复数据
    public void putResponse(String echo, JsonNode data) {
        var future = eventListeners.remove(echo);
        if (future != null) {
            future.complete(data);
        } else {
            log.warn("未找到对应的 NapCat 回复消息监听器，echo: {}", echo);
        }
    }

    /// 登记一条发送请求的回执监听（fire-and-forget）
    ///
    /// 复用 OneBot 11 的 echo 应答机制：NapCat 收到请求后总会回一条带相同 echo 的响应。
    /// 这里注册监听器消费响应（避免「未找到监听器」的告警，并在失败时记录日志），但不阻塞调用方。
    ///
    /// 由出站编码器（NcProtocolEncoder）在编码消息时调用。
    ///
    /// @param echo   请求的唯一标识符
    /// @param target 目标描述（用于日志，例如用户 QQ 号）
    public void trackSend(String echo, String target) {
        var replyFuture = new CompletableFuture<JsonNode>();
        eventListeners.put(echo, replyFuture);

        // 不等待回执：收到响应后只做结果校验并清理监听器；NapCat 一直没回时也会因超时被清理
        replyFuture.orTimeout(20, TimeUnit.SECONDS).whenComplete((reply, throwable) -> {
            eventListeners.remove(echo);

            if (throwable != null) {
                log.warn("发送消息失败，目标：{}，原因：{}", target, throwable.getMessage());
                return;
            }

            var retcodeNode = reply.get("retcode"); // 注：napcat节点命名如此，不是retCode
            var retcode = retcodeNode == null ? -1 : retcodeNode.asInt();
            if (retcode != 0) {
                var replyMessageNode = reply.get("message");
                log.warn(
                  "发送消息被 NapCat 拒绝，目标：{}，retcode：{}，message：{}",
                  target,
                  retcode,
                  replyMessageNode == null ? "" : replyMessageNode.asString()
                );
            } else {
                log.debug("消息发送成功，目标：{}", target);
            }
        });
    }

    /// 获取消息详情
    ///
    /// @param ctx   通道上下文
    /// @param msgId 消息ID
    public JsonNode getMsgDetail(ChannelHandlerContext ctx, String msgId) {
        return requestCache.invoke(
          "msgDetail-" + msgId, (entry, args) -> {
              if (entry.exists()) {
                  return entry.getValue();
              } else {
                  var requestUUID = UUID.randomUUID().toString();
                  String payload = String.format(
                    """
                    {
                        "action": "get_msg",
                        "params": {
                            "message_id": %s
                        },
                        "echo": "%s"
                    }
                    """, msgId, requestUUID
                  ).strip().replace("\n", "");

                  var replyFuture = sendRequest(ctx, payload, requestUUID);

                  try {
                      var reply = replyFuture.get(20, TimeUnit.SECONDS);
                      var data = reply.get("data");
                      entry.setValue(data);
                      return data;
                  } catch (TimeoutException e) {
                      log.warn("获取消息详情超时，消息ID：{}", msgId);
                      eventListeners.remove(requestUUID);
                  } catch (ExecutionException e) {
                      log.warn("获取消息详情请求失败，消息ID：{}", msgId, e.getCause());
                  } catch (InterruptedException e) {
                      log.warn("获取消息详情被中断，消息ID：{}", msgId);
                      eventListeners.remove(requestUUID);
                      Thread.currentThread().interrupt();
                  }
                  return null;
              }
          }
        );
    }

    /// 发送请求到 NapCat
    ///
    /// @param ctx     通道上下文
    /// @param payload 请求Payload
    private CompletableFuture<JsonNode> sendRequest(ChannelHandlerContext ctx, String payload, String requestUUID) {
        var resultFuture = new CompletableFuture<JsonNode>();
        eventListeners.put(requestUUID, resultFuture);    // 注册监听器
        var eventLoop = ctx.channel().eventLoop();
        if (eventLoop.isShuttingDown() || eventLoop.isShutdown()) {
            log.warn("事件循环已关闭，无法发送请求，载荷：{}", payload);
        } else {
            var sendFuture = new CompletableFuture<Void>(); // 用于监听发送结果
            eventLoop.execute(() -> ctx.writeAndFlush(new TextWebSocketFrame(payload))
                                       .addListener((ChannelFutureListener) channelFuture -> {
                                           if (channelFuture.isSuccess()) {
                                               sendFuture.complete(null);
                                           } else {
                                               sendFuture.completeExceptionally(channelFuture.cause());
                                           }
                                       }));
            try {
                sendFuture.get(5, TimeUnit.SECONDS);
                return resultFuture;
            } catch (InterruptedException e) {
                resultFuture.completeExceptionally(e);
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                resultFuture.completeExceptionally(e.getCause());
            } catch (TimeoutException e) {
                resultFuture.completeExceptionally(e);
            }
        }
        eventListeners.remove(requestUUID);
        return resultFuture;
    }

    public JsonNode getSelfInfo(ChannelHandlerContext ctx) {
        var requestUUID = UUID.randomUUID().toString();
        String payload = String.format(
          """
          {
              "action": "get_login_info",
              "params": {},
              "echo": "%s"
          }
          """, requestUUID
        ).stripIndent();

        log.debug("发送获取自身信息请求，载荷：\n{}", payload);

        var replyFuture = sendRequest(ctx, payload, requestUUID);

        try {
            var reply = replyFuture.get(20, TimeUnit.SECONDS);
            return reply.get("data");
        } catch (TimeoutException e) {
            log.warn("获取自身信息超时");
            eventListeners.remove(requestUUID);
        } catch (ExecutionException e) {
            log.warn("获取自身信息请求失败", e.getCause());
        } catch (InterruptedException e) {
            log.warn("获取自身信息被中断");
            eventListeners.remove(requestUUID);
            Thread.currentThread().interrupt();
        }
        return null;
    }

    public JsonNode getGroupMemberInfo(ChannelHandlerContext ctx, String groupId, String userId) {
        var requestUUID = UUID.randomUUID().toString();
        String payload = String.format(
          """
          {
              "action": "get_group_member_info",
              "params": {
                  "group_id": %s,
                  "user_id": %s,
                  "no_cache": true
              },
              "echo": "%s"
          }
          """, groupId, userId, requestUUID
        ).stripIndent();

        log.debug("获取群成员信息请求载荷：\n{}", payload);

        var replyFuture = sendRequest(ctx, payload, requestUUID);

        try {
            var reply = replyFuture.get(20, TimeUnit.SECONDS);
            return reply.get("data");
        } catch (TimeoutException e) {
            log.warn("获取群成员信息超时，群号：{}，用户ID：{}", groupId, userId);
            eventListeners.remove(requestUUID);
        } catch (ExecutionException e) {
            log.warn("获取群成员信息请求失败，群号：{}，用户ID：{}", groupId, userId, e.getCause());
        } catch (InterruptedException e) {
            log.warn("获取群成员信息被中断，群号：{}，用户ID：{}", groupId, userId);
            eventListeners.remove(requestUUID);
            Thread.currentThread().interrupt();
        }
        return null;
    }

    public JsonNode getForwardMsgDetail(ChannelHandlerContext ctx, String msgId) {
        return requestCache.invoke(
          "forwardMsgDetail-" + msgId, (entry, args) -> {
              if (entry.exists()) {
                  return entry.getValue();
              } else {
                  var requestUUID = UUID.randomUUID().toString();
                  String payload = String.format(
                    """
                    {
                        "action": "get_forward_msg",
                        "params": {
                            "message_id": "%s"
                        },
                        "echo": "%s"
                    }
                    """, msgId, requestUUID
                  ).stripIndent();

                  log.debug("获取合并消息详情请求载荷：\n{}", payload);

                  var replyFuture = sendRequest(ctx, payload, requestUUID);

                  try {
                      var reply = replyFuture.get(20, TimeUnit.SECONDS);
                      var data = reply.get("data");
                      entry.setValue(data);
                      return data;
                  } catch (TimeoutException e) {
                      log.warn("获取合并消息详情超时，消息ID：{}", msgId);
                      eventListeners.remove(requestUUID);
                  } catch (ExecutionException e) {
                      log.warn("获取合并消息详情请求失败，消息ID：{}", msgId, e.getCause());
                  } catch (InterruptedException e) {
                      log.warn("获取合并消息详情被中断，消息ID：{}", msgId);
                      eventListeners.remove(requestUUID);
                      Thread.currentThread().interrupt();
                  }
                  return null;
              }
          }
        );
    }
}
