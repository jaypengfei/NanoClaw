package com.nano.claw.agent.expert;

import com.nano.claw.agent.common.AgentRequest;
import com.nano.claw.agent.common.AgentResponse;
import com.nano.claw.agent.common.ThinkStep;
import com.nano.claw.agent.core.Agent;
import com.nano.claw.agent.panel.AgentContext;
import com.nano.claw.agent.panel.CollaborationRequest;
import com.nano.claw.agent.panel.CollaborationResponse;
import com.nano.claw.llm.ModelFacade;
import com.nano.claw.llm.ModelRequest;
import com.nano.claw.llm.ModelResponse;
import com.nano.claw.messages.Message;

import java.util.ArrayList;
import java.util.List;

/**
 * 专家角色 Agent 基类
 * <p>
 * 所有专家角色（销售、产品、架构师、程序员、测试、PM）均继承此类。
 * 提供统一的执行框架：读取上下文 → 调用 LLM → 写回黑板 → 返回结果。
 * <p>
 * 子类只需实现：
 * - {@link #getRole()} 角色标识
 * - {@link #getRoleSystemPrompt()} 角色专属 System Prompt
 * - {@link #getRequiredContextKeys()} 需要读取的前驱角色列表
 *
 * @author Jason
 * @description Expert Panel 专家 Agent 基类
 * @date 2026/5/20
 */
public abstract class ExpertAgent extends Agent {

    /**
     * 获取角色标识（小写英文，如 sales、pm、architect）
     *
     * @return 角色标识
     */
    public abstract String getRole();

    /**
     * 获取角色展示名称（中文，用于前端展示）
     *
     * @return 角色展示名称
     */
    public abstract String getRoleDisplayName();

    /**
     * 获取角色专属 System Prompt
     * <p>
     * 定义该角色的职责、工作目标和输出格式要求
     *
     * @return System Prompt 内容
     */
    public abstract String getRoleSystemPrompt();

    /**
     * 获取该角色需要读取的前驱角色列表
     * <p>
     * 返回空列表表示无依赖（首个执行角色）
     *
     * @return 前驱角色标识列表
     */
    public abstract List<String> getRequiredContextKeys();

    /**
     * 获取角色能力描述（用于动态协作匹配）
     * <p>
     * 描述该角色能提供什么能力、擅长什么领域，供其他角色判断是否需要协作。
     *
     * @return 角色能力描述
     */
    public abstract String getCapabilities();

    /**
     * 在 AgentContext 黑板模式下执行专家任务
     * <p>
     * 主控 Agent (ExpertPanelAgent) 调用此方法驱动专家执行
     *
     * @param context      共享黑板，包含前驱专家产出和原始需求
     * @param agentRequest 原始 Agent 请求（含 model、thinkStepConsumer 等）
     * @param result       AgentResponse，用于收集 ThinkStep
     * @return 本角色产出内容（将被写入黑板）
     */
    public String executeInContext(AgentContext context, AgentRequest agentRequest, AgentResponse result) {
        // 1. 构建上下文摘要（动态模式：requiredKeys为空时读取全部已完成产出）
        List<String> requiredKeys = getRequiredContextKeys();
        String contextSummary = context.buildContextSummary(requiredKeys.isEmpty() ? null : requiredKeys);

        // 2. 构建用户 Prompt
        String userPrompt = buildUserPrompt(context.getOriginalRequest(), contextSummary);

        // 3. 记录 EXPERT_DISPATCH 思考步骤
        addThinkStep(agentRequest, result, ThinkStep.of(
                ThinkStep.Type.EXPERT_DISPATCH,
                "【" + getRoleDisplayName() + "】开始工作",
                "角色: " + getRoleDisplayName() + "\n需求: " + truncate(context.getOriginalRequest(), 100)
                        + (contextSummary.isEmpty() ? "" : "\n已参考: " + formatReferencedRoles(context, requiredKeys)),
                0
        ));

        // 4. 调用 LLM
        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system", getRoleSystemPrompt()));
        messages.add(new Message("user", userPrompt));

        ModelRequest modelRequest = new ModelRequest(agentRequest.getModel(), newTraceId(), messages);
        ModelResponse modelResponse = ModelFacade.chatCompletion(modelRequest);

        // 5. 累加 token
        result.addTokens(modelResponse.getTotalTokens());

        if (!modelResponse.isSuccess()) {
            addThinkStep(agentRequest, result, ThinkStep.of(
                    ThinkStep.Type.EXPERT_RESULT,
                    "【" + getRoleDisplayName() + "】执行失败",
                    "错误: " + modelResponse.getError(),
                    0
            ));
            return "【" + getRoleDisplayName() + "】执行失败: " + modelResponse.getError();
        }

        String output = modelResponse.getContent();

        // 6. 记录 EXPERT_RESULT 思考步骤
        addThinkStep(agentRequest, result, ThinkStep.of(
                ThinkStep.Type.EXPERT_RESULT,
                "【" + getRoleDisplayName() + "】产出完成",
                output,
                0
        ));

        return output;
    }

    /**
     * 构建用户 Prompt
     * <p>
     * 将原始需求与前驱上下文摘要组合
     *
     * @param originalRequest 用户原始需求
     * @param contextSummary  前驱专家产出摘要
     * @return 用户 Prompt
     */
    protected String buildUserPrompt(String originalRequest, String contextSummary) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 项目需求\n\n").append(originalRequest).append("\n\n");
        if (contextSummary != null && !contextSummary.trim().isEmpty()) {
            sb.append(contextSummary);
        }
        sb.append("\n请根据你的专业角色，给出详细的分析和方案。");
        return sb.toString();
    }

    /**
     * Agent.run() 标准入口（支持独立运行，不依赖 AgentContext）
     */
    @Override
    public AgentResponse run(AgentRequest agentRequest) {
        AgentResponse validation = validateRequest(agentRequest);
        if (validation != null) return validation;

        String traceId = newTraceId();
        AgentResponse result = new AgentResponse();
        result.setTraceId(traceId);

        // 构建独立运行的上下文（不读取黑板）
        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system", getRoleSystemPrompt()));
        messages.add(new Message("user", agentRequest.getQuery()));

        ModelRequest modelRequest = new ModelRequest(agentRequest.getModel(), traceId, messages);
        ModelResponse modelResponse = ModelFacade.chatCompletion(modelRequest);

        result.addTokens(modelResponse.getTotalTokens());

        if (!modelResponse.isSuccess()) {
            return AgentResponse.failure("模型调用失败: " + modelResponse.getError(), 1, traceId);
        }

        result.setAnswer(modelResponse.getContent());
        result.setSuccess(true);
        result.setLoopCount(1);
        return result;
    }

    // ==================== 工具方法 ====================

    protected String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }

    /**
     * 获取角色展示名称（根据角色标识）
     */
    protected String getRoleDisplayName(String role) {
        switch (role) {
            case "sales":      return "销售分析师";
            case "pm":         return "产品经理";
            case "architect":  return "架构师";
            case "developer":  return "开发工程师";
            case "tester":     return "测试工程师";
            case "pm_project": return "项目经理";
            default:           return role;
        }
    }

    /**
     * 在 AgentContext 黑板模式下执行专家任务（含回退交互上下文）
     * <p>
     * 当其他角色对本角色提出疑问或打回需求时，使用此方法重新执行，
     * 将回退交互信息作为额外上下文注入。
     *
     * @param context        共享黑板
     * @param agentRequest   原始请求
     * @param result         用于收集 ThinkStep
     * @param bounceRequests 回退交互请求列表（疑问/反馈）
     * @return 本角色更新后的产出内容
     */
    public String executeWithBounceContext(AgentContext context, AgentRequest agentRequest,
                                             AgentResponse result, List<CollaborationRequest> bounceRequests) {
        // 1. 构建上下文摘要
        List<String> requiredKeys = getRequiredContextKeys();
        String contextSummary = context.buildContextSummary(requiredKeys.isEmpty() ? null : requiredKeys);

        // 2. 构建回退交互信息
        StringBuilder bounceInfo = new StringBuilder();
        bounceInfo.append("\n## 其他角色的反馈与疑问\n\n");
        for (CollaborationRequest req : bounceRequests) {
            bounceInfo.append("### 来自【").append(getRoleDisplayName(req.getFromRole()))
                    .append("】的");
            if (req.getRequestType() == CollaborationRequest.RequestType.QUESTION) {
                bounceInfo.append("疑问");
            } else {
                bounceInfo.append("反馈");
            }
            bounceInfo.append("\n");
            bounceInfo.append(req.getReason()).append("\n\n");
            if (req.getContextSnippet() != null && !req.getContextSnippet().isEmpty()) {
                bounceInfo.append("相关上下文:\n").append(req.getContextSnippet()).append("\n\n");
            }
        }
        bounceInfo.append("请根据以上反馈，补充或修正你的分析。\n");

        // 3. 构建用户 Prompt
        String userPrompt = buildUserPrompt(context.getOriginalRequest(), contextSummary) + bounceInfo.toString();

        // 4. 记录 EXPERT_BOUNCE 思考步骤
        addThinkStep(agentRequest, result, ThinkStep.of(
                ThinkStep.Type.EXPERT_BOUNCE,
                "【" + getRoleDisplayName() + "】回应反馈",
                "收到 " + bounceRequests.size() + " 条反馈/疑问，正在更新分析...",
                0
        ));

        // 5. 调用 LLM
        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system", getRoleSystemPrompt()));
        messages.add(new Message("user", userPrompt));

        ModelRequest modelRequest = new ModelRequest(agentRequest.getModel(), newTraceId(), messages);
        ModelResponse modelResponse = ModelFacade.chatCompletion(modelRequest);

        result.addTokens(modelResponse.getTotalTokens());

        if (!modelResponse.isSuccess()) {
            addThinkStep(agentRequest, result, ThinkStep.of(
                    ThinkStep.Type.EXPERT_RESULT,
                    "【" + getRoleDisplayName() + "】回应反馈失败",
                    "错误: " + modelResponse.getError(),
                    0
            ));
            return "【" + getRoleDisplayName() + "】回应反馈失败: " + modelResponse.getError();
        }

        String output = modelResponse.getContent();

        addThinkStep(agentRequest, result, ThinkStep.of(
                ThinkStep.Type.EXPERT_RESULT,
                "【" + getRoleDisplayName() + "】已更新产出",
                output,
                0
        ));

        return output;
    }

    /**
     * 评估是否应该参与项目协作
     * <p>
     * 当收到协作请求时，该角色根据原始需求、请求方原因和当前上下文，
     * 通过 LLM 判断自身是否需要参与。默认实现使用 LLM 决策，子类可覆写。
     *
     * @param originalRequest 原始项目需求
     * @param requesterRole   请求发起方角色标识
     * @param requestReason   请求方给出的协作理由
     * @param context         当前共享上下文
     * @param model           使用的模型
     * @return 协作响应（接受或拒绝）
     */
    public CollaborationResponse evaluateRelevance(String originalRequest, String requesterRole,
                                                      String requestReason, AgentContext context,
                                                      com.nano.claw.llm.Model model) {
        String evalSystemPrompt = "你是一个专家角色（" + getRoleDisplayName() + "），你的能力是：" + getCapabilities() + "\n\n"
                + "现在有其他角色请求你参与一个项目协作。请根据项目需求和协作理由，"
                + "判断你的专业知识是否对此项目有必要。\n"
                + "如果项目确实需要你的专业领域知识，请回答 ACCEPT；"
                + "如果项目不需要你的专业领域，请回答 DECLINE。\n\n"
                + "你必须严格按照以下格式回复：\n"
                + "DECISION: ACCEPT 或 DECLINE\n"
                + "REASON: 你的判断理由";

        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("## 项目需求\n\n").append(originalRequest).append("\n\n");

        // 已完成的产出物摘要
        java.util.Map<String, String> artifacts = context.getAllArtifacts();
        if (!artifacts.isEmpty()) {
            userPrompt.append("## 已有产出\n\n");
            for (java.util.Map.Entry<String, String> entry : artifacts.entrySet()) {
                String snippet = entry.getValue();
                if (snippet.length() > 300) {
                    snippet = snippet.substring(0, 300) + "...";
                }
                userPrompt.append("- ").append(entry.getKey()).append(": ").append(snippet).append("\n");
            }
            userPrompt.append("\n");
        }

        userPrompt.append("## 协作请求\n\n");
        userPrompt.append("请求方: ").append(requesterRole).append("\n");
        userPrompt.append("请求理由: ").append(requestReason).append("\n\n");
        userPrompt.append("请判断你是否需要参与此项目。");

        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system", evalSystemPrompt));
        messages.add(new Message("user", userPrompt.toString()));

        ModelRequest modelRequest = new ModelRequest(model, newTraceId(), messages);
        ModelResponse modelResponse = ModelFacade.chatCompletion(modelRequest);

        if (!modelResponse.isSuccess()) {
            return CollaborationResponse.accept(getRole(), requesterRole,
                    "无法评估（LLM调用失败），默认接受", null);
        }

        String content = modelResponse.getContent().trim();
        boolean accept = content.contains("ACCEPT");
        String reason = extractReason(content);

        if (accept) {
            return CollaborationResponse.accept(getRole(), requesterRole, reason, null);
        } else {
            return CollaborationResponse.decline(getRole(), requesterRole, reason, null);
        }
    }

    /**
     * 执行后声明需要哪些角色协作
     * <p>
     * 角色完成自身工作后，通过 LLM 判断还需要哪些角色的配合。
     * 返回协作请求列表，主控 Agent 根据此列表动态扩展协作图。
     *
     * @param output         本角色的产出内容
     * @param context        当前共享上下文
     * @param availableRoles 所有可用角色及其能力描述
     * @param model          使用的模型
     * @return 需要协作的角色请求列表
     */
    public List<CollaborationRequest> declareCollaborationNeeds(String output, AgentContext context,
                                                                  java.util.Map<String, String> availableRoles,
                                                                  com.nano.claw.llm.Model model) {
        if (availableRoles == null || availableRoles.isEmpty()) {
            return new ArrayList<>();
        }

        // 过滤掉已激活和已拒绝的角色
        java.util.Map<String, String> candidateRoles = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, String> entry : availableRoles.entrySet()) {
            if (!context.isRoleActivated(entry.getKey())) {
                candidateRoles.put(entry.getKey(), entry.getValue());
            }
        }
        if (candidateRoles.isEmpty()) {
            return new ArrayList<>();
        }

        String collabSystemPrompt = "你是一个专家角色（" + getRoleDisplayName() + "），刚完成了项目分析。\n"
                + "请根据你的分析结果，判断还需要哪些角色的配合才能完成完整的项目方案。\n"
                + "只选择确实需要的角色，不需要的角色不要选择。\n\n"
                + "请严格按照以下 JSON 数组格式回复（不要其他内容）：\n"
                + "[{\"role\": \"角色标识\", \"reason\": \"需要协作的理由\"}]\n"
                + "如果不需要其他角色协作，返回空数组 []";

        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("## 项目需求\n\n").append(context.getOriginalRequest()).append("\n\n");
        userPrompt.append("## 你的分析产出\n\n");
        String outputSnippet = output.length() > 500 ? output.substring(0, 500) + "..." : output;
        userPrompt.append(outputSnippet).append("\n\n");
        userPrompt.append("## 可选协作角色\n\n");
        for (java.util.Map.Entry<String, String> entry : candidateRoles.entrySet()) {
            userPrompt.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
        }
        userPrompt.append("\n请判断你需要哪些角色协作。");

        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system", collabSystemPrompt));
        messages.add(new Message("user", userPrompt.toString()));

        ModelRequest modelRequest = new ModelRequest(model, newTraceId(), messages);
        ModelResponse modelResponse = ModelFacade.chatCompletion(modelRequest);

        if (!modelResponse.isSuccess()) {
            return new ArrayList<>();
        }

        return parseCollaborationRequests(modelResponse.getContent(), candidateRoles);
    }

    /**
     * 执行后声明是否需要向已完成角色提问或反馈
     * <p>
     * 角色完成工作后，可以判断是否需要向已完成的角色提出疑问或打回需求。
     * 例如：开发角色对产品需求有疑问，可以向产品经理提问。
     *
     * @param output  本角色的产出内容
     * @param context 当前共享上下文
     * @param model   使用的模型
     * @return 回退交互请求列表（疑问/反馈）
     */
    public List<CollaborationRequest> declareBounceNeeds(String output, AgentContext context,
                                                           com.nano.claw.llm.Model model) {
        if (context.getCompletedRoles().isEmpty()) {
            return new ArrayList<>();
        }

        String bounceSystemPrompt = "你是一个专家角色（" + getRoleDisplayName() + "），刚完成了项目分析。\n"
                + "请根据你的分析结果，判断是否对其他已完成角色的产出有疑问或需要反馈修改。\n"
                + "只有确实存在疑问或需要修正时才发起，否则不需要。\n\n"
                + "请严格按照以下 JSON 数组格式回复（不要其他内容）：\n"
                + "[{\"role\": \"角色标识\", \"type\": \"QUESTION\"或\"FEEDBACK\", \"reason\": \"疑问或反馈内容\"}]\n"
                + "如果没有疑问或反馈，返回空数组 []";

        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("## 项目需求\n\n").append(context.getOriginalRequest()).append("\n\n");
        userPrompt.append("## 你的分析产出\n\n");
        String outputSnippet = output.length() > 500 ? output.substring(0, 500) + "..." : output;
        userPrompt.append(outputSnippet).append("\n\n");
        userPrompt.append("## 其他已完成的角色产出\n\n");
        for (String completedRole : context.getCompletedRoles()) {
            if (completedRole.equals(getRole())) continue;
            String artifact = context.read(completedRole);
            if (artifact != null) {
                String snippet = artifact.length() > 300 ? artifact.substring(0, 300) + "..." : artifact;
                userPrompt.append("### ").append(completedRole).append("\n").append(snippet).append("\n\n");
            }
        }
        userPrompt.append("请判断你是否需要向其他角色提出疑问或反馈。");

        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system", bounceSystemPrompt));
        messages.add(new Message("user", userPrompt.toString()));

        ModelRequest modelRequest = new ModelRequest(model, newTraceId(), messages);
        ModelResponse modelResponse = ModelFacade.chatCompletion(modelRequest);

        if (!modelResponse.isSuccess()) {
            return new ArrayList<>();
        }

        return parseBounceRequests(modelResponse.getContent(), context);
    }

    // ==================== 私有工具方法 ====================

    /**
     * 格式化已参考角色列表
     */
    private String formatReferencedRoles(AgentContext context, List<String> requiredKeys) {
        if (requiredKeys == null || requiredKeys.isEmpty()) {
            return String.join(", ", context.getCompletedRoles());
        }
        return String.join(", ", requiredKeys);
    }

    /**
     * 从 LLM 响应中提取 REASON 字段
     */
    private String extractReason(String content) {
        String[] lines = content.split("\n");
        for (String line : lines) {
            if (line.trim().startsWith("REASON:")) {
                return line.trim().substring("REASON:".length()).trim();
            }
        }
        return content.length() > 100 ? content.substring(0, 100) : content;
    }

    /**
     * 解析协作请求 JSON
     */
    private List<CollaborationRequest> parseCollaborationRequests(String content, java.util.Map<String, String> candidateRoles) {
        List<CollaborationRequest> requests = new ArrayList<>();
        try {
            String json = content.trim();
            if (json.startsWith("```")) {
                int start = json.indexOf('[');
                int end = json.lastIndexOf(']');
                if (start >= 0 && end > start) {
                    json = json.substring(start, end + 1);
                }
            }
            if (!json.contains("[")) return requests;
            json = json.substring(json.indexOf('[') + 1);
            if (json.contains("]")) json = json.substring(0, json.lastIndexOf(']'));

            String[] items = json.split("\\}\\s*,\\s*\\{");
            for (String item : items) {
                item = item.replace("{",  "").replace("}", "").trim();
                String role = extractJsonField(item, "role");
                String reason = extractJsonField(item, "reason");
                if (role != null && candidateRoles.containsKey(role)) {
                    requests.add(CollaborationRequest.collaborate(
                            getRole(), role, reason != null ? reason : "需要协作", ""));
                }
            }
        } catch (Exception e) {
            // JSON 解析失败，忽略
        }
        return requests;
    }

    /**
     * 解析回退交互请求 JSON
     */
    private List<CollaborationRequest> parseBounceRequests(String content, AgentContext context) {
        List<CollaborationRequest> requests = new ArrayList<>();
        try {
            String json = content.trim();
            if (json.startsWith("```")) {
                int start = json.indexOf('[');
                int end = json.lastIndexOf(']');
                if (start >= 0 && end > start) {
                    json = json.substring(start, end + 1);
                }
            }
            if (!json.contains("[")) return requests;
            json = json.substring(json.indexOf('[') + 1);
            if (json.contains("]")) json = json.substring(0, json.lastIndexOf(']'));

            String[] items = json.split("\\}\\s*,\\s*\\{");
            for (String item : items) {
                item = item.replace("{",  "").replace("}", "").trim();
                String role = extractJsonField(item, "role");
                String type = extractJsonField(item, "type");
                String reason = extractJsonField(item, "reason");
                if (role != null && context.isRoleCompleted(role) && reason != null) {
                    CollaborationRequest.RequestType requestType = "QUESTION".equalsIgnoreCase(type)
                            ? CollaborationRequest.RequestType.QUESTION
                            : CollaborationRequest.RequestType.FEEDBACK;
                    requests.add(new CollaborationRequest(getRole(), role, requestType, reason, ""));
                }
            }
        } catch (Exception e) {
            // JSON 解析失败，忽略
        }
        return requests;
    }

    /**
     * 从 JSON 对象字符串中提取字段值
     */
    private String extractJsonField(String json, String field) {
        String pattern = "\"" + field + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return null;
        int colonIdx = json.indexOf(':', idx + pattern.length());
        if (colonIdx < 0) return null;
        int startQuote = json.indexOf('"', colonIdx + 1);
        if (startQuote < 0) return null;
        int endQuote = json.indexOf('"', startQuote + 1);
        if (endQuote < 0) return null;
        return json.substring(startQuote + 1, endQuote);
    }
}
