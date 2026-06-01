package com.nano.claw.workspace;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 角色私有工作区 - 每个专家角色在项目中的独立工作空间
 * <p>
 * 每个角色拥有自己的私有工作区，管理该角色的所有产出物。
 * 角色可以读取共享区的资源和其他角色的公开产出物，
 * 但自己的私有产出物默认仅自己可见，可选择性地发布到共享区。
 *
 * @author Jason
 * @description 角色私有工作区
 * @date 2026/5/20
 */
public class RoleWorkspace {

    /**
     * 角色工作区状态
     */
    public enum RoleStatus {
        /** 空闲 */
        IDLE,
        /** 工作中 */
        WORKING,
        /** 已完成 */
        DONE,
        /** 执行失败 */
        FAILED
    }

    /** 角色标识（如 sales、pm、architect、developer、tester、pm_project） */
    private String role;

    /** 角色展示名称 */
    private String displayName;

    /** 角色图标 emoji */
    private String emoji;

    /** 当前状态 */
    private String status;

    /** 该角色的产出物列表 */
    private List<WorkspaceArtifact> artifacts = new ArrayList<>();

    /** 开始工作时间 */
    private long startedAt;

    /** 完成时间 */
    private long completedAt;

    /** 执行耗时（毫秒） */
    private long durationMs;

    /** 错误信息（失败时） */
    private String error;

    /** 累计消耗 token */
    private int tokensUsed;

    public RoleWorkspace() {
        this.status = RoleStatus.IDLE.name().toLowerCase();
    }

    public RoleWorkspace(String role, String displayName, String emoji) {
        this.role = role;
        this.displayName = displayName;
        this.emoji = emoji;
        this.status = RoleStatus.IDLE.name().toLowerCase();
    }

    /**
     * 添加产出物到工作区
     *
     * @param artifact 产出物
     */
    public void addArtifact(WorkspaceArtifact artifact) {
        artifacts.add(artifact);
    }

    /**
     * 获取所有公开（shared）产出物
     *
     * @return 可被其他角色读取的产出物列表
     */
    @JsonIgnore
    public List<WorkspaceArtifact> getSharedArtifacts() {
        List<WorkspaceArtifact> shared = new ArrayList<>();
        for (WorkspaceArtifact a : artifacts) {
            if ("shared".equals(a.getVisibility())) {
                shared.add(a);
            }
        }
        return Collections.unmodifiableList(shared);
    }

    /**
     * 获取所有产出物（包含私有）
     *
     * @return 不可修改的产出物列表
     */
    @JsonIgnore
    public List<WorkspaceArtifact> getAllArtifacts() {
        return Collections.unmodifiableList(new ArrayList<>(artifacts));
    }

    /**
     * 获取产出物总大小（字节数）
     */
    @JsonIgnore
    public long getTotalSizeBytes() {
        long total = 0;
        for (WorkspaceArtifact a : artifacts) {
            total += a.getSizeBytes();
        }
        return total;
    }

    /**
     * 获取产出物总数
     */
    @JsonIgnore
    public int getArtifactCount() {
        return artifacts.size();
    }

    // ==================== Getters & Setters ====================

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getEmoji() { return emoji; }
    public void setEmoji(String emoji) { this.emoji = emoji; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public void setArtifacts(List<WorkspaceArtifact> artifacts) { this.artifacts = artifacts; }

    public long getStartedAt() { return startedAt; }
    public void setStartedAt(long startedAt) { this.startedAt = startedAt; }

    public long getCompletedAt() { return completedAt; }
    public void setCompletedAt(long completedAt) { this.completedAt = completedAt; }

    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public int getTokensUsed() { return tokensUsed; }
    public void setTokensUsed(int tokensUsed) { this.tokensUsed = tokensUsed; }
}
