package com.nano.claw.agent.mcp;

/**
 * Agent 可调用的工具接口
 * <p>
 * 所有 Agent 工具都需要实现此接口，注册到 ToolRegistry 后可被 ReAct 循环调用
 *
 * @author Jason
 * @description 工具定义接口
 * @date 2026/5/19
 */
public interface Tool {

    /**
     * 工具名称，用于 ReAct 循环中 Action 的匹配
     *
     * @return 工具名称
     */
    String getName();

    /**
     * 工具描述，会注入到 system prompt 中供大模型选择使用
     *
     * @return 工具描述
     */
    String getDescription();

    /**
     * 执行工具逻辑
     *
     * @param input 工具输入参数（JSON 字符串）
     * @return 工具执行结果
     */
    ToolResult execute(String input);
}