package com.nano.claw.workspace;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 项目共享工作区 - 以项目为维度创建的共享协作空间
 * <p>
 * 项目工作区是所有角色共享的协作环境：
 * - 包含项目的基本信息和原始需求
 * - 各角色在其中拥有私有工作区（RoleWorkspace）
 * - 共享区存放所有角色都可见的公共产出物
 * - 所有产出物持久化到文件系统，供自身和他人随时读取
 *
 * @author Jason
 * @description 项目共享工作区
 * @date 2026/5/20
 */
public class ProjectWorkspace {

    /**
     * 项目工作区状态
     */
    public enum WorkspaceStatus {
        /** 已创建，待启动 */
        CREATED,
        /** 执行中 */
        RUNNING,
        /** 已完成 */
        COMPLETED,
        /** 执行失败 */
        FAILED
    }

    /** 工作区唯一ID */
    private String id;

    /** 项目名称 */
    private String name;

    /** 项目描述/原始需求 */
    private String description;

    /** 当前状态 */
    private String status;

    /** 各角色私有工作区，key=role */
    private Map<String, RoleWorkspace> roleWorkspaces = new LinkedHashMap<>();

    /** 共享区产出物（所有角色可见） */
    private List<WorkspaceArtifact> sharedArtifacts = new ArrayList<>();

    /** 创建时间 */
    private long createdAt;

    /** 更新时间 */
    private long updatedAt;

    /** 完成时间 */
    private long completedAt;

    /** 总耗时（毫秒） */
    private long totalDurationMs;

    /** 总消耗 token */
    private int totalTokens;

    /** 综合交付报告（最终聚合产出） */
    private String finalReport;

    public ProjectWorkspace() {
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = this.createdAt;
        this.status = WorkspaceStatus.CREATED.name().toLowerCase();
    }

    /**
     * 获取或创建角色工作区
     *
     * @param role        角色标识
     * @param displayName 展示名称
     * @param emoji       图标
     * @return 角色工作区
     */
    public RoleWorkspace getOrCreateRoleWorkspace(String role, String displayName, String emoji) {
        return roleWorkspaces.computeIfAbsent(role,
                r -> new RoleWorkspace(r, displayName, emoji));
    }

    /**
     * 获取角色工作区
     *
     * @param role 角色标识
     * @return 角色工作区，不存在返回 null
     */
    public RoleWorkspace getRoleWorkspace(String role) {
        return roleWorkspaces.get(role);
    }

    /**
     * 添加共享区产出物
     *
     * @param artifact 产出物
     */
    public void addSharedArtifact(WorkspaceArtifact artifact) {
        sharedArtifacts.add(artifact);
        this.updatedAt = System.currentTimeMillis();
    }

    /**
     * 获取所有角色的公开产出物（供其他角色读取）
     *
     * @param excludeRole 排除的角色（不读取自己的产出物）
     * @return 其他角色的公开产出物列表
     */
    public List<WorkspaceArtifact> getOtherRolesSharedArtifacts(String excludeRole) {
        List<WorkspaceArtifact> result = new ArrayList<>();
        for (Map.Entry<String, RoleWorkspace> entry : roleWorkspaces.entrySet()) {
            if (!entry.getKey().equals(excludeRole)) {
                result.addAll(entry.getValue().getSharedArtifacts());
            }
        }
        result.addAll(sharedArtifacts);
        return Collections.unmodifiableList(result);
    }

    /**
     * 获取所有角色的全部产出物（包括私有）
     *
     * @return 所有产出物列表
     */
    @JsonIgnore
    public List<WorkspaceArtifact> getAllArtifacts() {
        List<WorkspaceArtifact> all = new ArrayList<>(sharedArtifacts);
        for (RoleWorkspace rw : roleWorkspaces.values()) {
            all.addAll(rw.getAllArtifacts());
        }
        return Collections.unmodifiableList(all);
    }

    /**
     * 计算总体进度百分比
     *
     * @return 完成百分比 0-100
     */
    @JsonIgnore
    public int getProgressPercent() {
        if (roleWorkspaces.isEmpty()) return 0;
        long done = roleWorkspaces.values().stream()
                .filter(rw -> "done".equals(rw.getStatus()))
                .count();
        return (int) (done * 100 / roleWorkspaces.size());
    }

    /**
     * 获取所有产出物总大小
     */
    @JsonIgnore
    public long getTotalSizeBytes() {
        long total = 0;
        for (WorkspaceArtifact a : sharedArtifacts) {
            total += a.getSizeBytes();
        }
        for (RoleWorkspace rw : roleWorkspaces.values()) {
            total += rw.getTotalSizeBytes();
        }
        return total;
    }

    /**
     * 获取统计摘要
     */
    @JsonIgnore
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalRoles", roleWorkspaces.size());
        stats.put("doneRoles", roleWorkspaces.values().stream().filter(rw -> "done".equals(rw.getStatus())).count());
        stats.put("totalArtifacts", getAllArtifacts().size());
        stats.put("totalSizeBytes", getTotalSizeBytes());
        stats.put("totalTokens", totalTokens);
        stats.put("progressPercent", getProgressPercent());
        return stats;
    }

    // ==================== Getters & Setters ====================

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getStatus() { return status; }
    public void setStatus(String status) {
        this.status = status;
        this.updatedAt = System.currentTimeMillis();
    }

    public Map<String, RoleWorkspace> getRoleWorkspaces() { return roleWorkspaces; }
    public void setRoleWorkspaces(Map<String, RoleWorkspace> roleWorkspaces) { this.roleWorkspaces = roleWorkspaces; }

    public List<WorkspaceArtifact> getSharedArtifacts() { return Collections.unmodifiableList(sharedArtifacts); }
    public void setSharedArtifacts(List<WorkspaceArtifact> sharedArtifacts) { this.sharedArtifacts = sharedArtifacts; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }

    public long getCompletedAt() { return completedAt; }
    public void setCompletedAt(long completedAt) { this.completedAt = completedAt; }

    public long getTotalDurationMs() { return totalDurationMs; }
    public void setTotalDurationMs(long totalDurationMs) { this.totalDurationMs = totalDurationMs; }

    public int getTotalTokens() { return totalTokens; }
    public void setTotalTokens(int totalTokens) { this.totalTokens = totalTokens; }

    public String getFinalReport() { return finalReport; }
    public void setFinalReport(String finalReport) { this.finalReport = finalReport; }
}
