package com.nano.claw.agent.core;

import com.nano.claw.agent.common.AgentRequest;
import com.nano.claw.agent.common.AgentResponse;
import com.nano.claw.agent.common.ThinkStep;

/**
 * 核心的Agent定义
 *
 * @author Jason
 * @description TODO
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
