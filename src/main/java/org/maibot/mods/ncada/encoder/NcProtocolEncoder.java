package org.maibot.mods.ncada.encoder;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.maibot.mods.ncada.NapcatReqManager;
import org.maibot.sdk.ioc.AutoInject;
import org.maibot.sdk.msg.OutgoingMessage;
import org.maibot.sdk.msg.OutgoingSegment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

/// NapCat 协议编码器
///
/// 位于 WS 连接管线的出站方向：把 Core 侧写入的通用消息实例（{@link OutgoingMessage}）
/// 编码成 OneBot 11 的请求帧（目前支持 `send_private_msg`），交由管线发往 NapCat。
///
/// 这样 Core 不需要认识 QQ / OneBot 协议，具体编码由 Adapter 在管线中完成。
public class NcProtocolEncoder extends MessageToMessageEncoder<OutgoingMessage> {
    private static final Logger log = LoggerFactory.getLogger(NcProtocolEncoder.class);

    private final ObjectMapper     objectMapper;
    private final NapcatReqManager napcatReqManager;

    @AutoInject
    public NcProtocolEncoder(ObjectMapper objectMapper, NapcatReqManager napcatReqManager) {
        this.objectMapper = objectMapper;
        this.napcatReqManager = napcatReqManager;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, OutgoingMessage msg, List<Object> out)
    throws Exception {
        // M1 只支持私聊，群聊等目标类型暂不处理
        if (msg.isGroup()) {
            log.warn("暂不支持群聊消息发送（平台: {}，目标: {}）", msg.platform(), msg.platformTargetId());
            return;
        }

        var echo = UUID.randomUUID().toString();

        // 用消息段数组而不是字符串：字符串会被 NapCat 当作 CQ 码解析，文本里带方括号等内容会被误处理
        var payloadNode = this.objectMapper.createObjectNode();
        payloadNode.put("action", "send_private_msg");
        var paramsNode = payloadNode.putObject("params");
        paramsNode.put("user_id", msg.platformTargetId());
        var messageNode = paramsNode.putArray("message");

        for (var seg : msg.segments()) {
            if (!OutgoingSegment.TYPE_TEXT.equals(seg.type())) {
                log.warn("暂不支持的消息段类型，已忽略: {}", seg.type());
                continue;
            }
            var segNode = messageNode.addObject();
            segNode.put("type", "text");
            segNode.putObject("data").put("text", seg.text() == null ? "" : seg.text());
        }

        if (messageNode.size() == 0) {
            log.warn("消息段全部无法编码，跳过发送（目标: {}）", msg.platformTargetId());
            return;
        }

        payloadNode.put("echo", echo);

        // 注册回执监听：只做结果校验与日志，不阻塞发送
        this.napcatReqManager.trackSend(echo, msg.platformTargetId());

        out.add(new TextWebSocketFrame(this.objectMapper.writeValueAsString(payloadNode)));
        log.debug("已编码出站消息（目标: {}，段数: {}）", msg.platformTargetId(), msg.segments().size());
    }
}
