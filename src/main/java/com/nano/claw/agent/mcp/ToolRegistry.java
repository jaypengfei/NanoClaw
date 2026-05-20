package com.nano.claw.agent.mcp;

import java.util.HashMap;
import java.util.Map;

/**
 * 工具注册中心
 * <p>
 * 管理 Agent 可调用的所有工具，提供工具查找和描述生成
 *
 * @author Jason
 * @description 工具注册与查找
 * @date 2026/5/19
 */
public class ToolRegistry {

    private final Map<String, Tool> tools = new HashMap<>();

    /**
     * 注册工具
     *
     * @param tool 工具实例
     */
    public void register(Tool tool) {
        tools.put(tool.getName(), tool);
    }

    /**
     * 根据名称获取工具
     *
     * @param name 工具名称
     * @return 工具实例，不存在则返回 null
     */
    public Tool getTool(String name) {
        return tools.get(name);
    }

    /**
     * 判断工具是否已注册
     *
     * @param name 工具名称
     * @return 是否存在
     */
    public boolean hasTool(String name) {
        return tools.containsKey(name);
    }

    /**
     * 生成工具描述文本，注入到 system prompt 中
     * 格式：tool_name: description
     *
     * @return 工具描述文本
     */
    public String getToolDescriptions() {
        if (tools.isEmpty()) {
            return "当前无可用工具。";
        }
        StringBuilder sb = new StringBuilder();
        for (Tool tool : tools.values()) {
            sb.append("- ").append(tool.getName()).append(": ").append(tool.getDescription()).append("\n");
        }
        return sb.toString();
    }
}