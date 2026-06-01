package com.nano.claw.agent.core;

import com.nano.claw.llm.Model;
import com.nano.claw.llm.ModelFacade;
import com.nano.claw.llm.ModelRequest;
import com.nano.claw.llm.ModelResponse;
import com.nano.claw.messages.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 模式路由器 - 规则引擎优先 + LLM 兜底
 * <p>
 * 根据用户输入的问题特征，自动选择最合适的 Agent 模式:
 * - chat: 适合简单对话、常识问答等不需要工具的场景
 * - react: 适合需要调用工具获取信息的场景
 * - plan_and_execute: 适合复杂多步骤任务，需要全局规划的场景
 * - reflection: 适合需要高质量输出的场景，如写作、代码生成、复杂推理
 * <p>
 * 路由策略：先基于关键词+正则的规则引擎快速匹配，
 * 规则未命中时才调用 LLM 做路由判断，大幅减少 LLM 调用次数。
 *
 * @author Jason
 * @description 规则引擎优先 + LLM 兜底的Agent模式自动路由
 * @date 2026/5/20
 */
public class ModeRouter {

    private static final Logger log = LoggerFactory.getLogger(ModeRouter.class);

    private static final String ROUTER_SYSTEM_PROMPT =
            "你是一个任务路由专家。你的职责是分析用户的问题，判断应该使用哪种处理模式。\n\n"
            + "有四种模式可供选择：\n\n"
            + "1. chat - 直接对话模式\n"
            + "   适用场景：简单的日常对话、闲聊、常识问答、不需要工具或深度推理的场景\n"
            + "   特征：问题简单明确、不涉及实时数据、不需要复杂推理、纯粹的对话交互\n\n"
            + "2. react - ReAct 推理+行动模式\n"
            + "   适用场景：需要调用工具获取信息的场景（如HTTP请求、计算）、\n"
            + "单步或少量步骤即可完成的任务\n"
            + "   特征：需要外部数据、需要调用工具、目标单一\n\n"
            + "3. plan_and_execute - 先规划后执行模式\n"
            + "   适用场景：复杂的多步骤任务、需要全局规划、多个子任务有依赖关系、\n"
            + "需要制定详细执行计划\n"
            + "   特征：问题复杂、涉及多个环节、步骤间有先后顺序\n\n"
            + "4. reflection - 自我反思改进模式\n"
            + "   适用场景：需要高质量输出的任务，如文章写作、代码生成、深度分析、\n"
            + "复杂推理、需要反复打磨的回答\n"
            + "   特征：需要深度思考、对输出质量要求高、可能有多种解法\n\n"
            + "5. cronjob - 定时任务模式\n"
            + "   适用场景：用户想要设置定时任务、定期执行某个操作、周期性提醒\n"
            + "   特征：包含时间描述词（每天、每隔、每周、定时等）+ 要执行的动作\n\n"
            + "重要规则：\n"
            + "- 只输出模式名称，不要输出任何其他内容\n"
            + "- 必须且只能输出以下五个词之一: chat / react / plan_and_execute / reflection / cronjob\n"
            + "- 简单对话优先选择 chat，需要工具或复杂推理时选择其他模式\n"
            + "- 如果用户提到时间相关的定期执行需求，选择 cronjob\n"
            + "- 如果不确定，默认选择 chat";

    /**
     * 路由结果
     */
    public static class RouteResult {
        /** 路由到的模式 */
        private final String mode;
        /** 是否由规则匹配 */
        private final boolean ruleMatched;
        /** 匹配到的规则名称 */
        private final String ruleName;

        public RouteResult(String mode, boolean ruleMatched, String ruleName) {
            this.mode = mode;
            this.ruleMatched = ruleMatched;
            this.ruleName = ruleName;
        }

        public String getMode() { return mode; }
        public boolean isRuleMatched() { return ruleMatched; }
        public String getRuleName() { return ruleName; }
    }

    /**
     * 根据用户问题自动选择 Agent 模式
     * <p>
     * 路由策略：规则引擎优先匹配，未命中时降级到 LLM 路由
     *
     * @param query 用户输入的问题
     * @param model 使用的模型
     * @return 路由结果，包含模式名称和来源信息
     */
    public static RouteResult route(String query, Model model) {
        // ========== 1. 规则引擎优先匹配 ==========
        IntentRuleEngine.RouteResult ruleResult = IntentRuleEngine.match(query);
        if (ruleResult.isRuleMatched() && ruleResult.getMode() != null) {
            log.info("[ROUTER] 规则命中! 输入: {} => 模式: {} (规则: {})",
                    truncate(query), ruleResult.getMode(), ruleResult.getRuleName());
            return new RouteResult(ruleResult.getMode(), true, ruleResult.getRuleName());
        }

        // ========== 2. 规则未命中，降级到 LLM 路由 ==========
        log.info("[ROUTER] 规则未命中，降级LLM路由, 输入: {}", truncate(query));
        String mode = routeByLLM(query, model);
        return new RouteResult(mode, false, "LLM");
    }

    /**
     * 兼容旧接口：仅返回模式名称
     */
    public static String routeString(String query, Model model) {
        return route(query, model).getMode();
    }

    /**
     * LLM 路由（内部方法）
     */
    private static String routeByLLM(String query, Model model) {
        String traceId = UUID.randomUUID().toString();

        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system", ROUTER_SYSTEM_PROMPT));
        messages.add(new Message("user", query));

        ModelRequest request = new ModelRequest(model, traceId, messages);
        ModelResponse response = ModelFacade.chatCompletion(request);

        if (!response.isSuccess()) {
            log.warn("[ROUTER] LLM路由失败，降级为 chat: {}", response.getError());
            return "chat";
        }

        String mode = parseMode(response.getContent());
        log.info("[ROUTER] LLM路由结果: 输入: {} => 模式: {} (模型原始输出: {})",
                truncate(query), mode, response.getContent());
        return mode;
    }

    /**
     * 解析模型输出为模式名称
     */
    private static String parseMode(String content) {
        if (content == null || content.trim().isEmpty()) {
            return "chat";
        }

        String normalized = content.trim().toLowerCase()
                .replace("-", "_")
                .replace(" ", "_");

        if (normalized.contains("expert_panel") || normalized.contains("expertpanel") || normalized.contains("专家团")) {
            return "expert_panel";
        }
        if (normalized.contains("cronjob") || normalized.contains("cron") || normalized.contains("cron_job") || normalized.contains("定时任务")) {
            return "cronjob";
        }
        if (normalized.contains("plan_and_execute") || normalized.contains("planandexecute") || normalized.equals("plan")) {
            return "plan_and_execute";
        }
        if (normalized.contains("reflection") || normalized.contains("reflect")) {
            return "reflection";
        }
        if (normalized.contains("react")) {
            return "react";
        }
        if (normalized.contains("chat")) {
            return "chat";
        }
        // 默认 chat
        return "chat";
    }

    /** 截断日志输出 */
    private static String truncate(String text) {
        if (text == null) return "";
        return text.length() > 50 ? text.substring(0, 50) + "..." : text;
    }
}
