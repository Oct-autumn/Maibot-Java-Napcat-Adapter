package org.maibot.mods.ncada.msgevt;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.NonNull;
import org.maibot.sdk.SNoGenerator;
import org.maibot.sdk.Util;
import org.maibot.sdk.storage.model.msgevt.AbstractMessageEvent;
import org.maibot.sdk.storage.model.msgevt.MessageMeta;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * 消息事件
 */
public final class MessageEvent extends AbstractMessageEvent {
    private static final String OBJECT_TYPE = MessageEvent.class.getName();
    /// 消息内容
    @JsonProperty(value = "message_seg")
    MessageSeg          messageSeg;
    /// 额外字段的键值对
    @JsonProperty(value = "extra")
    Map<String, String> extra;

    public MessageEvent(
      String platform,
      MessageMeta.EntityInfo senderInfo,
      MessageMeta.StreamInfo streamInfo,
      Long timestamp,
      SNoGenerator.SerialNo sequence,
      MessageSeg messageSeg,
      Map<String, String> extra
    ) {
        super(platform, senderInfo, streamInfo, timestamp, sequence, OBJECT_TYPE);
        this.messageSeg = messageSeg;
        this.extra = extra;
    }

    @Override
    public String toPromptString() {
        return "";
    }

    @Override
    protected String toRawContentJson(ObjectMapper objectMapper) {
        ObjectNode node = new JsonNodeFactory().objectNode();

        node.put("message_seg", objectMapper.writeValueAsString(this.messageSeg));
        node.put("extra", objectMapper.writeValueAsString(this.extra));

        return objectMapper.writeValueAsString(node);
    }

    @Override
    public String toString() {
        return String.format(
          "MessageEvent[messageMeta=%s, timestamp=%d, sequence=%s, messageSeg=%s, extra=%s]",
          this.messageMeta,
          this.timestamp,
          this.serialNo,
          this.messageSeg,
          this.extra
        );
    }

    /**
     * @param segType 消息段类型，常见类型：
     *                <ul>
     *                <li> <code>list</code>: 消息段列表
     *                <li> <code>text</code>: 文本消息段
     *                <li> <code>image</code>: 图片消息段
     *                <li> <code>emoji</code>: 表情包（本质还是图片）消息段
     *                <li> <code>voice</code>: 语音消息段
     *                <li> <code>at</code>: At消息段
     *                </ul>
     * @param segList 当 type 为 <code>list</code> 时，表示子消息段列表；<br>
     *                否则为null
     * @param strData 当 type 为 <code>text</code> 时，表示文本内容；<br>
     *                当 type 为 <code>at</code> 时，表示可读的at内容；<br>
     *                除了当 type 为 <code>list</code>、<code>image</code>、<code>emoji</code>
     *                或 <code>voice</code> 时为null，其他情况视Adapter实现而定
     * @param binData 当 type 为 <code>image</code>、<code>emoji</code> 或 <code>voice</code> 时，表示Base64编码的数据；<br>
     *                除了当 type 为 <code>list</code>、<code>text</code> 或 <code>at</code> 时为null，其他情况视Adapter实现而定
     * @param extra   当 type 为 <code>at</code> 时，包含键值对 <code>"self_mention"</code>，表示是否At自身；<br>
     *                其他情况下作为额外字段的键值对，视Adapter实现而定
     */
    public record MessageSeg(
      @JsonProperty(value = "seg_type", required = true) String segType,
      @JsonProperty(value = "seg_list") List<MessageSeg> segList,
      @JsonProperty(value = "str_data") String strData,
      @JsonProperty(value = "bin_data") byte[] binData,
      @JsonProperty(value = "extra") Map<String, String> extra
    ) {
        public static MessageSeg listSeg(List<MessageSeg> segList) {
            return new MessageSeg("list", segList, null, null, null);
        }

        public static MessageSeg forwardSeg(List<MessageSeg> segments) {
            return new MessageSeg("forward", segments, null, null, null);
        }

        public static MessageSeg textSeg(String strData) {
            return new MessageSeg("text", null, strData, null, null);
        }

        public static MessageSeg atSeg(String strData, boolean selfMention) {
            return new MessageSeg("at", null, strData, null, Map.of("self_mention", Boolean.toString(selfMention)));
        }

        public static MessageSeg imageSeg(byte[] binData) {
            return new MessageSeg("image", null, null, binData, null);
        }

        public static MessageSeg voiceSeg(byte[] binData) {
            return new MessageSeg("voice", null, null, binData, null);
        }

        public static MessageSeg emojiSeg(byte[] binData) {
            return new MessageSeg("emoji", null, null, binData, null);
        }

        @Override
        @NonNull
        public String toString() {
            var segType = this.segType;

            var strData = "";
            if (this.strData != null && !this.strData.isBlank()) {
                strData = Util.strAbbreviate(this.strData.strip(), 20, 5);
            }

            var b64Data = "";
            if (this.binData != null && this.binData.length > 0) {
                var b64DataRaw = Base64.getEncoder().encodeToString(this.binData);
                switch (segType) {
                    case "image", "emoji" -> b64Data = "data:image;base64,";
                    case "voice" -> b64Data = "data:audio;base64,";
                    default -> b64Data = "data:segType;base64,";
                }
                b64Data += Util.strAbbreviate(b64DataRaw, 20, 5);
            }

            var segList = "";
            if (this.segList != null && !this.segList.isEmpty()) {
                segList = "[" + String.join(", ", this.segList.stream().map(MessageSeg::toString).toList()) + "]";
            }

            var extData = "";
            if (this.extra != null && !this.extra.isEmpty()) {
                extData = "{" + String.join(
                  ", ",
                  this.extra.entrySet()
                            .stream()
                            .map(e -> String.format("\"%s\"=\"%s\"", e.getKey(), e.getValue()))
                            .toList()
                ) + "}";
            }

            return String.format(
              "MessageSeg[segType=%s, strData=%s, binData=%s, extData=%s, segList=%s]",
              segType,
              strData,
              b64Data,
              extData,
              segList
            );
        }
    }
}
