// 本文件使用Kotlin
// 主要考虑到Kotlin在字符串操作和Elvis表达式等方面的优势，能够使代码更简洁易读

package org.maibot.mods.ncada.msgevt

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.persistence.EntityManager
import org.maibot.sdk.SNoGenerator.SerialNo
import org.maibot.sdk.ioc.IOC
import org.maibot.sdk.storage.model.msgevt.AbstractMessageEvent
import org.maibot.sdk.storage.model.msgevt.MessageMeta.EntityInfo
import org.maibot.sdk.storage.model.msgevt.MessageMeta.StreamInfo
import org.maibot.sdk.util.MapperUtils
import org.maibot.sdk.util.StrUtils.strAbbreviate
import tools.jackson.databind.node.JsonNodeFactory

/**
 * 消息事件
 */
class MessageEvent(
    platform: String,
    senderInfo: EntityInfo,
    streamInfo: StreamInfo,
    timestamp: Long,
    sequence: SerialNo,
    /** 消息内容 */
    @field:JsonProperty(value = "message_seg") val messageSeg: MessageSeg,
    /** 额外字段的键值对 */
    @field:JsonProperty(value = "extra") val extra: Map<String, String>
) : AbstractMessageEvent(platform, senderInfo, streamInfo, timestamp, sequence, OBJECT_TYPE) {
    private var promptStr: String? = null

    /**
     * toPromptString只负责将消息内容转换为可读的Prompt。
     * 不负责格式化时间戳、消息发送者等消息元信息相关的内容。
     *
     */
    @Synchronized
    override fun toPromptString(
        em: EntityManager,
        ioc: IOC
    ): String? {
        if (promptStr != null) {
            // 如果已经渲染过一次了，就直接返回缓存的结果，避免重复渲染
            return promptStr
        } else {
            if (messageSeg.segType != "list") {
                // 最外层消息段应为 list 类型
                // 如果不是 list 类型，则无法正确渲染消息内容，返回null
                return null
            }

            val render = ioc.get(MessagePromptRender::class.java)
            val renderedStr = render.renderListSeg(em, messageSeg.segList!!)
            promptStr = renderedStr

            return renderedStr
        }
    }

    override fun toRawContentJson(): String {
        val jsonMapper = MapperUtils.jsonMapper
        val node = JsonNodeFactory().objectNode().apply {
            put("message_seg", jsonMapper.writeValueAsString(messageSeg))
            put("extra", jsonMapper.writeValueAsString(extra))
        }

        return jsonMapper.writeValueAsString(node)
    }

    override fun toString(): String {
        return "MessageEvent[messageMeta=${this.messageMeta}, timestamp=${this.timestamp}, sequence=${this.serialNo}, messageSeg=${this.messageSeg}, extra=${this.extra}]"
    }

    /**
     * @param segType   消息段类型，常见类型：
     *
     *  *  `list`: 消息段列表
     *  *  `text`: 文本消息段
     *  *  `image`: 图片消息段
     *  *  `emoji`: 表情包（本质还是图片）消息段
     *  *  `voice`: 语音消息段
     *  *  `at`: At消息段
     *
     * @param segList   当 type 为 `list` 时，表示子消息段列表；<br></br>
     * 否则为null
     * @param strData   当 type 为 `text` 时，表示文本内容；<br></br>
     * 当 type 为 `at` 时，表示可读的at内容；<br></br>
     * 除了当 type 为 `list`、`image`、`emoji`
     * 或 `voice` 时为null，其他情况视Adapter实现而定
     * @param binFileId 当 type 为 `image`、`emoji` 或 `voice` 时，表示二进制文件ID；<br></br>
     * 除了当 type 为 `list`、`text` 或 `at` 时为null，其他情况视Adapter实现而定
     * @param extra     当 type 为 `at` 时，包含键值对 `"self_mention"`，表示是否At自身；<br></br>
     * 其他情况下作为额外字段的键值对，视Adapter实现而定
     */
    @JvmRecord
    data class MessageSeg(
        @field:JsonProperty("seg_type", required = true) val segType: String,
        @field:JsonProperty("seg_list") val segList: List<MessageSeg>? = null,
        @field:JsonProperty("str_data") val strData: String? = null,
        @field:JsonProperty("bin_file_id") val binFileId: Long? = null,
        @field:JsonProperty("extra") val extra: Map<String, String>? = null
    ) {
        override fun toString(): String {
            val segType = this.segType

            val strData = this.strData?.let {
                strAbbreviate(it.trim(), 20, 5)
            } ?: ""

            val b64Data = this.binFileId?.let {
                when (segType) {
                    "image", "emoji" -> "data:image;id="
                    "voice" -> "data:audio;id="
                    else -> "data:$segType;id="
                } + it
            } ?: ""

            val segList = this.segList?.let {
                if (this.segList.isNotEmpty()) "[${this.segList.joinToString(", ") { it.toString() }}]"
                else null
            } ?: ""

            val extData = this.extra?.let {
                if (this.extra.isNotEmpty())
                    "{${this.extra.entries.joinToString(", ") { "\"${it.key}\"=\"${it.value}\"" }}}"
                else null
            } ?: ""

            return "MessageSeg[segType=$segType, strData=$strData, binData=$b64Data, extData=$extData, segList=$segList]"
        }

        companion object {
            @JvmStatic
            fun listSeg(segList: MutableList<MessageSeg>): MessageSeg {
                return MessageSeg("list", segList, null, null, null)
            }

            @JvmStatic
            fun forwardSeg(segments: MutableList<MessageSeg>): MessageSeg {
                return MessageSeg("forward", segments, null, null, null)
            }

            @JvmStatic
            fun textSeg(strData: String): MessageSeg {
                return MessageSeg("text", null, strData, null, null)
            }

            @JvmStatic
            fun atSeg(strData: String?, selfMention: Boolean): MessageSeg {
                return MessageSeg(
                    "at",
                    null,
                    strData,
                    null,
                    mapOf(Pair("self_mention", selfMention.toString()))
                )
            }

            @JvmStatic
            fun imageSeg(binFileId: Long): MessageSeg {
                return MessageSeg("image", null, null, binFileId, null)
            }

            @JvmStatic
            fun voiceSeg(binFileId: Long): MessageSeg {
                return MessageSeg("voice", null, null, binFileId, null)
            }

            @JvmStatic
            fun emojiSeg(binFileId: Long): MessageSeg {
                return MessageSeg("emoji", null, null, binFileId, null)
            }
        }
    }

    companion object {
        private val OBJECT_TYPE: String = MessageEvent::class.java.getName()
    }
}
