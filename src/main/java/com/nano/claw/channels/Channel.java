package com.nano.claw.channels;

/**
 * 消息通道接口 - 对接不同 IM 平台
 * <p>
 * 统一不同 IM 平台（飞书、钉钉、企微等）的消息收发接口
 *
 * @author Jason
 * @description IM通道抽象接口
 * @date 2026/5/19
 */
public interface Channel {

    /**
     * 通道名称
     */
    String getName();

    /**
     * 发送文本消息
     *
     * @param targetId 目标ID（群聊ID/用户ID）
     * @param content  消息内容
     * @return 是否发送成功
     */
    boolean sendText(String targetId, String content);

    /**
     * 回复消息（带原始消息上下文）
     *
     * @param originalMsgId 原始消息ID
     * @param content       回复内容
     * @return 是否发送成功
     */
    boolean reply(String originalMsgId, String content);

    /**
     * 处理接收到的事件消息（webhook 回调）
     *
     * @param eventPayload 事件数据（JSON格式）
     * @return 处理后的响应
     */
    ChannelMessage handleEvent(String eventPayload);
}