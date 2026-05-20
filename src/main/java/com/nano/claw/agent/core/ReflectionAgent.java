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
 * 基于Reflection（反思）模式的Agent
 * <p>
 * 核心流程:
 * 1. Generate - 大模型生成初始回答
 * 2. Reflect - 大模型对初始回答进行反思和批评
 * 3. Refine - 根据反思结果改进回答
 * 4. 可选：多轮反思循环
 * <p>
 * 适用于：需要高质量输出的场景，如写作、代码生成、复杂推理
 * 通过自我反思来提升回答质量，类似于人类"打草稿→检查→修改"的过程
 *
 * @author Jason
 * @description 自我反思改进的Agent模式
 * @date 2026/5/19
 */
public class ReflectionAgent extends Agent {

    /** 最大反思轮数 */
    private static final int MAX_REFLECTION_ROUNDS = 2;

    private final ToolRegistry toolRegistry;

    public ReflectionAgent() {
        this.toolRegistry = new ToolRegistry();
    }

    public ReflectionAgent(ToolRegistry toolRegistry) {
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

        int loopCount = 0;
        AgentResponse result = new AgentResponse();
        result.setTraceId(traceId);

        // ========== 2. Generate 阶段 - 生成初始回答 ==========
        String systemPrompt = buildGeneratorPrompt(agentRequest.getSystemPrompt());
        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system", systemPrompt));
        messages.add(new Message("user", agentRequest.getQuery()));

        ModelRequest genRequest = new ModelRequest(agentRequest.getModel(), traceId, messages);
        ModelResponse genResponse = ModelFacade.chatCompletion(genRequest);
        loopCount++;

        if (!genResponse.isSuccess()) {
            return AgentResponse.failure("初始生成失败: " + genResponse.getError(), loopCount, traceId);
        }

        // 累加 token 用量
        result.addTokens(genResponse.getTotalTokens());

        String currentAnswer = genResponse.getContent();
        messages.add(new Message("assistant", currentAnswer));
        // 记录初始答案
        addThinkStep(agentRequest, result, ThinkStep.of(ThinkStep.Type.THOUGHT, "初始答案", currentAnswer, loopCount));

        // ========== 3. Reflection 循环 ==========
        for (int round = 0; round < MAX_REFLECTION_ROUNDS; round++) {

            // 3.1 Reflect 阶段 - 反思当前回答
            List<Message> reflectMessages = new ArrayList<>();
            reflectMessages.add(new Message("system", buildReflectionPrompt()));
            reflectMessages.add(new Message("user",
                    "原始问题: " + agentRequest.getQuery() + "\n\n"
                            + "当前回答: " + currentAnswer + "\n\n"
                            + "请对以上回答进行反思，指出不足之处和改进建议。"));

            ModelRequest reflectRequest = new ModelRequest(agentRequest.getModel(), traceId, reflectMessages);
            ModelResponse reflectResponse = ModelFacade.chatCompletion(reflectRequest);
            loopCount++;

            if (!reflectResponse.isSuccess()) {
                // 反思失败不影响已有答案，直接返回当前答案
                result.addTokens(reflectResponse.getTotalTokens());
                break;
            }

            // 累加 token 用量
            result.addTokens(reflectResponse.getTotalTokens());

            String reflection = reflectResponse.getContent();
            // 记录反思内容
            addThinkStep(agentRequest, result, ThinkStep.of(ThinkStep.Type.REFLECTING,
                    "第 " + (round + 1) + " 轮反思", reflection, loopCount));

            // 3.2 检查反思结果是否需要改进
            if (isSatisfactory(reflection)) {
                // 回答已经令人满意，不需要继续反思
                break;
            }

            // 3.3 Refine 阶段 - 根据反思改进回答
            // 如果反思中包含工具调用需求，先执行工具
            String toolObservations = executeToolsFromReflection(agentRequest.getModel(), traceId,
                    agentRequest.getQuery(), currentAnswer, reflection);
            loopCount++;

            List<Message> refineMessages = new ArrayList<>();
            refineMessages.add(new Message("system", buildRefinerPrompt()));
            refineMessages.add(new Message("user",
                    "原始问题: " + agentRequest.getQuery() + "\n\n"
                            + "当前回答: " + currentAnswer + "\n\n"
                            + "反思意见: " + reflection + "\n\n"
                            + (toolObservations != null ? "工具补充信息: " + toolObservations + "\n\n" : "")
                            + "请根据反思意见和补充信息，改进你的回答。"));

            ModelRequest refineRequest = new ModelRequest(agentRequest.getModel(), traceId, refineMessages);
            ModelResponse refineResponse = ModelFacade.chatCompletion(refineRequest);
            loopCount++;

            if (refineResponse.isSuccess()) {
                currentAnswer = refineResponse.getContent();
                // 记录改进后的回答
                addThinkStep(agentRequest, result, ThinkStep.of(ThinkStep.Type.REFINING,
                        "第 " + (round + 1) + " 轮改进回答", currentAnswer, loopCount));
            }

            // 累加 token 用量
            result.addTokens(refineResponse.getTotalTokens());
        }

        result.setAnswer(currentAnswer);
        result.setSuccess(true);
        result.setLoopCount(loopCount);
        return result;
    }

    // ==================== 辅助方法 ====================

    /**
     * 构建初始生成 prompt
     */
    private String buildGeneratorPrompt(String customPrompt) {
        StringBuilder sb = new StringBuilder();
        sb.append(buildCustomPromptPrefix(customPrompt));
        sb.append("你是一个专业的助手。请给出详细、准确的回答。\n");
        sb.append("如果你需要借助工具获取信息，请使用以下格式:\n");
        sb.append("Action: <工具名称>\n");
        sb.append("Action Input: <参数>\n\n");
        sb.append("可用工具:\n");
        sb.append(toolRegistry.getToolDescriptions());
        return sb.toString();
    }

    /**
     * 构建反思 prompt
     */
    private String buildReflectionPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个严格的评审专家。你的职责是对回答进行批评性审查。\n\n");
        sb.append("请从以下维度评估回答:\n");
        sb.append("1. 准确性 - 回答中的事实是否正确\n");
        sb.append("2. 完整性 - 是否完整回答了问题\n");
        sb.append("3. 清晰度 - 表达是否清晰有条理\n");
        sb.append("4. 深度 - 是否提供了足够的细节和推理\n\n");
        sb.append("输出格式:\n");
        sb.append("- 如果回答已经足够好，输出: SATISFACTORY\n");
        sb.append("- 如果需要改进，指出具体问题和改进建议\n");
        return sb.toString();
    }

    /**
     * 构建改进 prompt
     */
    private String buildRefinerPrompt() {
        return "你是一个改进专家。根据反思意见和补充信息，改进原始回答。\n"
                + "保持原有优点，修正指出的问题，补充缺失的信息。\n"
                + "直接输出改进后的完整回答，不要说明你做了哪些修改。";
    }

    /**
     * 判断反思结果是否表示回答已令人满意
     */
    private boolean isSatisfactory(String reflection) {
        if (reflection == null) return true;
        // 检查是否包含满意标记
        return reflection.toUpperCase().contains("SATISFACTORY")
                || reflection.contains("已足够好")
                || reflection.contains("无需改进")
                || reflection.contains("没有明显问题");
    }

    /**
     * 从反思结果中提取工具调用需求并执行
     */
    private String executeToolsFromReflection(com.nano.claw.llm.Model model, String traceId,
                                               String query, String currentAnswer, String reflection) {
        String actionName = extractAction(reflection);
        String actionInputStr = extractActionInput(reflection);

        if (actionName == null || !toolRegistry.hasTool(actionName)) {
            return null;
        }

        Tool tool = toolRegistry.getTool(actionName);
        ToolResult toolResult = tool.execute(actionInputStr != null ? actionInputStr : "");

        return "通过调用工具 " + actionName + " 获取的信息: " + toolResult.toObservation();
    }
}