package com.nano.claw.agent.core;

import com.nano.claw.agent.common.AgentRequest;
import com.nano.claw.agent.common.AgentResponse;
import com.nano.claw.agent.common.ThinkStep;
import com.nano.claw.agent.mcp.Skill;
import com.nano.claw.agent.mcp.SkillManager;
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
 * 基于ReAct模式的循环Agent
 * <p>
 * ReAct(Reasoning + Acting) 模式核心流程:
 * <ol>
 *   <li>Thought - 大模型推理下一步该做什么</li>
 *   <li>Action - 执行工具调用</li>
 *   <li>Observation - 观察工具返回结果</li>
 *   <li>循环直到产生 Final Answer</li>
 * </ol>
 *
 * @author Jason
 * @description 基于ReAct模式的Agent推理循环
 * @date 2026/5/16
 */
public class AgentLoop extends Agent {

    /**
     * 最大迭代轮数
     */
    private static final int MAX_LOOP_STEPS = 5;

    /**
     * ReAct 格式标记
     */
    private static final String FINAL_ANSWER_MARKER = "Final Answer:";
    private static final String ACTION_MARKER = "Action:";
    private static final String ACTION_INPUT_MARKER = "Action Input:";

    /**
     * 工具注册中心
     */
    private final ToolRegistry toolRegistry;

    /**
     * 技能管理器（可选）
     */
    private SkillManager skillManager;

    /** 匹配到的活跃技能列表，会在 run 时自动选择 */
    private List<Skill> activeSkills;

    public AgentLoop() {
        this.toolRegistry = new ToolRegistry();
    }

    public AgentLoop(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /**
     * 设置技能管理器
     */
    public void setSkillManager(SkillManager skillManager) {
        this.skillManager = skillManager;
    }

    /**
     * 注册工具
     *
     * @param tool 工具实例
     */
    public void registerTool(Tool tool) {
        toolRegistry.register(tool);
    }

    /**
     * 防止Agent陷入死循环:
     * # 强制终止
     * >> 最大轮数
     * >> 工具重复调用检测
     * >> 模型输出错误检测
     * # Prompt引导
     * >> 提示适当的时候可以结束，不一定非要拿到结果
     * >> 提示之前的尝试路径
     * # 工具
     * >> 返回值要明确，不要返回泛化或者无意义的结果，明确说明错误调用结果
     * >> 区分参数错误（告知大模型重试）还是API本身的问题（明确提示）
     *
     * @param agentRequest 请求
     * @return agent响应
     */
    @Override
    public AgentResponse run(AgentRequest agentRequest) {
        String traceId = newTraceId();
        AgentResponse result = new AgentResponse();

        // ========== 1. 参数校验 ==========
        AgentResponse validateResult = validateRequest(agentRequest);
        if (validateResult != null) {
            return validateResult;
        }

        // ========== 2. 组装上下文 ==========
        // 2.1 自动选择技能
        if (skillManager != null) {
            activeSkills = skillManager.autoSelectSkills(agentRequest.getQuery());
            if (!activeSkills.isEmpty()) {
                addThinkStep(agentRequest, result, ThinkStep.of(ThinkStep.Type.ROUTING,
                        "技能匹配", "自动匹配到技能: " + activeSkills.stream()
                                .map(Skill::getName).reduce((a, b) -> a + ", " + b).orElse(""), 0));
            }
        }

        String systemPrompt = buildSystemPrompt(agentRequest.getSystemPrompt());
        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system", systemPrompt));
        messages.add(new Message("user", agentRequest.getQuery()));

        // ========== 3. ReAct 循环 ==========
        int loopCount = 0;
        String lastActionName = null;
        String lastActionInput = null;
        int duplicateActionCount = 0;

        while (loopCount < MAX_LOOP_STEPS) {
            loopCount++;

            // 3.1 调用大模型
            ModelRequest modelRequest = new ModelRequest(
                    agentRequest.getModel(), traceId, messages);
            ModelResponse modelResponse = ModelFacade.chatCompletion(modelRequest);

            // 3.2 模型调用失败处理
            if (!modelResponse.isSuccess()) {
                return AgentResponse.failure("模型调用失败: " + modelResponse.getError(), loopCount, traceId);
            }

            // 累加 token 用量
            result.addTokens(modelResponse.getTotalTokens());

            String assistantContent = modelResponse.getContent();
            messages.add(new Message("assistant", assistantContent));

            // 3.3 提取 Thought 并记录
            String thought = extractThought(assistantContent);
            if (thought != null) {
                addThinkStep(agentRequest, result, ThinkStep.of(ThinkStep.Type.THOUGHT,
                        "Thought #" + loopCount, thought, loopCount));
            }

            // 3.4 检查 Final Answer
            String finalAnswer = extractFinalAnswer(assistantContent);
            if (finalAnswer != null) {
                result.setAnswer(finalAnswer);
                result.setSuccess(true);
                result.setLoopCount(loopCount);
                result.setTraceId(traceId);
                return result;
            }

            // 3.5 检查 Action
            String actionName = extractAction(assistantContent);
            String actionInput = extractActionInput(assistantContent);

            if (actionName == null) {
                messages.add(new Message("user",
                        "你的回复中没有包含 Final Answer 或 Action。请按照指定格式重新回答。\n"
                                + "如果你已经知道答案，请使用 Final Answer: <答案> 格式回答。"));
                continue;
            }

            // 3.6 重复调用检测
            if (actionName.equals(lastActionName) && actionInput != null && actionInput.equals(lastActionInput)) {
                duplicateActionCount++;
                if (duplicateActionCount >= 2) {
                    return AgentResponse.failure("Agent陷入死循环：连续重复调用工具 " + actionName
                            + "，已达最大重复次数。当前推理内容: " + assistantContent, loopCount, traceId);
                }
                messages.add(new Message("user",
                        "你已经连续调用了同一个工具和相同的参数，请尝试其他方式，"
                                + "或者使用 Final Answer: <答案> 给出当前已知的结论。"));
                continue;
            }
            lastActionName = actionName;
            lastActionInput = actionInput;
            duplicateActionCount = 0;

            // 3.7 记录 Action 思考步骤
            addThinkStep(agentRequest, result, ThinkStep.of(ThinkStep.Type.ACTION,
                    "调用工具: " + actionName,
                    "工具: " + actionName + "\n参数: " + (actionInput != null ? actionInput : ""), loopCount));

            // 3.8 执行工具
            String observation;
            if (skillManager != null && skillManager.isSkillAction(actionName)) {
                // 执行技能工具
                observation = skillManager.executeSkillAction(actionName, actionInput != null ? actionInput : "");
            } else if (toolRegistry.hasTool(actionName)) {
                Tool tool = toolRegistry.getTool(actionName);
                ToolResult toolResult = tool.execute(actionInput != null ? actionInput : "");
                observation = toolResult.toObservation();
            } else {
                // 构建可用工具和技能列表
                String available = toolRegistry.getToolDescriptions();
                if (skillManager != null) {
                    available += "\n" + skillManager.getSkillRegistry().getSkillDescriptions();
                }
                observation = "工具 '" + actionName + "' 不存在。可用工具:\n" + available;
            }

            // 3.9 记录 Observation
            addThinkStep(agentRequest, result, ThinkStep.of(ThinkStep.Type.OBSERVATION,
                    "Observation #" + loopCount, observation, loopCount));

            messages.add(new Message("user", "Observation: " + observation));
        }

        // ========== 4. 超过最大迭代轮数 ==========
        String lastAssistantContent = getLastAssistantContent(messages);
        String fallbackAnswer = extractFinalAnswer(lastAssistantContent);
        if (fallbackAnswer != null) {
            result.setAnswer(fallbackAnswer);
            result.setSuccess(true);
            result.setLoopCount(loopCount);
            result.setTraceId(traceId);
            return result;
        }

        return AgentResponse.failure("Agent达到最大迭代轮数(" + MAX_LOOP_STEPS
                + ")仍未产出 Final Answer。最后一次模型输出: "
                + lastAssistantContent, loopCount, traceId);
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 从模型输出中提取 Thought 内容
     */
    private String extractThought(String content) {
        if (content == null) return null;
        int idx = content.indexOf("Thought:");
        if (idx < 0) return null;
        int start = idx + "Thought:".length();
        int end = content.indexOf("\n", start);
        // 如果 Thought 后面有 Action 或 Final Answer，截断在它们之前
        int actionIdx = content.indexOf("\nAction:", start);
        int finalIdx = content.indexOf("\nFinal Answer:", start);
        int limit = content.length();
        if (actionIdx > start) limit = Math.min(limit, actionIdx);
        if (finalIdx > start) limit = Math.min(limit, finalIdx);
        if (end > start && end < limit) limit = end;
        return content.substring(start, limit).trim();
    }

    /**
     * 构建 system prompt
     * 注入 ReAct 格式指引和可用工具描述
     *
     * @param customPrompt 用户自定义的 system prompt，不为空时作为前缀
     * @return 完整的 system prompt
     */
    private String buildSystemPrompt(String customPrompt) {
        StringBuilder sb = new StringBuilder();

        // 用户自定义 prompt 作为前缀
        sb.append(buildCustomPromptPrefix(customPrompt));

        // ReAct 格式指引
        sb.append("你是一个能够推理和行动的助手。你必须严格按照以下格式回答:\n\n");
        sb.append("当你需要思考和推理时:\n");
        sb.append("Thought: <你的推理过程>\n\n");
        sb.append("当你需要调用工具时:\n");
        sb.append("Action: <工具名称>\n");
        sb.append("Action Input: <工具输入参数，JSON格式>\n\n");
        sb.append("当你已经得到最终答案时:\n");
        sb.append("Thought: <最终推理>\n");
        sb.append("Final Answer: <最终答案>\n\n");
        sb.append("重要规则:\n");
        sb.append("1. 每次回复必须包含 Thought\n");
        sb.append("2. 如果需要信息才能回答，必须使用 Action 调用工具\n");
        sb.append("3. 如果已经能够直接回答用户问题，使用 Final Answer\n");
        sb.append("4. 不要在没有 Action 的情况下凭空猜测事实\n");
        sb.append("5. 适当的时候可以结束，不一定非要使用工具\n\n");

        // 工具描述
        sb.append("可用工具:\n");
        sb.append(toolRegistry.getToolDescriptions());

        // 技能描述（如果有匹配到的技能）
        if (skillManager != null) {
            List<Skill> skills = (activeSkills != null && !activeSkills.isEmpty())
                    ? activeSkills : skillManager.getSkillRegistry().getEnabledSkills();
            if (!skills.isEmpty()) {
                sb.append("\n可用技能（使用 Action: skill_<技能名> 调用）:\n");
                for (Skill skill : skills) {
                    sb.append("- skill_").append(skill.getName()).append(": ").append(skill.getDescription());
                    if (!skill.getTriggerKeywords().isEmpty()) {
                        sb.append(" [触发词: ").append(String.join(",", skill.getTriggerKeywords())).append("]");
                    }
                    sb.append("\n");
                }
            }

            // 注入匹配技能的 prompt 模板
            String skillPrompt = skillManager.buildSkillPrompt(skills);
            if (skillPrompt != null && !skillPrompt.isEmpty()) {
                sb.append(skillPrompt);
            }
        }

        return sb.toString();
    }

    /**
     * 从模型输出中提取 Final Answer
     *
     * @param content 模型输出文本
     * @return 提取到的最终答案，不存在则返回 null
     */
    private String extractFinalAnswer(String content) {
        if (content == null) {
            return null;
        }
        int idx = content.indexOf(FINAL_ANSWER_MARKER);
        if (idx < 0) {
            return null;
        }
        return content.substring(idx + FINAL_ANSWER_MARKER.length()).trim();
    }

    /**
     * 从消息列表中获取最后一条 assistant 消息内容
     *
     * @param messages 消息列表
     * @return 最后一条 assistant 消息内容，无则返回空字符串
     */
    private String getLastAssistantContent(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message msg = messages.get(i);
            if ("assistant".equals(msg.getRole())) {
                return msg.getContent();
            }
        }
        return "";
    }
}
