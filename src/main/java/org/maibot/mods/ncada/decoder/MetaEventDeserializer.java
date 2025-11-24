package org.maibot.mods.ncada.decoder;

import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.util.concurrent.TimeUnit;

class MetaEventDeserializer {
    private final static Logger log = LoggerFactory.getLogger(MetaEventDeserializer.class);

    NcProtocolDecoder ncProtocolDecoder;

    MetaEventDeserializer(NcProtocolDecoder ncProtocolDecoder) {
        this.ncProtocolDecoder = ncProtocolDecoder;
    }

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
            ncProtocolDecoder.heartbeatLock.lock();
            // 在超时前唤醒心跳监测线程
            ncProtocolDecoder.heartbeatCondition.signalAll();
        } finally {
            ncProtocolDecoder.heartbeatLock.unlock();
        }
    }

    private void heartbeatWatchdog(ChannelHandlerContext ctx) {
        log.trace("NapCat心跳监测线程已启动");
        while (ncProtocolDecoder.running) {
            try {
                ncProtocolDecoder.heartbeatLock.lock();
                if (!ncProtocolDecoder.heartbeatCondition.await(60, TimeUnit.SECONDS)) {
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
                ncProtocolDecoder.heartbeatLock.unlock();
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

            if (!ncProtocolDecoder.running) {
                ncProtocolDecoder.running = true;
                // 启动心跳监测线程
                ncProtocolDecoder.taskExecuteService.submit(() -> heartbeatWatchdog(ctx), true);
            }
        }
    }
}
