package com.nano.claw.agent.panel;

import java.util.HashMap;
import java.util.Map;

/**
 * Agent 间消息传递对象
 * <p>
 * 用于专家团协作模式下各角色 Agent 之间的信息传递。
 * 支持点对点消息和广播消息（toRole=null）。
 *
 * @author Jason
 * @description Expert Panel 消息总线核心数据结构
 * @date 2026/5/20
 */
public class AgentMessage {

    /**
     * 消息类型枚举
     */
    public enum MessageType {
        /** 需求描述 */
        REQUIREMENT,
        /** 设计方案 */
        DESIGN,
        /** 代码方案 */
        CODE,
        /** 评审意见 */
        REVIEW,
        /** 规划排期 */
        PLAN,
        /** 测试用例 */
        TEST_CASE,
        /** 通用信息 */
        INFO
    }

    /** 发送方角色（如 sales、pm、architect、developer、tester、pm_project） */
    private String fromRole;

    /** 接收方角色，null 表示广播给所有角色 */
    private String toRole;

    /** 消息类型 */
    private String messageType;

    /** 消息正文 */
    private String content;

    /** 附加结构化元数据 */
    private Map<String, Object> metadata = new HashMap<>();

    /** 消息时间戳 */
    private long timestamp;

    public AgentMessage() {
        this.timestamp = System.currentTimeMillis();
    }

    public AgentMessage(String fromRole, String toRole, MessageType messageType, String content) {
        this.fromRole = fromRole;
        this.toRole = toRole;
        this.messageType = messageType.name().toLowerCase();
        this.content = content;
        this.timestamp = System.currentTimeMillis();
    }

    /**
     * 创建广播消息（toRole=null）
     */
    public static AgentMessage broadcast(String fromRole, MessageType messageType, String content) {
        return new AgentMessage(fromRole, null, messageType, content);
    }

    /**
     * 创建点对点消息
     */
    public static AgentMessage to(String fromRole, String toRole, MessageType messageType, String content) {
        return new AgentMessage(fromRole, toRole, messageType, content);
    }

    // ==================== Getters & Setters ====================

    public String getFromRole() { return fromRole; }
    public void setFromRole(String fromRole) { this.fromRole = fromRole; }

    public String getToRole() { return toRole; }
    public void setToRole(String toRole) { this.toRole = toRole; }

    public String getMessageType() { return messageType; }
    public void setMessageType(String messageType) { this.messageType = messageType; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
}
