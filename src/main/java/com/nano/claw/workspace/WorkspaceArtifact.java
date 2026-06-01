package com.nano.claw.workspace;

/**
 * 工作区产出物 - 角色产出的文档/代码/设计方案等
 * <p>
 * 每个产出物归属于某个角色工作区，包含类型、名称、内容和格式信息。
 * 所有产出物都会持久化到文件系统，供自身和其他角色读取引用。
 *
 * @author Jason
 * @description 工作区产出物实体
 * @date 2026/5/20
 */
public class WorkspaceArtifact {

    /**
     * 产出物类型枚举
     */
    public enum ArtifactType {
        /** 文档类（需求文档、分析报告等） */
        DOCUMENT,
        /** 代码类（源代码、配置文件等） */
        CODE,
        /** 设计类（架构设计、UI设计稿等） */
        DESIGN,
        /** 规划类（项目计划、排期表等） */
        PLAN,
        /** 测试类（测试用例、测试报告等） */
        TEST_CASE,
        /** 分析类（市场分析、竞品分析等） */
        ANALYSIS,
        /** 其他 */
        OTHER
    }

    /** 产出物唯一ID */
    private String id;

    /** 产出物名称（如"需求分析报告"、"系统架构设计"） */
    private String name;

    /** 产出物类型 */
    private String type;

    /** 内容格式（markdown / java / json / yaml / sql 等） */
    private String format;

    /** 产出物内容 */
    private String content;

    /** 所属角色（如 sales、pm、architect） */
    private String role;

    /** 创建时间（毫秒时间戳） */
    private long createdAt;

    /** 更新时间（毫秒时间戳） */
    private long updatedAt;

    /** 文件大小（字节数） */
    private long sizeBytes;

    /** 可见性：shared=所有人可见，private=仅自己可见 */
    private String visibility;

    public WorkspaceArtifact() {
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = this.createdAt;
        this.visibility = "shared";
    }

    public static WorkspaceArtifact of(String id, String name, ArtifactType type, String format,
                                        String content, String role) {
        WorkspaceArtifact artifact = new WorkspaceArtifact();
        artifact.setId(id);
        artifact.setName(name);
        artifact.setType(type.name().toLowerCase());
        artifact.setFormat(format);
        artifact.setContent(content);
        artifact.setRole(role);
        artifact.setSizeBytes(content != null ? content.length() : 0);
        return artifact;
    }

    // ==================== Getters & Setters ====================

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }

    public String getContent() { return content; }
    public void setContent(String content) {
        this.content = content;
        this.sizeBytes = content != null ? content.length() : 0;
        this.updatedAt = System.currentTimeMillis();
    }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }

    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long sizeBytes) { this.sizeBytes = sizeBytes; }

    public String getVisibility() { return visibility; }
    public void setVisibility(String visibility) { this.visibility = visibility; }
}
