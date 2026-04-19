package org.maibot.mods.ncada.msgevt

import jakarta.persistence.EntityManager
import org.maibot.mods.ncada.msgevt.MessageEvent.MessageSeg
import org.maibot.sdk.ioc.AutoInject
import org.maibot.sdk.ioc.Component
import org.maibot.sdk.manager.BinFileManager
import org.maibot.sdk.manager.ImageDescribeManager

@Component
class MessagePromptRender
@AutoInject
private constructor(
    private val binFileManager: BinFileManager,
    private val imageDescribeManager: ImageDescribeManager
) {
    fun renderListSeg(em: EntityManager, segList: List<MessageSeg>): String {
        return mutableListOf<String>().apply {
            segList.forEach { seg ->
                when (seg.segType) {
                    "text" -> add(seg.strData!!)   // 文本消息段直接追加文本内容
                    "at" -> {
                        if (seg.extra?.get("self_mention") == "true") add("[${seg.strData!!.trim()}(你)]")  // At消息段如果是At自己，追加特殊标识
                        else add("[${seg.strData!!.trim()}]")
                    }

                    "image" -> {
                        val binFileWithData = binFileManager.get(em, seg.binFileId!!) ?: run {
                            add("[图片 加载失败]")  // 如果图片数据缺失，添加加载失败提示，并跳过后续描述生成
                            return@forEach
                        }

                        val imageDesc = imageDescribeManager.getOrCreatIfAbsent(em, binFileWithData, false) ?: run {
                            add("[图片 加载失败]")  // 如果图像描述生成失败，添加提示，并跳过后续描述生成
                            return@forEach
                        }

                        add("[图片: ${imageDesc.description}]")  // 图片消息段追加描述文本
                    }

                    "emoji" -> {
                        val binFileWithData = binFileManager.get(em, seg.binFileId!!) ?: run {
                            add("[表情包 加载失败]")  // 如果表情数据缺失，添加加载失败提示，并跳过后续描述生成
                            return@forEach
                        }

                        val imageDesc = imageDescribeManager.getOrCreatIfAbsent(em, binFileWithData, true) ?: run {
                            add("[表情包 加载失败]")  // 如果表情描述生成失败，添加提示，并跳过后续描述生成
                            return@forEach
                        }

                        add("[表情包: ${imageDesc.description}]")  // 表情消息段追加描述文本
                    }

                    "voice" -> add("[一段语音]")  // 语音消息段添加固定提示文本 TODO: 后续可以考虑添加语音转文本的描述
                    "forward" -> {
                        // 转发消息段，递归渲染子消息段列表
                        val subSegList = seg.segList ?: run {
                            add("[转发消息 加载失败]")  // 如果转发消息的子消息段列表缺失，添加加载失败提示，并跳过后续渲染
                            return@forEach
                        }

                        add(renderListSeg(em, subSegList))  // 转发消息段追加渲染后的子消息内容
                    }

                    else -> add("[未知消息类型]")
                }
            }
        }.joinToString(" ") // 最后将所有消息段内容连接成一个字符串
    }
}
