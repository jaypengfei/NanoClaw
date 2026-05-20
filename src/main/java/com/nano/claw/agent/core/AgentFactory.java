package com.nano.claw.agent.core;

import com.nano.claw.agent.mcp.ToolRegistry;

/**
 * Agent 工厂 - 根据模式类型创建对应的 Agent 实例
 *
 * @author Jason
 * @description Agent创建工厂
 * @date 2026/5/19
 */
public class AgentFactory {

    /**
     * Agent 模式枚举
     */
    public enum AgentMode {
        /** ReAct 推理+行动模式 */
        REACT,
        /** 先规划后执行模式 */
        PLAN_AND_EXECUTE,
        /** 自我反思改进模式 */
        REFLECTION,
        /** Chat 直接对话模式 */
        CHAT,
        /** 定时任务模式 */
        CRONJOB
    }

    /**
     * 根据模式创建 Agent（使用共享的 ToolRegistry 和 SkillRegistry）
     *
     * @param mode          Agent模式
     * @param toolRegistry  工具注册中心
     * @return Agent实例
     */
    public static Agent create(AgentMode mode, ToolRegistry toolRegistry) {
        switch (mode) {
            case REACT:
                return new AgentLoop(toolRegistry);
            case PLAN_AND_EXECUTE:
                return new PlanExecuteAgent(toolRegistry);
            case REFLECTION:
                return new ReflectionAgent(toolRegistry);
            case CHAT:
                return new ChatAgent();
            case CRONJOB:
                return new ChatAgent();  // cronjob复用ChatAgent，具体逻辑在ChatFlow中处理
            default:
                return new AgentLoop(toolRegistry);
        }
    }

    /**
     * 根据模式名称创建 Agent
     *
     * @param modeName      模式名称（不区分大小写）
     * @param toolRegistry  工具注册中心
     * @return Agent实例
     */
    public static Agent create(String modeName, ToolRegistry toolRegistry) {
        AgentMode mode = parseMode(modeName);
        return create(mode, toolRegistry);
    }

    /**
     * 创建默认的 ReAct Agent
     */
    public static Agent createDefault(ToolRegistry toolRegistry) {
        return new AgentLoop(toolRegistry);
    }

    private static AgentMode parseMode(String modeName) {
        if (modeName == null) {
            return AgentMode.REACT;
        }
        switch (modeName.toUpperCase().replace("-", "_").replace(" ", "_")) {
            case "REACT":
                return AgentMode.REACT;
            case "PLAN_AND_EXECUTE":
            case "PLANANDEXECUTE":
            case "PLAN":
                return AgentMode.PLAN_AND_EXECUTE;
            case "REFLECTION":
            case "REFLECT":
                return AgentMode.REFLECTION;
            case "CHAT":
                return AgentMode.CHAT;
            case "CRONJOB":
            case "CRON_JOB":
            case "CRON":
                return AgentMode.CRONJOB;
            default:
                return AgentMode.REACT;
        }
    }
}