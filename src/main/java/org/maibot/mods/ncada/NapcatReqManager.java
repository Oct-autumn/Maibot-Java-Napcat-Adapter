package org.maibot.mods.ncada;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.maibot.sdk.ioc.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

@Component
public class NapcatReqManager {
    private static final Logger log = LoggerFactory.getLogger(NapcatReqManager.class);

    /// 消息监听器
    private final Map<String, CompletableFuture<JsonNode>> eventListeners = new ConcurrentHashMap<>();

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

    /// 获取消息详情
    ///
    /// @param ctx   通道上下文
    /// @param msgId 消息ID
    public JsonNode getMsgDetail(ChannelHandlerContext ctx, String msgId) {
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
        ).stripIndent().replace("\n", "");

        var replyFuture = sendRequest(ctx, payload, requestUUID);

        try {
            var reply = replyFuture.get(20, TimeUnit.SECONDS);
            return reply.get("data");
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
        ).stripIndent().replace("\n", "");

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
        ).stripIndent().replace("\n", "");

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
        var requestUUID = UUID.randomUUID().toString();
        String payload = String.format(
          """
          {
              "action": "get_forward_msg",
              "params": {
                  "message_id": %s
              },
              "echo": "%s"
          }
          """, msgId, requestUUID
        ).stripIndent().replace("\n", "");

        var replyFuture = sendRequest(ctx, payload, requestUUID);

        try {
            var reply = replyFuture.get(20, TimeUnit.SECONDS);
            return reply.get("data");
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
