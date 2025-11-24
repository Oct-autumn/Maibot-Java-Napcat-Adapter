package org.maibot.mods.ncada.decoder;

import io.netty.channel.ChannelHandlerContext;
import org.maibot.mods.ncada.Utils;
import org.maibot.mods.ncada.msgevt.MessageEvent;
import org.maibot.mods.ncada.msgevt.MessageEventFactory;
import org.maibot.sdk.SNoGenerator;
import org.maibot.sdk.storage.domain.StreamType;
import org.maibot.sdk.storage.model.msgevt.MessageMeta;
import org.maibot.sdk.storage.model.msgevt.MessageMetaFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.maibot.mods.ncada.NapcatAdapterMod.PLATFORM_NAME;

class MessageDeserializer {
    private final static Logger log = LoggerFactory.getLogger(MessageDeserializer.class);

    private final NcProtocolDecoder ncProtocolDecoder;


    public MessageDeserializer(NcProtocolDecoder ncProtocolDecoder) {
        this.ncProtocolDecoder = ncProtocolDecoder;
    }

    private static String getDisplayName(JsonNode memberInfo) {
        String displayName;
        // 优先使用群名片/备注名
        if (memberInfo.has("card") && !memberInfo.get("card").asString().isBlank()) {
            displayName = memberInfo.get("card").asString();
        } else if (memberInfo.has("nickname") && !memberInfo.get("nickname").asString().isBlank()) {
            displayName = memberInfo.get("nickname").asString();
        } else {
            displayName = "未知用户";
        }
        return displayName;
    }

    public MessageEvent deserialize(ChannelHandlerContext ctx, SNoGenerator.SerialNo sNo, JsonNode rawJsonNode) {
        var msgEventFactory = new MessageEventFactory();

        // 消息ID（可用于识别，但进入数据库时不能用来作为主键索引，原因见NapCat实现）
        msgEventFactory.putExtra("message_id", rawJsonNode.get("message_id").asString());
        // 原始消息
        //msgEventFactory.putExtra("raw_message", jsonNode.get("raw_message").asString());
        // 消息时间戳（秒级，由NapCat生成）
        msgEventFactory.setTimestamp(rawJsonNode.get("time").asLong() * 1000);
        // 消息序列号
        msgEventFactory.setSequence(sNo);

        // 消息类型（私聊/群聊）
        var messageType = rawJsonNode.get("message_type").asString();
        var subTypeObj = rawJsonNode.get("sub_type");

        if (subTypeObj == null) {
            log.warn("Private message missing sub_type");
            return null;
        }
        var subType = subTypeObj.asString();

        // 提取消息来源信息（发送者、群组等）
        var streamInfo = extractStreamInfo(rawJsonNode, messageType, subType);
        if (streamInfo == null) {
            return null;
        } else {
            // 生成事件元信息
            msgEventFactory.setMessageMeta(new MessageMetaFactory().setPlatform(PLATFORM_NAME)
                                                                   .setSenderInfo(streamInfo.privateInfo())
                                                                   .setStreamInfo(streamInfo)
                                                                   .build());
        }

        // 消息内容
        msgEventFactory.setMessage(Objects.requireNonNullElseGet(
          parseMessageContent(
            ctx,
            rawJsonNode.get("message"),
            false,
            msgEventFactory.getMessageMeta()
                           .streamInfo()
          ),
          () -> MessageEvent.MessageSeg.listSeg(List.of(MessageEvent.MessageSeg.textSeg("无法解析的消息内容")))
        ));

        var msgEvent = msgEventFactory.build();
        log.debug(msgEvent.toString());
        return msgEvent;
    }

    private void extractText(JsonNode msgData, List<MessageEvent.MessageSeg> messageSegments) {
        messageSegments.add(MessageEvent.MessageSeg.textSeg(msgData.get("text").asString()));
    }

    private void extractQQFace(JsonNode msgData, List<MessageEvent.MessageSeg> messageSegments) {
        var faceId = msgData.get("id").asString();
        var mappedFace = ncProtocolDecoder.qqFace.getMapping(faceId);

        if (mappedFace == null) {
            log.warn("未知的 QQ 表情 ID: {}", faceId);
            mappedFace = "[未知表情]";
        }

        messageSegments.add(MessageEvent.MessageSeg.textSeg(mappedFace));
    }

    private void extractReply(
      ChannelHandlerContext ctx,
      boolean inReply,
      JsonNode msgData,
      List<MessageEvent.MessageSeg> messageSegments,
      MessageMeta.StreamInfo streamInfo
    ) {
        if (inReply) {
            log.debug("嵌套的回复消息被忽略");
        } else {
            var msgId = msgData.get("id").asString();
            var replyDetailJsonNode = ncProtocolDecoder.napcatReqManager.getMsgDetail(ctx, msgId);

            if (replyDetailJsonNode == null) {
                messageSegments.add(MessageEvent.MessageSeg.textSeg("[无法定位被回复消息]"));
            } else {
                var senderInfoJsonNode = replyDetailJsonNode.get("sender");

                String displayName = getDisplayName(senderInfoJsonNode);

                String displayId;
                if (senderInfoJsonNode.get("user_id") != null && !senderInfoJsonNode.get("user_id")
                                                                                    .asString()
                                                                                    .isBlank()) {
                    displayId = senderInfoJsonNode.get("user_id").asString();
                } else {
                    displayId = "未知ID";
                }

                messageSegments.add(MessageEvent.MessageSeg.textSeg(String.format(
                  "回复 [<%s:%s>：",
                  displayName,
                  displayId
                )));

                messageSegments.add(Objects.requireNonNullElseGet(
                  parseMessageContent(
                    ctx,
                    replyDetailJsonNode.get("message"),
                    true,
                    streamInfo
                  ),
                  () -> MessageEvent.MessageSeg.textSeg("无法解析的回复内容")
                ));

                messageSegments.add(MessageEvent.MessageSeg.textSeg("] 说："));
            }

        }
    }

    private void extractImage(JsonNode msgData, List<MessageEvent.MessageSeg> messageSegments) {
        var imageSubTypeJsonNode = msgData.get("sub_type");
        var imageSummaryJsonNode = msgData.get("summary");

        var imageSummary = imageSummaryJsonNode == null ? "" : imageSummaryJsonNode.asString();

        boolean isEmoji;

        if (imageSubTypeJsonNode != null) {
            var imageSubType = imageSubTypeJsonNode.asInt();
            switch (imageSubType) {
                case 0 -> // 普通图片
                  isEmoji = false;
                case 1, 2, 3, 7, 8, 10, 13 -> // 视为表情包
                  isEmoji = true;
                case 4, 9 -> {
                    log.debug("暂不支持的图片子类型: {}", imageSubType);
                    return;
                }
                default -> {
                    log.warn("未知的图片子类型: {}", imageSubType);
                    return;
                }
            }
        } else {
            isEmoji = msgData.has("emoji_id") && msgData.has("emoji_package_id");
        }

        var fId = ncProtocolDecoder.databaseService.exec(em -> {
            var url = msgData.get("url").asString();
            // 截取文件类型
            String fileName = msgData.get("file").asString();
            var fileType = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
            var result = ncProtocolDecoder.binFileManager.get(em, url);
            if (result != null) {
                // 文件记录存在
                if (result.getData() != null) {
                    // 数据存在
                    return result.getBinFile().getId();
                } else {
                    // 数据丢失，重新下载
                    var fetchedData = Utils.getImgData(url);
                    if (fetchedData != null) {
                        var updatedBinFile = result.getBinFile();
                        result = ncProtocolDecoder.binFileManager.update(em, updatedBinFile, fetchedData);
                        if (result != null) {
                            return result.getBinFile().getId();
                        }
                    }
                }
            } else {
                // 文件记录不存在，重新下载
                var fetchedData = Utils.getImgData(msgData.get("url").asString());
                if (fetchedData != null) {
                    result = ncProtocolDecoder.binFileManager.getOrCreatIfAbsent(em, url, fileType, fetchedData);
                    if (result != null) {
                        return result.getBinFile().getId();
                    } else {
                        log.warn("无法创建图片文件记录，URL: {}", msgData.get("url").asString());
                    }
                }
            }
            return null;
        });

        if (fId == null) {
            var defaultPrompt = isEmoji ? "[未知表情包]" : "[未知图片]";
            messageSegments.add(MessageEvent.MessageSeg.textSeg(imageSummary.isBlank() ? defaultPrompt : imageSummary));
        } else {
            if (isEmoji) {
                messageSegments.add(MessageEvent.MessageSeg.emojiSeg(fId));
            } else {
                messageSegments.add(MessageEvent.MessageSeg.imageSeg(fId));
            }
        }
    }

    private void extractAt(
      ChannelHandlerContext ctx,
      JsonNode msgData,
      List<MessageEvent.MessageSeg> messageSegments,
      MessageMeta.StreamInfo streamInfo
    ) {
        var targetId = msgData.get("qq").asString();

        if (targetId.equals(ncProtocolDecoder.config.get().qqAccount())) {
            // At 了机器人自己
            var selfInfo = ncProtocolDecoder.napcatReqManager.getSelfInfo(ctx);
            if (selfInfo == null) {
                messageSegments.add(MessageEvent.MessageSeg.atSeg(
                  String.format(
                    "@<%s:%s> ",
                    ncProtocolDecoder.config.get().nickname(),
                    ncProtocolDecoder.config.get().qqAccount()
                  ),
                  true
                ));
            } else {
                messageSegments.add(MessageEvent.MessageSeg.atSeg(
                  String.format(
                    "@<%s:%s> ",
                    selfInfo.get("nickname").asString(),
                    selfInfo.get("user_id").asString()
                  ),
                  true
                ));
            }
        } else {
            // At 了其他人 （一定是在群聊中，私聊没有 At）
            var memberInfo = ncProtocolDecoder.napcatReqManager.getGroupMemberInfo(
              ctx,
              streamInfo.groupInfo().platformId(),
              targetId
            );
            if (memberInfo == null) {
                messageSegments.add(MessageEvent.MessageSeg.atSeg(String.format("@<未知用户:%s> ", targetId), false));
            } else {
                String displayName = getDisplayName(memberInfo);

                messageSegments.add(MessageEvent.MessageSeg.atSeg(
                  String.format("@<%s:%s>", displayName, targetId),
                  false
                ));
            }
        }
    }

    private void extractRps(JsonNode msgData, List<MessageEvent.MessageSeg> messageSegments) {
        var result = msgData.get("result").asString();

        switch (result) {
            case "1" -> messageSegments.add(MessageEvent.MessageSeg.textSeg("[石头剪刀布: 布]"));
            case "2" -> messageSegments.add(MessageEvent.MessageSeg.textSeg("[石头剪刀布: 剪刀]"));
            case "3" -> messageSegments.add(MessageEvent.MessageSeg.textSeg("[石头剪刀布: 石头]"));
            default -> log.warn("未知的石头剪刀布结果: {}", result);
        }
    }

    private void extractDice(JsonNode msgData, List<MessageEvent.MessageSeg> messageSegments) {
        var result = msgData.get("result").asString();
        messageSegments.add(MessageEvent.MessageSeg.textSeg(String.format("[掷骰子: %s]", result)));
    }

    private MessageEvent.MessageSeg parseMessageContent(
      ChannelHandlerContext ctx,
      JsonNode messageJson,
      boolean inReply,
      MessageMeta.StreamInfo streamInfo
    ) {
        // 解析消息内容为 MessageSeg 对象
        if (messageJson == null) {
            log.warn("消息内容为空");
            return null;
        } else if (!messageJson.isArray()) {
            log.warn("消息内容格式错误，预期为数组");
            return null;
        }

        List<MessageEvent.MessageSeg> messageSegments = new ArrayList<>(messageJson.size());

        for (JsonNode subMsg : messageJson.values()) {
            var msgData = subMsg.get("data");
            switch (subMsg.get("type").asString()) {
                case "text" -> // 纯文本消息
                  extractText(msgData, messageSegments);
                case "face" -> // QQ表情消息
                  extractQQFace(msgData, messageSegments);
                case "reply" -> // 回复消息
                  extractReply(ctx, inReply, msgData, messageSegments, streamInfo);
                case "image" -> // 图片消息
                  extractImage(msgData, messageSegments);
                case "record" -> {
                    messageSegments.add(MessageEvent.MessageSeg.textSeg("[语音]"));
                }
                case "video" -> {
                    messageSegments.add(MessageEvent.MessageSeg.textSeg("[视频]"));
                }
                case "at" -> // @消息
                  extractAt(ctx, msgData, messageSegments, streamInfo);
                case "rps" -> // 石头剪刀布
                  extractRps(msgData, messageSegments);
                case "dice" -> // 掷骰子
                  extractDice(msgData, messageSegments);
                case "shake" -> {
                    messageSegments.add(MessageEvent.MessageSeg.textSeg("[窗口抖动]"));
                }
                case "share" -> {
                    // 分享消息
                    messageSegments.add(MessageEvent.MessageSeg.textSeg("[分享]"));
                }
                case "forward" -> // 转发消息
                  new ForwardMessageExtractor().extractForward(ctx, msgData, messageSegments);
                case "node" -> {
                    // 转发消息节点
                    messageSegments.add(MessageEvent.MessageSeg.textSeg("[转发消息节点]"));
                }
                default -> log.warn("未知的消息子类型: {}", subMsg.get("type").asString());
            }
        }

        if (messageSegments.isEmpty()) {
            log.warn("消息内容解析后为空");
            return null;
        } else {
            return MessageEvent.MessageSeg.listSeg(messageSegments);
        }
    }

    private MessageMeta.StreamInfo extractStreamInfo(JsonNode rawJsonNode, String messageType, String subType) {
        switch (messageType) {
            case "private" -> {
                switch (subType) {
                    case "friend" -> {
                        // 处理好友私聊消息
                        if (!ncProtocolDecoder.applyFilters(
                          rawJsonNode.get("sender").get("user_id").asString(),
                          null,
                          StreamType.PRIVATE
                        )) {
                            var senderInfoJson = rawJsonNode.get("sender");
                            if (doPrivateStreamPersistence(senderInfoJson)) {
                                return new MessageMeta.StreamInfo(
                                  new MessageMeta.EntityInfo(
                                    senderInfoJson.get("user_id").asString(),
                                    senderInfoJson.get("nickname").asString(),
                                    senderInfoJson.get("card").asString()
                                  ), null, StreamType.PRIVATE
                                );
                            }
                        }
                        return null;
                    }
                    case "group" -> {
                        // 处理群内私聊消息（临时会话）
                        // 暂不做支持
                        //msgEventFactory.setMessageType(MessageEvent.MessageType.GROUP_TEMP);

                        return null;
                    }
                    default -> log.warn("Unknown private message sub_type: {}", subType);
                }
            }
            case "group" -> {
                switch (subType) {
                    case "normal" -> {
                        if (!ncProtocolDecoder.applyFilters(
                          rawJsonNode.get("sender").get("user_id").asString(),
                          rawJsonNode.get("group_id").asString(),
                          StreamType.GROUP
                        )) {// 处理普通群消息
                            var senderInfoJson = rawJsonNode.get("sender");

                            if (doGroupStreamPersistence(rawJsonNode)) {
                                return new MessageMeta.StreamInfo(
                                  new MessageMeta.EntityInfo(
                                    senderInfoJson.get("user_id").asString(),
                                    senderInfoJson.get("nickname").asString(),
                                    senderInfoJson.get("card").asString()
                                  ),
                                  new MessageMeta.GroupInfo(
                                    rawJsonNode.get("group_id").asString(),
                                    rawJsonNode.get("group_name").asString()
                                  ),
                                  StreamType.GROUP
                                );
                            }
                        }
                        return null;
                    }
                    case "anonymous" -> {
                        // 处理匿名群消息（弃用）
                        return null;
                    }
                    default -> log.warn("Unknown group message sub_type: {}", subType);
                }
            }
        }
        return null;
    }

    private Boolean doPrivateStreamPersistence(JsonNode rawJsonNode) {
        var senderInfoJson = rawJsonNode.get("sender");
        return ncProtocolDecoder.databaseService.exec(em -> {
            var interactionEntity = ncProtocolDecoder.interactionEntityManager.getOrCreatIfAbsent(
              em,
              PLATFORM_NAME,
              senderInfoJson.get("user_id")
                            .asString(),
              senderInfoJson.get("nickname")
                            .asString()
            );

            if (interactionEntity == null) {
                log.warn(
                  "无法获取或创建交互实体，平台: {}, 用户ID: {}",
                  PLATFORM_NAME,
                  senderInfoJson.get("user_id").asString()
                );
                return false;
            } else {
                // 更新昵称信息
                var currentNickname = senderInfoJson.get("nickname").asString();
                if (interactionEntity.getNickname() == null || !interactionEntity.getNickname()
                                                                                 .equals(currentNickname)) {
                    interactionEntity.setNickname(currentNickname);
                    em.merge(interactionEntity);
                }
            }

            var interactionStream = ncProtocolDecoder.interactionStreamManager.getOrCreateIfAbsent(
              em,
              StreamType.PRIVATE,
              interactionEntity,
              null
            );

            if (interactionStream == null) {
                log.warn(
                  "无法获取或创建交互流，平台: {}, 用户ID: {}",
                  PLATFORM_NAME,
                  senderInfoJson.get("user_id").asString()
                );
                return false;
            }

            return true;
        });
    }

    private Boolean doGroupStreamPersistence(JsonNode rawJsonNode) {
        var senderInfoJson = rawJsonNode.get("sender");
        return ncProtocolDecoder.databaseService.exec(em -> {
            var interactionGroup = ncProtocolDecoder.interactionGroupManager.getOrCreatIfAbsent(
              em,
              PLATFORM_NAME,
              rawJsonNode.get("group_id").asString(),
              rawJsonNode.get("group_name") == null ? null : rawJsonNode.get("group_name").asString()
            );

            if (interactionGroup == null) {
                log.warn(
                  "无法获取或创建交互群，平台: {}, 群ID: {}",
                  PLATFORM_NAME,
                  rawJsonNode.get("group_id").asString()
                );
                return false;
            } else {
                var currentGroupName = rawJsonNode.get("group_name").asString();
                if (interactionGroup.getGroupName() == null || !interactionGroup.getGroupName()
                                                                                .equals(currentGroupName)) {
                    interactionGroup.setGroupName(currentGroupName);
                    em.merge(interactionGroup);
                }
            }

            var interactionEntity = ncProtocolDecoder.interactionEntityManager.getOrCreatIfAbsent(
              em,
              PLATFORM_NAME,
              senderInfoJson.get("user_id")
                            .asString(),
              senderInfoJson.get("nickname")
                            .asString()
            );

            if (interactionEntity == null) {
                log.warn(
                  "无法获取或创建交互实体，平台: {}, 用户ID: {}",
                  PLATFORM_NAME,
                  senderInfoJson.get("user_id").asString()
                );
                return false;
            } else {    // 更新昵称信息
                var currentNickname = senderInfoJson.get("nickname").asString();
                if (interactionEntity.getNickname() == null || !interactionEntity.getNickname()
                                                                                 .equals(currentNickname)) {
                    interactionEntity.setNickname(currentNickname);
                    em.merge(interactionEntity);
                }
            }

            var groupMember = ncProtocolDecoder.groupMemberManager.getOrCreatIfAbsent(
              em,
              interactionGroup,
              interactionEntity,
              senderInfoJson.get("card") == null ? null : senderInfoJson.get("card").asString()
            );

            if (groupMember == null) {
                log.warn(
                  "无法获取或创建群成员，平台: {}, 群ID: {}, 用户ID: {}",
                  PLATFORM_NAME,
                  rawJsonNode.get("group_id").asString(),
                  senderInfoJson.get("user_id").asString()
                );
                return false;
            } else {    // 更新群名片信息
                var currentCardName = senderInfoJson.get("card").asString();
                if (groupMember.getCardName() == null || !groupMember.getCardName().equals(currentCardName)) {
                    groupMember.setCardName(currentCardName);
                    em.persist(groupMember);
                }
            }

            var interactionStream = ncProtocolDecoder.interactionStreamManager.getOrCreateIfAbsent(
              em,
              StreamType.GROUP,
              interactionEntity,
              interactionGroup
            );

            if (interactionStream == null) {
                log.warn(
                  "无法获取或创建交互流，平台: {}, 群ID: {}, 用户ID: {}",
                  PLATFORM_NAME,
                  rawJsonNode.get("group_id").asString(),
                  senderInfoJson.get("user_id").asString()
                );
                return false;
            }

            return true;
        });
    }

    private class ForwardMessageExtractor {
        private MessageEvent.MessageSeg recursivelyExtractForward(ChannelHandlerContext ctx, JsonNode forwardJson) {
            if (forwardJson == null) {
                log.warn("转发消息内容为空");
                return null;
            } else if (!forwardJson.isArray()) {
                log.warn("转发消息内容格式错误，预期为数组");
                return null;
            } else if (forwardJson.isEmpty()) {
                log.warn("转发消息内容为空数组");
                return null;
            }

            List<MessageEvent.MessageSeg> forwardSegments = new ArrayList<>();

            var firstMsg = forwardJson.get(0);

            var streamInfo = extractStreamInfo(
              firstMsg,
              firstMsg.get("message_type").asString(),
              firstMsg.get("sub_type").asString()
            );
            if (streamInfo == null) {
                log.warn("无法提取转发消息的流信息");
                return null;
            }

            switch (streamInfo.streamType()) {
                case PRIVATE -> forwardSegments.add(MessageEvent.MessageSeg.textSeg("[私聊的聊天记录："));
                case GROUP -> forwardSegments.add(MessageEvent.MessageSeg.textSeg("[群聊的聊天记录："));
                case GROUP_TEMP, GROUP_ANONYMOUS -> {
                    log.warn("不支持的转发消息流类型: {}", streamInfo.streamType());
                    return null;
                }
            }

            for (JsonNode node : forwardJson) {
                var senderInfoJsonNode = node.get("sender");

                String displayName = getDisplayName(senderInfoJsonNode);

                String displayId = senderInfoJsonNode.has("user_id") ? senderInfoJsonNode.get("user_id")
                                                                                         .asString() : "未知ID";

                forwardSegments.add(MessageEvent.MessageSeg.textSeg(String.format(
                  "\n[<%s:%s>:",
                  displayName,
                  displayId
                )));

                forwardSegments.add(Objects.requireNonNullElseGet(
                  parseMessageContent(ctx, node.get("message"), false, streamInfo),
                  () -> MessageEvent.MessageSeg.textSeg("无法解析的消息内容")
                ));

                forwardSegments.add(MessageEvent.MessageSeg.textSeg("]"));
            }

            forwardSegments.add(MessageEvent.MessageSeg.textSeg("]"));

            return MessageEvent.MessageSeg.forwardSeg(forwardSegments);
        }

        private void extractForward(
          ChannelHandlerContext ctx,
          JsonNode msgData,
          List<MessageEvent.MessageSeg> messageSegments
        ) {
            var forwardMsgDetailJsonNode = ncProtocolDecoder.napcatReqManager.getForwardMsgDetail(
              ctx,
              msgData.get("id")
                     .asString()
            );
            if (forwardMsgDetailJsonNode != null) {
                var forwardMessage = recursivelyExtractForward(ctx, forwardMsgDetailJsonNode.get("messages"));
                if (forwardMessage != null) {
                    // 解析图片
                    messageSegments.add(forwardMessage);
                    return;
                }
            }
            messageSegments.add(MessageEvent.MessageSeg.textSeg("[转发消息解析失败]"));
        }
    }
}
