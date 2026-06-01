package com.nano.claw.agent.panel;

/**
 * 角色参与决策响应
 * <p>
 * 当一个角色收到协作请求后，通过此对象返回其参与决策。
 * 支持：
 * - ACCEPT: 接受协作请求，将参与项目
 * - DECLINE: 拒绝参与，说明理由（无需角色不出现在流程中）
 *
 * @author Jason
 * @description 专家团动态协作：角色参与决策响应
 * @date 2026/5/21
 */
public class CollaborationResponse {

    /**
     * 参与决策枚举
     */
    public enum Decision {
        /** 接受协作请求 */
        ACCEPT,
        /** 拒绝参与，无需本角色 */
        DECLINE
    }

    /** 响应的角色标识 */
    private String role;

    /** 请求发起方角色标识 */
    private String requestFromRole;

    /** 决策结果 */
    private Decision decision;

    /** 决策理由 */
    private String reason;

    /** 原始协作请求 */
    private CollaborationRequest originalRequest;

    public CollaborationResponse() {
    }

    public CollaborationResponse(String role, String requestFromRole, Decision decision,
                                  String reason, CollaborationRequest originalRequest) {
        this.role = role;
        this.requestFromRole = requestFromRole;
        this.decision = decision;
        this.reason = reason;
        this.originalRequest = originalRequest;
    }

    /**
     * 创建接受响应
     */
    public static CollaborationResponse accept(String role, String requestFromRole,
                                                String reason, CollaborationRequest request) {
        return new CollaborationResponse(role, requestFromRole, Decision.ACCEPT, reason, request);
    }

    /**
     * 创建拒绝响应
     */
    public static CollaborationResponse decline(String role, String requestFromRole,
                                                 String reason, CollaborationRequest request) {
        return new CollaborationResponse(role, requestFromRole, Decision.DECLINE, reason, request);
    }

    // ==================== Getters & Setters ====================

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getRequestFromRole() { return requestFromRole; }
    public void setRequestFromRole(String requestFromRole) { this.requestFromRole = requestFromRole; }

    public Decision getDecision() { return decision; }
    public void setDecision(Decision decision) { this.decision = decision; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public CollaborationRequest getOriginalRequest() { return originalRequest; }
    public void setOriginalRequest(CollaborationRequest originalRequest) { this.originalRequest = originalRequest; }

    @Override
    public String toString() {
        return "CollaborationResponse{" + role + " " + decision + " (from " + requestFromRole + "): " + reason + "}";
    }
}
