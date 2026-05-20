package com.nano.claw.channels;

/**
 * 统一的消息模型 - 跨 IM 平台通用
 *
 * @author Jason
 * @description 通道消息统一格式
 * @date 2026/5/19
 */
public class ChannelMessage {

    /** 消息ID */
    private String msgId;
    /** 发送者ID */
    private String senderId;
    /** 发送者名称 */
    private String senderName;
    /** 消息内容 */
    private String content;
    /** 所属会话/群聊ID */
    private String chatId;
    /** 来源通道 */
    private String channelType;
    /** 消息类型：text / image / event */
    private String msgType;

    public ChannelMessage() {
    }

    public String getMsgId() {
        return msgId;
    }

    public void setMsgId(String msgId) {
        this.msgId = msgId;
    }

    public String getSenderId() {
        return senderId;
    }

    public void setSenderId(String senderId) {
        this.senderId = senderId;
    }

    public String getSenderName() {
        return senderName;
    }

    public void setSenderName(String senderName) {
        this.senderName = senderName;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getChatId() {
        return chatId;
    }

    public void setChatId(String chatId) {
        this.chatId = chatId;
    }

    public String getChannelType() {
        return channelType;
    }

    public void setChannelType(String channelType) {
        this.channelType = channelType;
    }

    public String getMsgType() {
        return msgType;
    }

    public void setMsgType(String msgType) {
        this.msgType = msgType;
    }
}