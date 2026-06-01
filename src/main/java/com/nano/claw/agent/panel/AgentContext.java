package com.nano.claw.agent.panel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 共享上下文（黑板模式）
 * <p>
 * 专家团协作的核心数据容器，所有 Expert Agent 通过此对象读取前驱产出、
 * 写入自身结果，实现解耦且有序的协作。
 * <p>
 * 黑板模式（Blackboard Pattern）：
 * - 各专家 Agent 独立读写黑板，无需直接感知彼此
 * - 主控 Agent 负责调度顺序，保证数据一致性
 * - 所有操作通过 synchronized 保证线程安全
 *
 * @author Jason
 * @description Expert Panel 共享黑板
 * @date 2026/5/20
 */
public class AgentContext {

    /** 会话ID */
    private final String sessionId;

    /** 用户原始需求 */
    private final String originalRequest;

    /** 项目工作区ID（用于持久化产出物） */
    private String projectId;

    /** 各角色产出物，key=role，value=产出内容（有序保留插入顺序） */
    private final Map<String, String> artifacts = Collections.synchronizedMap(new LinkedHashMap<>());

    /** 完整消息历史（Agent 间广播/点对点消息） */
    private final List<AgentMessage> messageLog = Collections.synchronizedList(new ArrayList<>());

    /** 结构化共享数据（任意键值对） */
    private final Map<String, Object> sharedData = new ConcurrentHashMap<>();

    // ==================== 动态协作图追踪 ====================

    /** 协作请求历史（动态图的边） */
    private final List<CollaborationRequest> collaborationRequests = Collections.synchronizedList(new ArrayList<>());

    /** 协作响应历史 */
    private final List<CollaborationResponse> collaborationResponses = Collections.synchronizedList(new ArrayList<>());

    /** 已激活的角色集合 */
    private final Set<String> activatedRoles = Collections.synchronizedSet(new HashSet<>());

    /** 已完成的角色集合 */
    private final Set<String> completedRoles = Collections.synchronizedSet(new HashSet<>());

    /** 已拒绝参与的角色集合 */
    private final Set<String> declinedRoles = Collections.synchronizedSet(new HashSet<>());

    public AgentContext(String sessionId, String originalRequest) {
        this.sessionId = sessionId;
        this.originalRequest = originalRequest;
    }

    public AgentContext(String sessionId, String originalRequest, String projectId) {
        this.sessionId = sessionId;
        this.originalRequest = originalRequest;
        this.projectId = projectId;
    }

    // ==================== 黑板读写接口 ====================

    /**
     * 写入角色产出物
     *
     * @param role    角色标识（如 sales、pm、architect）
     * @param content 产出内容
     */
    public void publish(String role, String content) {
        artifacts.put(role, content);
    }

    /**
     * 读取指定角色的产出物
     *
     * @param role 角色标识
     * @return 产出内容，不存在时返回 null
     */
    public String read(String role) {
        return artifacts.get(role);
    }

    /**
     * 判断指定角色的产出物是否已就绪
     *
     * @param role 角色标识
     * @return 是否存在且非空
     */
    public boolean hasArtifact(String role) {
        String content = artifacts.get(role);
        return content != null && !content.trim().isEmpty();
    }

    /**
     * 获取所有已完成角色的产出物快照
     *
     * @return 不可修改的产出物视图
     */
    public Map<String, String> getAllArtifacts() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(artifacts));
    }

    /**
     * 广播消息到消息日志
     *
     * @param message 消息对象
     */
    public void broadcast(AgentMessage message) {
        messageLog.add(message);
    }

    /**
     * 获取完整消息历史快照
     *
     * @return 不可修改的消息列表
     */
    public List<AgentMessage> getMessageLog() {
        return Collections.unmodifiableList(new ArrayList<>(messageLog));
    }

    /**
     * 存入结构化共享数据
     *
     * @param key   键
     * @param value 值
     */
    public void put(String key, Object value) {
        sharedData.put(key, value);
    }

    /**
     * 读取结构化共享数据
     *
     * @param key 键
     * @return 值，不存在时返回 null
     */
    public Object get(String key) {
        return sharedData.get(key);
    }

    /**
     * 构建给指定角色的上下文摘要
     * <p>
     * 将所有已完成角色的产出物组合为该角色 Agent 的输入上下文字符串
     *
     * @param requiredRoles 需要读取的前驱角色列表（可为null，表示读取全部已完成的产出）
     * @return 上下文摘要字符串
     */
    public String buildContextSummary(List<String> requiredRoles) {
        Map<String, String> availableArtifacts = getAllArtifacts();
        if (availableArtifacts.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("## 前序专家意见汇总\n\n");

        if (requiredRoles == null || requiredRoles.isEmpty()) {
            // 动态模式：读取所有已完成角色的产出
            for (Map.Entry<String, String> entry : availableArtifacts.entrySet()) {
                sb.append("### ").append(getRoleDisplayName(entry.getKey())).append("\n");
                sb.append(entry.getValue()).append("\n\n");
            }
        } else {
            // 固定模式：仅读取指定角色的产出
            for (String role : requiredRoles) {
                String artifact = read(role);
                if (artifact != null && !artifact.trim().isEmpty()) {
                    sb.append("### ").append(getRoleDisplayName(role)).append("\n");
                    sb.append(artifact).append("\n\n");
                }
            }
        }
        return sb.toString();
    }

    /**
     * 获取角色展示名称
     */
    private String getRoleDisplayName(String role) {
        switch (role) {
            case "sales":        return "销售分析";
            case "pm":           return "产品需求";
            case "architect":    return "技术架构";
            case "developer":    return "开发方案";
            case "tester":       return "测试策略";
            case "pm_project":   return "项目管理";
            default:             return role;
        }
    }

    // ==================== 动态协作图接口 ====================

    /**
     * 注册协作请求（动态图新增边）
     */
    public void addCollaborationRequest(CollaborationRequest request) {
        collaborationRequests.add(request);
    }

    /**
     * 注册协作响应
     */
    public void addCollaborationResponse(CollaborationResponse response) {
        collaborationResponses.add(response);
        if (response.getDecision() == CollaborationResponse.Decision.ACCEPT) {
            declinedRoles.remove(response.getRole());
        } else {
            declinedRoles.add(response.getRole());
        }
    }

    /**
     * 标记角色已激活
     */
    public void activateRole(String role) {
        activatedRoles.add(role);
        declinedRoles.remove(role);
    }

    /**
     * 标记角色已完成
     */
    public void completeRole(String role) {
        completedRoles.add(role);
    }

    /**
     * 判断角色是否已激活
     */
    public boolean isRoleActivated(String role) {
        return activatedRoles.contains(role);
    }

    /**
     * 判断角色是否已完成
     */
    public boolean isRoleCompleted(String role) {
        return completedRoles.contains(role);
    }

    /**
     * 判断角色是否已拒绝参与
     */
    public boolean isRoleDeclined(String role) {
        return declinedRoles.contains(role);
    }

    /**
     * 获取所有协作请求快照
     */
    public List<CollaborationRequest> getCollaborationRequests() {
        return Collections.unmodifiableList(new ArrayList<>(collaborationRequests));
    }

    /**
     * 获取所有协作响应快照
     */
    public List<CollaborationResponse> getCollaborationResponses() {
        return Collections.unmodifiableList(new ArrayList<>(collaborationResponses));
    }

    /**
     * 获取已激活角色集合
     */
    public Set<String> getActivatedRoles() {
        return Collections.unmodifiableSet(new HashSet<>(activatedRoles));
    }

    /**
     * 获取已完成角色集合
     */
    public Set<String> getCompletedRoles() {
        return Collections.unmodifiableSet(new HashSet<>(completedRoles));
    }

    /**
     * 获取已拒绝角色集合
     */
    public Set<String> getDeclinedRoles() {
        return Collections.unmodifiableSet(new HashSet<>(declinedRoles));
    }

    /**
     * 构建协作图的可视化描述
     */
    public String buildCollaborationGraphSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("已参与角色: ").append(String.join(", ", activatedRoles)).append("\n");
        if (!declinedRoles.isEmpty()) {
            sb.append("已拒绝角色: ").append(String.join(", ", declinedRoles)).append("\n");
        }
        sb.append("协作关系:\n");
        for (CollaborationRequest req : collaborationRequests) {
            sb.append("  ").append(req.getFromRole())
              .append(" --").append(req.getRequestType().name().toLowerCase())
              .append("-> ").append(req.getToRole())
              .append(" (").append(req.getReason()).append(")\n");
        }
        return sb.toString();
    }

    // ==================== Getters ====================

    public String getSessionId() { return sessionId; }

    public String getOriginalRequest() { return originalRequest; }

    public Map<String, Object> getSharedData() { return Collections.unmodifiableMap(sharedData); }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
}
