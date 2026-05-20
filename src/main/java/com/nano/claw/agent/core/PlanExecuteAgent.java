package com.nano.claw.agent.core;

import com.nano.claw.agent.common.AgentRequest;
import com.nano.claw.agent.common.AgentResponse;
import com.nano.claw.agent.common.ThinkStep;
import com.nano.claw.agent.mcp.Tool;
import com.nano.claw.agent.mcp.ToolRegistry;
import com.nano.claw.agent.mcp.ToolResult;
import com.nano.claw.llm.ModelFacade;
import com.nano.claw.llm.ModelRequest;
import com.nano.claw.llm.ModelResponse;
import com.nano.claw.messages.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 基于Plan-and-Execute模式的Agent
 * <p>
 * 核心流程:
 * 1. Planning 阶段 - 大模型分析任务，生成执行计划（步骤列表）
 * 2. Execution 阶段 - 逐步执行计划中的每个步骤，调用工具获取结果
 * 3. Re-planning 阶段 - 根据执行结果，决定是否需要调整计划
 * 4. 循环直到所有步骤完成，汇总输出最终答案
 * <p>
 * 适用于：复杂的多步骤任务，需要全局规划后再执行的场景
 *
 * @author Jason
 * @description 先规划后执行的Agent模式
 * @date 2026/5/19
 */
public class PlanExecuteAgent extends Agent {

    private static final int MAX_PLAN_STEPS = 8;
    private static final int MAX_REPLAN_COUNT = 3;

    private final ToolRegistry toolRegistry;

    public PlanExecuteAgent() {
        this.toolRegistry = new ToolRegistry();
    }

    public PlanExecuteAgent(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    public void registerTool(Tool tool) {
        toolRegistry.register(tool);
    }

    @Override
    public AgentResponse run(AgentRequest agentRequest) {
        // ========== 1. 参数校验 ==========
        AgentResponse validateResult = validateRequest(agentRequest);
        if (validateResult != null) {
            return validateResult;
        }

        String traceId = newTraceId();

        int totalLoopCount = 0;
        AgentResponse result = new AgentResponse();
        result.setTraceId(traceId);

        // ========== 2. Planning 阶段 - 生成执行计划 ==========
        List<Message> planningMessages = new ArrayList<>();
        planningMessages.add(new Message("system", buildPlannerPrompt()));
        planningMessages.add(new Message("user", agentRequest.getQuery()));

        ModelRequest planRequest = new ModelRequest(agentRequest.getModel(), traceId, planningMessages);
        ModelResponse planResponse = ModelFacade.chatCompletion(planRequest);
        totalLoopCount++;

        if (!planResponse.isSuccess()) {
            return AgentResponse.failure("计划生成失败: " + planResponse.getError(), totalLoopCount, traceId);
        }

        // 累加 token 用量
        result.addTokens(planResponse.getTotalTokens());

        String planContent = planResponse.getContent();
        List<String> steps = parsePlanSteps(planContent);

        // 记录规划步骤
        addThinkStep(agentRequest, result, ThinkStep.of(ThinkStep.Type.PLANNING, "生成执行计划", planContent, 1));

        if (steps.isEmpty()) {
            result.setAnswer(planContent);
            result.setSuccess(true);
            result.setLoopCount(totalLoopCount);
            return result;
        }

        // ========== 3. Execution 阶段 - 逐步执行 ==========
        StringBuilder executionLog = new StringBuilder();
        executionLog.append("执行计划:\n");
        for (int i = 0; i < steps.size(); i++) {
            executionLog.append(i + 1).append(". ").append(steps.get(i)).append("\n");
        }
        executionLog.append("\n执行结果:\n");

        int replanCount = 0;

        for (int stepIndex = 0; stepIndex < steps.size(); stepIndex++) {
            String currentStep = steps.get(stepIndex);

            // 判断当前步骤是否需要调用工具
            String actionName = extractActionName(currentStep);
            String actionInput = extractActionInput(currentStep);

            String stepResult;
            if (actionName != null && toolRegistry.hasTool(actionName)) {
                // 执行工具
                addThinkStep(agentRequest, result, ThinkStep.of(ThinkStep.Type.EXECUTING,
                        "执行步骤 " + (stepIndex + 1) + ": " + currentStep,
                        "调用工具: " + actionName + "\n参数: " + actionInput, stepIndex + 2));
                Tool tool = toolRegistry.getTool(actionName);
                ToolResult toolResult = tool.execute(actionInput != null ? actionInput : "");
                stepResult = toolResult.toObservation();
                totalLoopCount++;
                addThinkStep(agentRequest, result, ThinkStep.of(ThinkStep.Type.OBSERVATION,
                        "步骤 " + (stepIndex + 1) + " 结果", stepResult, stepIndex + 2));
            } else {
                // 不需要工具的步骤，让模型执行推理
                addThinkStep(agentRequest, result, ThinkStep.of(ThinkStep.Type.EXECUTING,
                        "执行步骤 " + (stepIndex + 1) + ": " + currentStep,
                        "当前步骤: " + currentStep, stepIndex + 2));
                List<Message> stepMessages = new ArrayList<>();
                stepMessages.add(new Message("system", buildExecutorPrompt()));
                stepMessages.add(new Message("user",
                        "原始任务: " + agentRequest.getQuery() + "\n\n"
                                + "当前步骤: " + currentStep + "\n\n"
                                + "已完成的步骤结果:\n" + executionLog.toString() + "\n"
                                + "请执行当前步骤并给出结果。"));

                ModelRequest stepRequest = new ModelRequest(agentRequest.getModel(), traceId, stepMessages);
                ModelResponse stepResponse = ModelFacade.chatCompletion(stepRequest);
                totalLoopCount++;

                // 累加 token 用量
                result.addTokens(stepResponse.getTotalTokens());

                if (!stepResponse.isSuccess()) {
                    stepResult = "步骤执行失败: " + stepResponse.getError();
                } else {
                    stepResult = stepResponse.getContent();
                    addThinkStep(agentRequest, result, ThinkStep.of(ThinkStep.Type.OBSERVATION,
                            "步骤 " + (stepIndex + 1) + " 结果", stepResult, stepIndex + 2));
                }
            }

            executionLog.append("步骤 ").append(stepIndex + 1).append(" 结果: ")
                    .append(stepResult).append("\n");

            // ========== 4. Re-planning 检查 ==========
            if (replanCount < MAX_REPLAN_COUNT && stepIndex < steps.size() - 1) {
                ReplanDecision decision = shouldReplan(agentRequest.getModel(), traceId,
                        agentRequest.getQuery(), executionLog.toString(),
                        steps.subList(stepIndex + 1, steps.size()));
                totalLoopCount++;

                // 累加 replanning 的 token 用量
                result.addTokens(decision.tokensUsed);

                if (decision.replan) {
                    replanCount++;
                    // 重新生成剩余步骤
                    steps = new ArrayList<>(steps.subList(0, stepIndex + 1));
                    steps.addAll(decision.newSteps);
                }
            }
        }

        // ========== 5. 汇总最终答案 ==========
        List<Message> summaryMessages = new ArrayList<>();
        summaryMessages.add(new Message("system",
                "你是一个任务总结助手。根据执行计划和结果，给出简洁的最终答案。"));
        summaryMessages.add(new Message("user",
                "原始任务: " + agentRequest.getQuery() + "\n\n"
                        + executionLog.toString() + "\n"
                        + "请给出最终答案。"));

        ModelRequest summaryRequest = new ModelRequest(agentRequest.getModel(), traceId, summaryMessages);
        ModelResponse summaryResponse = ModelFacade.chatCompletion(summaryRequest);
        totalLoopCount++;

        // 累加 token 用量
        result.addTokens(summaryResponse.getTotalTokens());

        if (!summaryResponse.isSuccess()) {
            return AgentResponse.failure("答案汇总失败: " + summaryResponse.getError(), totalLoopCount, traceId);
        }

        addThinkStep(agentRequest, result, ThinkStep.of(ThinkStep.Type.SUMMARY, "汇总最终答案",
                summaryResponse.getContent(), totalLoopCount));
        result.setAnswer(summaryResponse.getContent());
        result.setSuccess(true);
        result.setLoopCount(totalLoopCount);
        return result;
    }

    // ==================== 辅助方法 ====================

    private String buildPlannerPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个任务规划专家。你的职责是将用户的需求分解为可执行的步骤列表。\n\n");
        sb.append("格式要求：每行一个步骤，以数字编号开头，例如：\n");
        sb.append("1. 第一步描述\n");
        sb.append("2. 第二步描述\n");
        sb.append("...\n\n");
        sb.append("如果某个步骤需要调用工具，请在步骤中标注，格式为：\n");
        sb.append("步骤描述 [Action: 工具名称, Action Input: 参数]\n\n");
        sb.append("可用工具:\n");
        sb.append(toolRegistry.getToolDescriptions());
        sb.append("\n规则：步骤不要超过").append(MAX_PLAN_STEPS).append("步，步骤要具体可执行。");
        return sb.toString();
    }

    private String buildExecutorPrompt() {
        return "你是一个任务执行助手。请根据给定的步骤和上下文，执行当前步骤并给出结果。";
    }

    /**
     * 解析计划步骤
     */
    private List<String> parsePlanSteps(String planContent) {
        List<String> steps = new ArrayList<>();
        if (planContent == null) return steps;

        String[] lines = planContent.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            // 匹配数字编号开头的步骤行
            if (trimmed.matches("^\\d+[.、．)]\\s*.+")) {
                // 去掉编号前缀
                int dotIdx = trimmed.indexOf('.');
                if (dotIdx < 0) dotIdx = trimmed.indexOf('、');
                if (dotIdx < 0) dotIdx = trimmed.indexOf('．');
                if (dotIdx < 0) dotIdx = trimmed.indexOf(')');
                if (dotIdx >= 0) {
                    steps.add(trimmed.substring(dotIdx + 1).trim());
                }
            }
        }
        return steps;
    }

    /**
     * 从步骤描述中提取 Action 名称
     */
    private String extractActionName(String step) {
        int idx = step.indexOf("[Action:");
        if (idx < 0) idx = step.indexOf("Action:");
        if (idx < 0) return null;

        int start = step.indexOf(":", idx) + 1;
        int commaIdx = step.indexOf(",", start);
        int bracketIdx = step.indexOf("]", start);
        int end = commaIdx > 0 ? commaIdx : (bracketIdx > 0 ? bracketIdx : step.length());
        return step.substring(start, end).trim();
    }

    /**
     * 从步骤描述中提取 Action Input
     */
    protected String extractActionInput(String step) {
        int idx = step.indexOf("Action Input:");
        if (idx < 0) {
            return null;
        }

        int start = step.indexOf(":", idx) + 1;
        int bracketIdx = step.indexOf("]", start);
        int end = bracketIdx > 0 ? bracketIdx : step.length();
        return step.substring(start, end).trim();
    }

    /**
     * 判断是否需要重新规划
     */
    private ReplanDecision shouldReplan(com.nano.claw.llm.Model model, String traceId,
                                         String originalTask, String executionLog,
                                         List<String> remainingSteps) {
        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system",
                "你是一个任务规划评估专家。判断当前执行结果是否需要调整后续计划。\n"
                        + "如果执行结果偏离预期，输出 REPLAN 并给出新的步骤。\n"
                        + "如果一切正常，输出 CONTINUE。"));
        messages.add(new Message("user",
                "原始任务: " + originalTask + "\n\n"
                        + "执行进度:\n" + executionLog + "\n\n"
                        + "剩余计划:\n" + formatSteps(remainingSteps) + "\n\n"
                        + "是否需要重新规划？"));

        ModelRequest request = new ModelRequest(model, traceId, messages);
        ModelResponse response = ModelFacade.chatCompletion(request);

        // 累加 token 用量（replanning 调用由父方法 result 累加，这里不直接访问 result）
        // 注意：shouldReplan 是私有方法，token 累加在外层调用处处理

        if (!response.isSuccess() || response.getContent() == null) {
            return new ReplanDecision(false, null, response.getTotalTokens());
        }

        String content = response.getContent().trim();
        if (content.toUpperCase().contains("REPLAN") && !content.toUpperCase().contains("CONTINUE")) {
            List<String> newSteps = parsePlanSteps(content);
            return new ReplanDecision(true, newSteps, response.getTotalTokens());
        }

        return new ReplanDecision(false, null, response.getTotalTokens());
    }

    private String formatSteps(List<String> steps) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < steps.size(); i++) {
            sb.append(i + 1).append(". ").append(steps.get(i)).append("\n");
        }
        return sb.toString();
    }

    /**
     * 重新规划决策
     */
    private static class ReplanDecision {
        boolean replan;
        List<String> newSteps;
        int tokensUsed;

        ReplanDecision(boolean replan, List<String> newSteps, int tokensUsed) {
            this.replan = replan;
            this.newSteps = newSteps != null ? newSteps : new ArrayList<String>();
            this.tokensUsed = tokensUsed;
        }
    }
}