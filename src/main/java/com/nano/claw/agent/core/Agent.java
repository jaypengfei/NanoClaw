package com.nano.claw.agent.core;

import com.nano.claw.agent.common.AgentRequest;
import com.nano.claw.agent.common.AgentResponse;
import com.nano.claw.agent.common.ThinkStep;

import java.util.UUID;

/**
 * 核心的Agent定义
 * <p>
 * 提供公共方法：参数校验、Action提取、System Prompt前缀构建
 *
 * @author Jason
 * @description Agent基类，提取公共逻辑
 * @date 2026/5/16
 */
public abstract class Agent {

    /**
     * agent运行入口
     *
     * @param agentRequest 请求
     * @return 响应
     */
    public abstract AgentResponse run(AgentRequest agentRequest);

    // ==================== 公共方法（供子类复用） ====================

    /**
     * 生成链路追踪ID
     */
    protected String newTraceId() {
        return UUID.randomUUID().toString();
    }

    /**
     * 参数校验 - 检查 query 和 model 是否为空
     *
     * @param agentRequest 请求
     * @return 校验失败的响应，校验通过返回 null
     */
    protected AgentResponse validateRequest(AgentRequest agentRequest) {
        if (agentRequest == null || agentRequest.getQuery() == null
                || agentRequest.getQuery().trim().isEmpty()) {
            return AgentResponse.failure("请求参数无效：query不能为空", 0, newTraceId());
        }
        if (agentRequest.getModel() == null) {
            return AgentResponse.failure("请求参数无效：model不能为空", 0, newTraceId());
        }
        return null;
    }

    /**
     * 构建自定义 System Prompt 前缀
     * <p>
     * 如果 agentRequest 中有自定义 systemPrompt，作为前缀添加。
     * 各子类在构建完整 system prompt 时调用此方法。
     *
     * @param customPrompt 用户自定义的 system prompt
     * @return 自定义前缀文本，无自定义时返回空字符串
     */
    protected String buildCustomPromptPrefix(String customPrompt) {
        if (customPrompt != null && !customPrompt.trim().isEmpty()) {
            return customPrompt + "\n\n";
        }
        return "";
    }

    /**
     * 从模型输出中提取 Action 名称
     *
     * @param content 模型输出文本
     * @return 提取到的 Action 名称，不存在则返回 null
     */
    protected String extractAction(String content) {
        if (content == null) {
            return null;
        }
        String marker = "Action:";
        int idx = content.indexOf(marker);
        if (idx < 0) {
            return null;
        }
        int start = idx + marker.length();
        int end = content.indexOf("\n", start);
        if (end < 0) {
            end = content.length();
        }
        return content.substring(start, end).trim();
    }

    /**
     * 从模型输出中提取 Action Input
     *
     * @param content 模型输出文本
     * @return 提取到的 Action Input，不存在则返回 null
     */
    protected String extractActionInput(String content) {
        if (content == null) {
            return null;
        }
        String marker = "Action Input:";
        int idx = content.indexOf(marker);
        if (idx < 0) {
            return null;
        }
        int start = idx + marker.length();
        int end = content.indexOf("\n", start);
        if (end < 0) {
            end = content.length();
        }
        return content.substring(start, end).trim();
    }

    /**
     * 添加思考步骤并触发实时回调（SSE流式推送时使用）
     *
     * @param agentRequest 请求（可能包含回调）
     * @param result       Agent响应对象
     * @param step         思考步骤
     */
    protected void addThinkStep(AgentRequest agentRequest, AgentResponse result, ThinkStep step) {
        result.addThinkStep(step);
        if (agentRequest.getThinkStepConsumer() != null) {
            try {
                agentRequest.getThinkStepConsumer().accept(step);
            } catch (Exception e) {
                // 回调异常不影响主流程
            }
        }
    }
}
