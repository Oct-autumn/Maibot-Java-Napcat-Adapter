package org.maibot.mods.ncada.msgevt;

import lombok.Getter;
import lombok.Setter;
import org.maibot.sdk.SeqGenerator;
import org.maibot.sdk.model.msgevt.MessageMeta;

import java.util.HashMap;
import java.util.Map;

public class MessageEventFactory {
    /// 额外字段的键值对
    private final Map<String, String>       extra       = new HashMap<>();
    /// 平台标识
    @Getter
    @Setter
    private       String                  platform   = null;
    /// 发送者信息
    @Getter
    @Setter
    private       MessageMeta.EntityInfo  senderInfo = null;
    /// 消息流信息
    @Getter
    @Setter
    private       MessageMeta.StreamInfo  streamInfo  = null;
    /// 消息序列号
    @Getter
    @Setter
    private       SeqGenerator.Sequence     sequence    = null;
    /// 消息时间戳
    @Getter
    @Setter
    private       Long                      timestamp   = null;
    /// 消息内容
    @Getter
    @Setter
    private       MessageEvent.MessageSeg   message     = null;

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

    public MessageEvent build() {
        return new MessageEvent(
          platform,
          senderInfo,
          streamInfo,
          timestamp,
          sequence,
          message,
          extra
        );
    }
}
