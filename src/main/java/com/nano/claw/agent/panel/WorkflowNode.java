package com.nano.claw.agent.panel;

import java.util.ArrayList;
import java.util.List;

/**
 * 工作流节点
 * <p>
 * 代表专家团工作流中的一个执行单元，包含角色信息、依赖关系和执行状态。
 * WorkflowPlanner 基于节点依赖关系构建 DAG，按拓扑顺序调度执行。
 *
 * @author Jason
 * @description Expert Panel DAG 工作流调度节点
 * @date 2026/5/20
 */
public class WorkflowNode {

    /**
     * 节点执行状态枚举
     */
    public enum NodeStatus {
        /** 等待执行（依赖未就绪） */
        PENDING,
        /** 正在执行 */
        RUNNING,
        /** 执行完成 */
        DONE,
        /** 执行失败 */
        FAILED,
        /** 已跳过（可选节点） */
        SKIPPED
    }

    /** 节点唯一ID */
    private String nodeId;

    /** 执行角色（对应 ExpertAgent 的 getRole() 返回值） */
    private String role;

    /** 节点展示名称（用于日志和前端展示） */
    private String displayName;

    /** 依赖的节点ID列表（这些节点全部 DONE 后，本节点才可执行） */
    private List<String> dependencies = new ArrayList<>();

    /** 节点当前状态 */
    private NodeStatus status = NodeStatus.PENDING;

    /** 节点输入（从 AgentContext 读取哪些角色的产出） */
    private List<String> inputRoles = new ArrayList<>();

    /** 节点执行完成时间（毫秒时间戳） */
    private long completedAt;

    /** 节点执行耗时（毫秒） */
    private long durationMs;

    /** 执行失败时的错误信息 */
    private String errorMsg;

    public WorkflowNode() {
    }

    public WorkflowNode(String nodeId, String role, String displayName) {
        this.nodeId = nodeId;
        this.role = role;
        this.displayName = displayName;
    }

    /**
     * 添加依赖节点
     *
     * @param nodeId 依赖节点ID
     * @return this（链式调用）
     */
    public WorkflowNode dependsOn(String nodeId) {
        this.dependencies.add(nodeId);
        this.inputRoles.add(nodeId); // 依赖节点ID即角色ID，同步加入输入角色
        return this;
    }

    /**
     * 判断节点是否已就绪（所有依赖都已完成）
     *
     * @param completedNodeIds 已完成的节点ID集合
     * @return 是否可执行
     */
    public boolean isReady(java.util.Set<String> completedNodeIds) {
        return completedNodeIds.containsAll(dependencies);
    }

    // ==================== Getters & Setters ====================

    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public List<String> getDependencies() { return dependencies; }
    public void setDependencies(List<String> dependencies) { this.dependencies = dependencies; }

    public NodeStatus getStatus() { return status; }
    public void setStatus(NodeStatus status) { this.status = status; }

    public List<String> getInputRoles() { return inputRoles; }
    public void setInputRoles(List<String> inputRoles) { this.inputRoles = inputRoles; }

    public long getCompletedAt() { return completedAt; }
    public void setCompletedAt(long completedAt) { this.completedAt = completedAt; }

    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }

    public String getErrorMsg() { return errorMsg; }
    public void setErrorMsg(String errorMsg) { this.errorMsg = errorMsg; }
}
