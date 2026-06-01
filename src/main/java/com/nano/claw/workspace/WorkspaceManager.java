package com.nano.claw.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 工作区管理服务
 * <p>
 * 负责项目工作区的创建、查询、持久化和生命周期管理。
 * 所有工作区数据持久化到文件系统（data/workspaces/目录），
 * 确保产出物可供自身和其他角色随时读取。
 * <p>
 * 存储结构：
 * <pre>
 * data/workspaces/
 *   ├── {projectId}/
 *   │   ├── workspace.json          # 工作区元数据
 *   │   ├── shared/                 # 共享区产出物
 *   │   │   └── {artifactName}.md
 *   │   ├── roles/
 *   │   │   ├── sales/
 *   │   │   │   └── {artifactName}.md
 *   │   │   ├── pm/
 *   │   │   │   └── ...
 *   │   │   └── ...
 *   │   └── report.md              # 综合交付报告
 * </pre>
 *
 * @author Jason
 * @description 项目工作区管理服务
 * @date 2026/5/20
 */
@Service
public class WorkspaceManager {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceManager.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static {
        MAPPER.enable(SerializationFeature.INDENT_OUTPUT);
    }

    @Value("${nanoclaw.workspace.base-path:data/workspaces}")
    private String basePath;

    /** 内存中的工作区缓存，key=projectId */
    private final ConcurrentHashMap<String, ProjectWorkspace> workspaceCache = new ConcurrentHashMap<>();

    /** 角色元数据配置 */
    private static final String[][] ROLE_META = {
            {"sales",      "销售分析师",   "💼"},
            {"pm",         "产品经理",     "📝"},
            {"architect",  "架构师",       "🏗️"},
            {"developer",  "开发工程师",   "💻"},
            {"tester",     "测试工程师",   "🔍"},
            {"pm_project", "项目经理",     "📊"}
    };

    @PostConstruct
    public void init() {
        // 启动时加载已有工作区
        loadExistingWorkspaces();
        log.info("[WORKSPACE] 工作区管理服务初始化完成，已加载 {} 个项目工作区", workspaceCache.size());
    }

    /**
     * 创建新的项目工作区
     *
     * @param name        项目名称
     * @param description 项目描述/需求
     * @return 创建的项目工作区
     */
    public ProjectWorkspace createWorkspace(String name, String description) {
        String projectId = "proj_" + UUID.randomUUID().toString().substring(0, 8);
        ProjectWorkspace workspace = new ProjectWorkspace();
        workspace.setId(projectId);
        workspace.setName(name);
        workspace.setDescription(description);

        // 初始化所有角色工作区
        for (String[] meta : ROLE_META) {
            workspace.getOrCreateRoleWorkspace(meta[0], meta[1], meta[2]);
        }

        // 创建文件系统目录
        createWorkspaceDirs(projectId);

        // 持久化元数据
        persistWorkspace(workspace);

        // 缓存
        workspaceCache.put(projectId, workspace);

        log.info("[WORKSPACE] 创建项目工作区: {} ({})", name, projectId);
        return workspace;
    }

    /**
     * 获取项目工作区
     *
     * @param projectId 项目ID
     * @return 工作区，不存在返回 null
     */
    public ProjectWorkspace getWorkspace(String projectId) {
        return workspaceCache.get(projectId);
    }

    /**
     * 获取所有项目工作区列表
     *
     * @return 工作区列表
     */
    public List<ProjectWorkspace> listWorkspaces() {
        return new ArrayList<>(workspaceCache.values());
    }

    /**
     * 获取最近的工作区列表（按创建时间倒序）
     *
     * @param limit 最大数量
     * @return 工作区列表
     */
    public List<ProjectWorkspace> listRecentWorkspaces(int limit) {
        return workspaceCache.values().stream()
                .sorted((a, b) -> Long.compare(b.getCreatedAt(), a.getCreatedAt()))
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * 更新工作区并持久化
     *
     * @param workspace 工作区
     */
    public void updateWorkspace(ProjectWorkspace workspace) {
        workspace.setUpdatedAt(System.currentTimeMillis());
        persistWorkspace(workspace);
    }

    /**
     * 删除项目工作区
     *
     * @param projectId 项目ID
     */
    public void deleteWorkspace(String projectId) {
        workspaceCache.remove(projectId);
        try {
            Path dir = Paths.get(basePath, projectId);
            deleteDirectory(dir.toFile());
            log.info("[WORKSPACE] 删除项目工作区: {}", projectId);
        } catch (Exception e) {
            log.error("[WORKSPACE] 删除工作区目录失败: {}", projectId, e);
        }
    }

    /**
     * 添加角色产出物到工作区
     *
     * @param projectId 项目ID
     * @param role      角色
     * @param artifact  产出物
     */
    public void addArtifact(String projectId, String role, WorkspaceArtifact artifact) {
        ProjectWorkspace workspace = workspaceCache.get(projectId);
        if (workspace == null) {
            log.warn("[WORKSPACE] 工作区不存在: {}", projectId);
            return;
        }

        RoleWorkspace roleWorkspace = workspace.getOrCreateRoleWorkspace(role, getRoleDisplayName(role), getRoleEmoji(role));
        roleWorkspace.addArtifact(artifact);

        // 持久化产出物到文件
        persistArtifact(projectId, role, artifact);

        // 更新工作区元数据
        updateWorkspace(workspace);

        log.info("[WORKSPACE] 添加产出物: {}/{} - {} ({}字节)",
                projectId, role, artifact.getName(), artifact.getSizeBytes());
    }

    /**
     * 添加共享产出物
     *
     * @param projectId 项目ID
     * @param artifact  产出物
     */
    public void addSharedArtifact(String projectId, WorkspaceArtifact artifact) {
        ProjectWorkspace workspace = workspaceCache.get(projectId);
        if (workspace == null) return;

        workspace.addSharedArtifact(artifact);
        persistArtifact(projectId, "shared", artifact);
        updateWorkspace(workspace);
    }

    /**
     * 保存综合交付报告
     *
     * @param projectId 项目ID
     * @param report    报告内容
     */
    public void saveFinalReport(String projectId, String report) {
        ProjectWorkspace workspace = workspaceCache.get(projectId);
        if (workspace == null) return;

        workspace.setFinalReport(report);

        // 持久化报告文件
        try {
            Path reportPath = Paths.get(basePath, projectId, "report.md");
            Files.createDirectories(reportPath.getParent());
            Files.write(reportPath, report.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.error("[WORKSPACE] 保存报告失败: {}", projectId, e);
        }

        updateWorkspace(workspace);
    }

    /**
     * 读取角色产出物内容
     *
     * @param projectId   项目ID
     * @param role        角色
     * @param artifactId  产出物ID
     * @return 产出物内容，不存在返回 null
     */
    public String readArtifactContent(String projectId, String role, String artifactId) {
        ProjectWorkspace workspace = workspaceCache.get(projectId);
        if (workspace == null) return null;

        // 先从内存查找文件路径
        if ("shared".equals(role)) {
            for (WorkspaceArtifact a : workspace.getSharedArtifacts()) {
                if (a.getId().equals(artifactId)) return a.getContent();
            }
        } else {
            RoleWorkspace rw = workspace.getRoleWorkspace(role);
            if (rw != null) {
                for (WorkspaceArtifact a : rw.getAllArtifacts()) {
                    if (a.getId().equals(artifactId)) return a.getContent();
                }
            }
        }

        // 降级：从文件读取
        try {
            Path artifactPath = Paths.get(basePath, projectId, "roles", role, artifactId + ".md");
            if (Files.exists(artifactPath)) {
                return new String(Files.readAllBytes(artifactPath), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.error("[WORKSPACE] 读取产出物失败: {}/{}/{}", projectId, role, artifactId);
        }
        return null;
    }

    /**
     * 获取工作区统计摘要
     *
     * @param projectId 项目ID
     * @return 统计数据
     */
    public Map<String, Object> getWorkspaceStats(String projectId) {
        ProjectWorkspace workspace = workspaceCache.get(projectId);
        if (workspace == null) return Collections.emptyMap();
        return workspace.getStats();
    }

    // ==================== 私有方法 ====================

    private void createWorkspaceDirs(String projectId) {
        try {
            Path projectDir = Paths.get(basePath, projectId);
            Files.createDirectories(projectDir);
            Files.createDirectories(Paths.get(basePath, projectId, "shared"));
            Files.createDirectories(Paths.get(basePath, projectId, "roles"));
            for (String[] meta : ROLE_META) {
                Files.createDirectories(Paths.get(basePath, projectId, "roles", meta[0]));
            }
        } catch (IOException e) {
            log.error("[WORKSPACE] 创建工作区目录失败: {}", projectId, e);
        }
    }

    private void persistWorkspace(ProjectWorkspace workspace) {
        try {
            Path metaPath = Paths.get(basePath, workspace.getId(), "workspace.json");
            Files.createDirectories(metaPath.getParent());
            MAPPER.writeValue(metaPath.toFile(), workspace);
        } catch (IOException e) {
            log.error("[WORKSPACE] 持久化工作区元数据失败: {}", workspace.getId(), e);
        }
    }

    private void persistArtifact(String projectId, String role, WorkspaceArtifact artifact) {
        try {
            String ext = getExtension(artifact.getFormat());
            String fileName = sanitizeFileName(artifact.getName()) + "_" + artifact.getId() + ext;
            Path artifactPath;
            if ("shared".equals(role)) {
                artifactPath = Paths.get(basePath, projectId, "shared", fileName);
            } else {
                artifactPath = Paths.get(basePath, projectId, "roles", role, fileName);
            }
            Files.createDirectories(artifactPath.getParent());
            Files.write(artifactPath, artifact.getContent().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.error("[WORKSPACE] 持久化产出物失败: {}/{}/{}", projectId, role, artifact.getName(), e);
        }
    }

    private void loadExistingWorkspaces() {
        try {
            Path dir = Paths.get(basePath);
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
                return;
            }

            Files.list(dir)
                    .filter(Files::isDirectory)
                    .forEach(projectDir -> {
                        Path metaPath = projectDir.resolve("workspace.json");
                        if (Files.exists(metaPath)) {
                            try {
                                ProjectWorkspace workspace = MAPPER.readValue(metaPath.toFile(), ProjectWorkspace.class);
                                workspaceCache.put(workspace.getId(), workspace);
                                log.debug("[WORKSPACE] 加载已有工作区: {} ({})", workspace.getName(), workspace.getId());
                            } catch (IOException e) {
                                log.warn("[WORKSPACE] 加载工作区元数据失败: {}", metaPath, e);
                            }
                        }
                    });
        } catch (IOException e) {
            log.error("[WORKSPACE] 扫描工作区目录失败", e);
        }
    }

    private void deleteDirectory(File dir) {
        if (dir.isDirectory()) {
            File[] children = dir.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteDirectory(child);
                }
            }
        }
        dir.delete();
    }

    private String getExtension(String format) {
        if (format == null) return ".md";
        switch (format.toLowerCase()) {
            case "java":   return ".java";
            case "json":   return ".json";
            case "yaml":
            case "yml":    return ".yaml";
            case "sql":    return ".sql";
            case "xml":    return ".xml";
            case "python": return ".py";
            default:       return ".md";
        }
    }

    private String sanitizeFileName(String name) {
        if (name == null) return "untitled";
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").replaceAll("\\s+", "_");
    }

    private String getRoleDisplayName(String role) {
        for (String[] meta : ROLE_META) {
            if (meta[0].equals(role)) return meta[1];
        }
        return role;
    }

    private String getRoleEmoji(String role) {
        for (String[] meta : ROLE_META) {
            if (meta[0].equals(role)) return meta[2];
        }
        return "👤";
    }
}
