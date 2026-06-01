package com.nano.claw.workspace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作区 REST API 控制器
 * <p>
 * 提供项目工作区的查询、产出物读取等接口，
 * 供前端展示工作区数据、角色产出物和综合报告。
 *
 * @author Jason
 * @description 工作区 API 控制器
 * @date 2026/5/20
 */
@RestController
@RequestMapping("/api/workspace")
public class WorkspaceController {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceController.class);

    @Resource
    private WorkspaceManager workspaceManager;

    /**
     * 获取所有项目工作区列表
     */
    @GetMapping("/list")
    public Map<String, Object> listWorkspaces() {
        List<ProjectWorkspace> workspaces = workspaceManager.listWorkspaces();
        List<Map<String, Object>> items = new ArrayList<>();
        for (ProjectWorkspace ws : workspaces) {
            items.add(toWorkspaceSummary(ws));
        }

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("total", items.size());
        result.put("workspaces", items);
        return result;
    }

    /**
     * 获取最近的工作区
     */
    @GetMapping("/recent")
    public Map<String, Object> recentWorkspaces(@RequestParam(defaultValue = "10") int limit) {
        List<ProjectWorkspace> workspaces = workspaceManager.listRecentWorkspaces(limit);
        List<Map<String, Object>> items = new ArrayList<>();
        for (ProjectWorkspace ws : workspaces) {
            items.add(toWorkspaceSummary(ws));
        }

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("total", items.size());
        result.put("workspaces", items);
        return result;
    }

    /**
     * 获取项目工作区详情
     */
    @GetMapping("/{projectId}")
    public Map<String, Object> getWorkspace(@PathVariable String projectId) {
        ProjectWorkspace workspace = workspaceManager.getWorkspace(projectId);
        if (workspace == null) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("error", "工作区不存在: " + projectId);
            return result;
        }
        return toWorkspaceDetail(workspace);
    }

    /**
     * 获取角色工作区详情
     */
    @GetMapping("/{projectId}/role/{role}")
    public Map<String, Object> getRoleWorkspace(@PathVariable String projectId, @PathVariable String role) {
        ProjectWorkspace workspace = workspaceManager.getWorkspace(projectId);
        if (workspace == null) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("error", "工作区不存在");
            return result;
        }

        RoleWorkspace rw = workspace.getRoleWorkspace(role);
        if (rw == null) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("error", "角色工作区不存在: " + role);
            return result;
        }

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("projectId", projectId);
        result.put("role", toRoleWorkspaceDetail(rw));
        return result;
    }

    /**
     * 读取产出物内容
     */
    @GetMapping("/{projectId}/artifact/{role}/{artifactId}")
    public Map<String, Object> readArtifact(
            @PathVariable String projectId,
            @PathVariable String role,
            @PathVariable String artifactId) {
        String content = workspaceManager.readArtifactContent(projectId, role, artifactId);

        Map<String, Object> result = new HashMap<>();
        if (content != null) {
            result.put("success", true);
            result.put("content", content);
            result.put("projectId", projectId);
            result.put("role", role);
            result.put("artifactId", artifactId);
        } else {
            result.put("success", false);
            result.put("error", "产出物不存在");
        }
        return result;
    }

    /**
     * 获取工作区统计信息
     */
    @GetMapping("/{projectId}/stats")
    public Map<String, Object> getWorkspaceStats(@PathVariable String projectId) {
        Map<String, Object> stats = workspaceManager.getWorkspaceStats(projectId);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("projectId", projectId);
        result.put("stats", stats);
        return result;
    }

    /**
     * 删除项目工作区
     */
    @DeleteMapping("/{projectId}")
    public Map<String, Object> deleteWorkspace(@PathVariable String projectId) {
        workspaceManager.deleteWorkspace(projectId);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "工作区已删除");
        return result;
    }

    // ==================== 数据转换方法 ====================

    private Map<String, Object> toWorkspaceSummary(ProjectWorkspace ws) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", ws.getId());
        map.put("name", ws.getName());
        map.put("status", ws.getStatus());
        map.put("createdAt", ws.getCreatedAt());
        map.put("updatedAt", ws.getUpdatedAt());
        map.put("progressPercent", ws.getProgressPercent());
        map.put("totalArtifacts", ws.getAllArtifacts().size());
        map.put("totalTokens", ws.getTotalTokens());
        return map;
    }

    private Map<String, Object> toWorkspaceDetail(ProjectWorkspace ws) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", ws.getId());
        map.put("name", ws.getName());
        map.put("description", ws.getDescription());
        map.put("status", ws.getStatus());
        map.put("createdAt", ws.getCreatedAt());
        map.put("updatedAt", ws.getUpdatedAt());
        map.put("completedAt", ws.getCompletedAt());
        map.put("totalDurationMs", ws.getTotalDurationMs());
        map.put("totalTokens", ws.getTotalTokens());
        map.put("progressPercent", ws.getProgressPercent());
        map.put("finalReport", ws.getFinalReport());

        // 角色工作区列表（包含产出物详情）
        List<Map<String, Object>> roles = new ArrayList<>();
        for (RoleWorkspace rw : ws.getRoleWorkspaces().values()) {
            roles.add(toRoleWorkspaceDetail(rw));
        }
        map.put("roles", roles);

        // 共享产出物列表
        List<Map<String, Object>> sharedArtifacts = new ArrayList<>();
        for (WorkspaceArtifact a : ws.getSharedArtifacts()) {
            sharedArtifacts.add(toArtifactSummary(a));
        }
        map.put("sharedArtifacts", sharedArtifacts);

        // 统计信息
        map.put("stats", ws.getStats());

        return map;
    }

    private Map<String, Object> toRoleWorkspaceSummary(RoleWorkspace rw) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("role", rw.getRole());
        map.put("displayName", rw.getDisplayName());
        map.put("emoji", rw.getEmoji());
        map.put("status", rw.getStatus());
        map.put("artifactCount", rw.getArtifactCount());
        map.put("durationMs", rw.getDurationMs());
        map.put("tokensUsed", rw.getTokensUsed());
        return map;
    }

    private Map<String, Object> toRoleWorkspaceDetail(RoleWorkspace rw) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("role", rw.getRole());
        map.put("displayName", rw.getDisplayName());
        map.put("emoji", rw.getEmoji());
        map.put("status", rw.getStatus());
        map.put("startedAt", rw.getStartedAt());
        map.put("completedAt", rw.getCompletedAt());
        map.put("durationMs", rw.getDurationMs());
        map.put("tokensUsed", rw.getTokensUsed());
        map.put("error", rw.getError());

        List<Map<String, Object>> artifacts = new ArrayList<>();
        for (WorkspaceArtifact a : rw.getAllArtifacts()) {
            artifacts.add(toArtifactSummary(a));
        }
        map.put("artifacts", artifacts);

        return map;
    }

    private Map<String, Object> toArtifactSummary(WorkspaceArtifact a) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", a.getId());
        map.put("name", a.getName());
        map.put("type", a.getType());
        map.put("format", a.getFormat());
        map.put("role", a.getRole());
        map.put("sizeBytes", a.getSizeBytes());
        map.put("visibility", a.getVisibility());
        map.put("createdAt", a.getCreatedAt());
        map.put("updatedAt", a.getUpdatedAt());
        // 内容太长不返回摘要，需单独读取
        if (a.getContent() != null && a.getContent().length() < 200) {
            map.put("preview", a.getContent());
        } else if (a.getContent() != null) {
            map.put("preview", a.getContent().substring(0, 200) + "...");
        }
        return map;
    }
}
