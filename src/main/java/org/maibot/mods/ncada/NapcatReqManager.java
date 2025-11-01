package org.maibot.mods.ncada;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
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
    private boolean sendRequest(ChannelHandlerContext ctx, String payload) {
        var eventLoop = ctx.channel().eventLoop();
        if (eventLoop.isShuttingDown() || eventLoop.isShutdown()) {
            log.warn("事件循环已关闭，无法发送请求，负载：{}", payload);
        } else {
            var future = new CompletableFuture<Void>();
            eventLoop.execute(() -> ctx.writeAndFlush(payload).addListener((ChannelFutureListener) channelFuture -> {
                if (channelFuture.isSuccess()) {
                    future.complete(null);
                } else {
                    future.completeExceptionally(channelFuture.cause());
                }
            }));
            try {
                future.get(5, TimeUnit.SECONDS);
                return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("发送请求被中断，负载：{}", payload);
            } catch (ExecutionException e) {
                log.warn("发送请求失败，负载：{}，错误：{}", payload, e.getCause().getMessage());
            } catch (TimeoutException e) {
                log.warn("发送请求超时，负载：{}", payload);
            }
        }
        return false;
    }

    /// 获取消息详情
    ///
    /// @param ctx   通道上下文
    /// @param msgId 消息ID
    public JsonNode getMsgDetail(ChannelHandlerContext ctx, String msgId) {
        var requestUUID = UUID.randomUUID().toString();
        var future = new CompletableFuture<JsonNode>();
        String payload = String.format(
          """
          {
              "action": "get_msg",
              "params": {
                  "message_id": %s
              },
              "echo": %s
          }
          """, msgId, requestUUID
        ).stripTrailing();

        eventListeners.put(requestUUID, future);

        try {
            if (!sendRequest(ctx, payload)) {
                eventListeners.remove(requestUUID);
                return null;
            }
            return future.get(20, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("获取消息详情超时，消息ID：{}", msgId);
            eventListeners.remove(requestUUID);
        } catch (ExecutionException ignored) {
            // 忽略执行异常，这不会发生
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("获取消息详情被中断，消息ID：{}", msgId);
        }
        return null;
    }
}
