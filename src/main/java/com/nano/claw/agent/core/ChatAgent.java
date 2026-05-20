package com.nano.claw.agent.core;

import com.nano.claw.agent.common.AgentRequest;
import com.nano.claw.agent.common.AgentResponse;
import com.nano.claw.agent.common.ThinkStep;
import com.nano.claw.llm.ModelFacade;
import com.nano.claw.llm.ModelRequest;
import com.nano.claw.llm.ModelResponse;
import com.nano.claw.messages.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Chat 模式 Agent - 模型直接回答，不经过任何循环推理
 * <p>
 * 适用于：简单的日常对话、闲聊、知识问答等场景
 * 直接将用户问题发送给大模型，获取回答后原样返回，
 * 不做任何工具调用、规划或反思。
 *
 * @author Jason
 * @description 模型直接回答的Chat模式
 * @date 2026/5/19
 */
public class ChatAgent extends Agent {

    @Override
    public AgentResponse run(AgentRequest agentRequest) {
        // ========== 1. 参数校验 ==========
        AgentResponse validateResult = validateRequest(agentRequest);
        if (validateResult != null) {
            return validateResult;
        }

        String traceId = newTraceId();

        AgentResponse result = new AgentResponse();
        result.setTraceId(traceId);

        // ========== 2. 构建 system prompt ==========
        String systemPrompt = buildSystemPrompt(agentRequest.getSystemPrompt());

        // ========== 3. 直接调用大模型 ==========
        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system", systemPrompt));
        messages.add(new Message("user", agentRequest.getQuery()));

        // 记录思考步骤
        addThinkStep(agentRequest, result, ThinkStep.of(ThinkStep.Type.CHAT,
                "直接对话", agentRequest.getQuery(), 1));

        ModelRequest modelRequest = new ModelRequest(agentRequest.getModel(), traceId, messages);
        ModelResponse modelResponse = ModelFacade.chatCompletion(modelRequest);

        if (!modelResponse.isSuccess()) {
            return AgentResponse.failure("模型调用失败: " + modelResponse.getError(), 1, traceId);
        }

        // 累加 token 用量
        result.addTokens(modelResponse.getTotalTokens());

        String answer = modelResponse.getContent();

        // 记录回答步骤
        addThinkStep(agentRequest, result, ThinkStep.of(ThinkStep.Type.CHAT,
                "模型回答", answer, 1));

        result.setAnswer(answer);
        result.setSuccess(true);
        result.setLoopCount(1);
        return result;
    }

    /**
     * 构建 system prompt
     *
     * @param customPrompt 用户自定义的 system prompt，不为空时作为前缀
     * @return 完整的 system prompt
     */
    private String buildSystemPrompt(String customPrompt) {
        StringBuilder sb = new StringBuilder();

        sb.append(buildCustomPromptPrefix(customPrompt));

        sb.append("你是一个智能助手。请直接回答用户的问题，给出清晰、准确、有用的回答。");
        return sb.toString();
    }
}
