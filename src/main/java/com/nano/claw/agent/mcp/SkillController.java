package com.nano.claw.agent.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 技能管理 API 控制器
 * <p>
 * 提供技能的查询、安装、卸载、启用、禁用等接口
 *
 * @author Jason
 * @description 技能管理 REST API
 * @date 2026/5/21
 */
@RestController
@RequestMapping("/api/skills")
public class SkillController {

    private static final Logger log = LoggerFactory.getLogger(SkillController.class);

    @Resource
    private SkillManager skillManager;

    /**
     * 获取所有技能列表
     *
     * GET /api/skills
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> listSkills() {
        List<SkillManager.SkillInfo> skills = skillManager.listSkills();
        long enabledCount = skills.stream().filter(SkillManager.SkillInfo::isEnabled).count();

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("skills", skills);
        result.put("total", skills.size());
        result.put("enabledCount", enabledCount);
        return ResponseEntity.ok(result);
    }

    /**
     * 获取已启用的技能列表
     *
     * GET /api/skills/enabled
     */
    @GetMapping("/enabled")
    public ResponseEntity<Map<String, Object>> listEnabledSkills() {
        List<SkillManager.SkillInfo> skills = skillManager.listEnabledSkills();

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("skills", skills);
        result.put("total", skills.size());
        return ResponseEntity.ok(result);
    }

    /**
     * 获取单个技能详情
     *
     * GET /api/skills/{name}
     */
    @GetMapping("/{name}")
    public ResponseEntity<Map<String, Object>> getSkill(@PathVariable String name) {
        SkillManager.SkillInfo skill = skillManager.listSkills().stream()
                .filter(s -> name.equals(s.getName()))
                .findFirst().orElse(null);

        Map<String, Object> result = new HashMap<>();
        if (skill != null) {
            result.put("success", true);
            result.put("skill", skill);
        } else {
            result.put("success", false);
            result.put("error", "技能不存在: " + name);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 安装技能
     *
     * POST /api/skills/install
     * Body: { "name": "skill-name", "content": "markdown content" }
     */
    @PostMapping("/install")
    public ResponseEntity<Map<String, Object>> installSkill(@RequestBody Map<String, String> body) {
        String name = body.get("name");
        String content = body.get("content");

        log.info("[SKILL-API] 安装技能: {}", name);

        SkillManager.SkillInstallResult installResult = skillManager.installSkill(name, content);

        Map<String, Object> result = new HashMap<>();
        if (installResult.isSuccess()) {
            result.put("success", true);
            result.put("name", installResult.getName());
            result.put("version", installResult.getVersion());
            result.put("message", "技能安装成功");
        } else {
            result.put("success", false);
            result.put("error", installResult.getError());
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 卸载技能
     *
     * DELETE /api/skills/{name}
     */
    @DeleteMapping("/{name}")
    public ResponseEntity<Map<String, Object>> uninstallSkill(@PathVariable String name) {
        log.info("[SKILL-API] 卸载技能: {}", name);

        boolean success = skillManager.uninstallSkill(name);

        Map<String, Object> result = new HashMap<>();
        result.put("success", success);
        if (!success) {
            result.put("error", "技能不存在或卸载失败: " + name);
        } else {
            result.put("message", "技能已卸载");
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 启用技能
     *
     * PUT /api/skills/{name}/enable
     */
    @PutMapping("/{name}/enable")
    public ResponseEntity<Map<String, Object>> enableSkill(@PathVariable String name) {
        log.info("[SKILL-API] 启用技能: {}", name);

        boolean success = skillManager.enableSkill(name);

        Map<String, Object> result = new HashMap<>();
        result.put("success", success);
        if (!success) {
            result.put("error", "技能不存在: " + name);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 禁用技能
     *
     * PUT /api/skills/{name}/disable
     */
    @PutMapping("/{name}/disable")
    public ResponseEntity<Map<String, Object>> disableSkill(@PathVariable String name) {
        log.info("[SKILL-API] 禁用技能: {}", name);

        boolean success = skillManager.disableSkill(name);

        Map<String, Object> result = new HashMap<>();
        result.put("success", success);
        if (!success) {
            result.put("error", "技能不存在: " + name);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 重新扫描技能目录
     *
     * POST /api/skills/scan
     */
    @PostMapping("/scan")
    public ResponseEntity<Map<String, Object>> scanSkills() {
        log.info("[SKILL-API] 重新扫描技能目录");

        SkillRegistry registry = skillManager.getSkillRegistry();
        int count1 = registry.scanAndLoad("./src/main/resources/static/skills");
        int count2 = registry.scanAndLoad("./data/skills");

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "扫描完成");
        result.put("totalSkills", registry.size());
        return ResponseEntity.ok(result);
    }
}
