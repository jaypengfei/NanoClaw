package com.nano.claw.agent.panel;

/**
 * 角色间协作请求
 * <p>
 * 代表一个角色向另一个角色发起的协作请求。
 * 支持三种协作类型：
 * - COLLABORATE: 请求对方角色参与项目协作（正向流转）
 * - QUESTION: 对已完成角色提出疑问（回退交互）
 * - FEEDBACK: 对已完成角色提供反馈/打回需求（回退交互）
 * <p>
 * 协作请求构成动态图的边（Edge），从 fromRole 指向 toRole。
 *
 * @author Jason
 * @description 专家团动态协作：角色间协作请求数据模型
 * @date 2026/5/21
 */
public class CollaborationRequest {

    /**
     * 协作请求类型枚举
     */
    public enum RequestType {
        /** 请求对方角色参与项目协作 */
        COLLABORATE,
        /** 对已完成角色提出疑问，需对方补充回答 */
        QUESTION,
        /** 对已完成角色提供反馈，打回需求要求修改 */
        FEEDBACK
    }

    /** 发起方角色标识 */
    private String fromRole;

    /** 目标方角色标识 */
    private String toRole;

    /** 请求类型 */
    private RequestType requestType;

    /** 请求原因/理由 */
    private String reason;

    /** 附带上文片段（发起方产出中与目标角色相关的部分） */
    private String contextSnippet;

    /** 请求时间戳 */
    private long timestamp;

    public CollaborationRequest() {
        this.timestamp = System.currentTimeMillis();
    }

    public CollaborationRequest(String fromRole, String toRole, RequestType requestType,
                                 String reason, String contextSnippet) {
        this.fromRole = fromRole;
        this.toRole = toRole;
        this.requestType = requestType;
        this.reason = reason;
        this.contextSnippet = contextSnippet;
        this.timestamp = System.currentTimeMillis();
    }

    /**
     * 创建正向协作请求
     */
    public static CollaborationRequest collaborate(String fromRole, String toRole,
                                                    String reason, String contextSnippet) {
        return new CollaborationRequest(fromRole, toRole, RequestType.COLLABORATE, reason, contextSnippet);
    }

    /**
     * 创建疑问请求（回退交互）
     */
    public static CollaborationRequest question(String fromRole, String toRole,
                                                 String reason, String contextSnippet) {
        return new CollaborationRequest(fromRole, toRole, RequestType.QUESTION, reason, contextSnippet);
    }

    /**
     * 创建反馈请求（打回需求）
     */
    public static CollaborationRequest feedback(String fromRole, String toRole,
                                                 String reason, String contextSnippet) {
        return new CollaborationRequest(fromRole, toRole, RequestType.FEEDBACK, reason, contextSnippet);
    }

    // ==================== Getters & Setters ====================

    public String getFromRole() { return fromRole; }
    public void setFromRole(String fromRole) { this.fromRole = fromRole; }

    public String getToRole() { return toRole; }
    public void setToRole(String toRole) { this.toRole = toRole; }

    public RequestType getRequestType() { return requestType; }
    public void setRequestType(RequestType requestType) { this.requestType = requestType; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getContextSnippet() { return contextSnippet; }
    public void setContextSnippet(String contextSnippet) { this.contextSnippet = contextSnippet; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    @Override
    public String toString() {
        return "CollaborationRequest{" + fromRole + " --" + requestType + "-> " + toRole
                + ", reason=" + reason + "}";
    }
}
