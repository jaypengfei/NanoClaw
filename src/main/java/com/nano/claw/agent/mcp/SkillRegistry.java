package com.nano.claw.agent.mcp;

import java.util.HashMap;
import java.util.Map;

/**
 * 技能注册中心
 *
 * @author Jason
 * @description 管理和查找可用的技能
 * @date 2026/5/19
 */
public class SkillRegistry {

    private final Map<String, Skill> skills = new HashMap<>();

    /**
     * 注册技能
     */
    public void register(Skill skill) {
        skills.put(skill.getName(), skill);
    }

    /**
     * 根据名称获取技能
     */
    public Skill getSkill(String name) {
        return skills.get(name);
    }

    /**
     * 判断技能是否已注册
     */
    public boolean hasSkill(String name) {
        return skills.containsKey(name);
    }

    /**
     * 生成技能描述文本
     */
    public String getSkillDescriptions() {
        if (skills.isEmpty()) {
            return "当前无可用技能。";
        }
        StringBuilder sb = new StringBuilder();
        for (Skill skill : skills.values()) {
            sb.append("- ").append(skill.getName()).append(": ").append(skill.getDescription()).append("\n");
        }
        return sb.toString();
    }
}