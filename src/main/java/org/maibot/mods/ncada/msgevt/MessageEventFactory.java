package org.maibot.mods.ncada.msgevt;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.jetbrains.annotations.NotNull;
import org.maibot.sdk.ioc.ObjectFactory;
import org.maibot.sdk.ioc.Specify;
import org.maibot.sdk.storage.model.msgevt.AbstractMessageEventFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.HashMap;
import java.util.Map;

@ObjectFactory
@Specify("org.maibot.mods.ncada.msgevt.MessageEvent")
public class MessageEventFactory extends AbstractMessageEventFactory {
    /// 额外字段的键值对
    private final Map<String, String>     extra   = new HashMap<>();
    /// 消息内容
    @Getter
    @Setter
    @Accessors(chain = true)
    private       MessageEvent.MessageSeg message = null;

    public boolean hasExtra(String key) {
        return this.extra.containsKey(key);
    }

    public void putExtra(String key, String value) {
        this.extra.put(key, value);
    }

    public String getExtra(String key) {
        return this.extra.get(key);
    }

    public void removeExtra(String key) {
        this.extra.remove(key);
    }

    public MessageEventFactory() {
        super(MessageEvent.class.getName());
    }

    @NotNull
    @Override
    protected AbstractMessageEventFactory fromRawContentJson(ObjectMapper objectMapper, @NotNull String jsonString) {
        var rawContentNode = objectMapper.readTree(jsonString);

        this.message = objectMapper.treeToValue(rawContentNode.get("message_seg"), MessageEvent.MessageSeg.class);

        var extraNode = rawContentNode.get("extra");
        if (extraNode instanceof ObjectNode objectNode) {
            objectNode.forEachEntry((field, node) -> {
                if (node.isString()) {
                    this.extra.put(field, node.asString());
                } // 非字符串类型的额外字段将被忽略
            });
        }

        return this;
    }

    @NotNull
    @Override
    public MessageEvent build() {
        var messageMeta = getMessageMeta();
        assert messageMeta != null;
        return new MessageEvent(
          messageMeta.platform(),
          messageMeta.senderInfo(),
          messageMeta.streamInfo(),
          getTimestamp(),
          getSequence(),
          message,
          extra
        );
    }
}
