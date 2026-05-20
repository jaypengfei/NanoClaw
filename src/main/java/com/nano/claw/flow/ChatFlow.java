package com.nano.claw.flow;

import com.nano.claw.agent.common.AgentRequest;
import com.nano.claw.agent.common.AgentResponse;
import com.nano.claw.agent.common.ChatRequest;
import com.nano.claw.agent.common.ChatResponse;
import com.nano.claw.agent.common.ThinkStep;
import com.nano.claw.agent.core.Agent;
import com.nano.claw.agent.core.AgentFactory;
import com.nano.claw.agent.core.ModeRouter;
import com.nano.claw.agent.mcp.CalculatorTool;
import com.nano.claw.agent.mcp.HttpTool;
import com.nano.claw.agent.mcp.ToolRegistry;
import com.nano.claw.channels.Channel;
import com.nano.claw.channels.ChannelMessage;
import com.nano.claw.channels.FeishuChannel;
import com.nano.claw.cronjob.CronJob;
import com.nano.claw.cronjob.CronJobManager;
import com.nano.claw.cronjob.CronJobParser;
import com.nano.claw.llm.Model;
import com.nano.claw.memory.ConversationMemory;
import com.nano.claw.memory.MemoryService;
import com.nano.claw.sessions.SessionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 聊天编排层 - 串联会话管理、Agent调用、通道分发
 *
 * @author Jason
 * @description 组装工作流程
 * @date 2026/5/18
 */
@Service
public class ChatFlow {

    private static final Logger log = LoggerFactory.getLogger(ChatFlow.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Value("${nanoclaw.llm.model:MINI_MAX}")
    private String defaultModelName;

    @Value("${nanoclaw.agent.mode:react}")
    private String defaultAgentMode;

    @Value("${nanoclaw.feishu.app-id:}")
    private String feishuAppId;

    @Value("${nanoclaw.feishu.app-secret:}")
    private String feishuAppSecret;

    @Value("${nanoclaw.feishu.verification-token:}")
    private String feishuVerificationToken;

    private final SessionManager sessionManager = new SessionManager();
    private final ToolRegistry toolRegistry = new ToolRegistry();
    private final Map<String, Channel> channels = new HashMap<>();

    @Resource
    private CronJobManager cronJobManager;

    @Resource
    private MemoryService memoryService;

    /** 问句结果缓存，key=问句文本，value=缓存条目 */
    private final ConcurrentHashMap<String, CacheEntry> queryCache = new ConcurrentHashMap<>();

    /** 缓存有效期：10分钟 */
    private static final long CACHE_TTL_MS = 10 * 60 * 1000L;

    /**
     * 缓存条目
     */
    private static class CacheEntry {
        final ChatResponse response;
        final long timestamp;

        CacheEntry(ChatResponse response) {
            this.response = response;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_TTL_MS;
        }
    }

    @PostConstruct
    public void init() {
        // 注册内置工具
        toolRegistry.register(new HttpTool());
        toolRegistry.register(new CalculatorTool());

        // 初始化飞书通道
        if (feishuAppId != null && !feishuAppId.isEmpty()) {
            FeishuChannel feishuChannel = new FeishuChannel(feishuAppId, feishuAppSecret, feishuVerificationToken);
            channels.put("feishu", feishuChannel);
            log.info("飞书通道初始化完成");
        }

        // 设置 ChatFlow 引用到 CronJobManager
        cronJobManager.setChatFlow(this);

        log.info("ChatFlow 初始化完成，工具数: {}，通道数: {}", toolRegistry.getToolDescriptions().split("\n").length, channels.size());
    }

    /**
     * 处理聊天请求（HTTP API 入口）
     */
    public ChatResponse chat(ChatRequest request) {
        long startTime = System.currentTimeMillis();

        // 确定会话ID
        String sessionId = request.getSessionId();
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = UUID.randomUUID().toString();
            log.info("[FLOW] 新建会话: {}", sessionId);
        } else {
            log.info("[FLOW] 继续会话: {}", sessionId);
        }

        // ========== 缓存检查：相同问句直接返回 ==========
        String cacheKey = request.getMessage().trim();
        CacheEntry cached = queryCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            log.info("[FLOW] 命中缓存，问句: {}", cacheKey);
            ChatResponse cachedResponse = cloneResponse(cached.response);
            cachedResponse.setSessionId(sessionId);
            cachedResponse.setCached(true);
            cachedResponse.setTotalTokens(0);
            cachedResponse.setDurationMs(System.currentTimeMillis() - startTime);
            return cachedResponse;
        }
        // 清理过期缓存条目（简单策略：每次查询时概率清理）
        if (queryCache.size() > 100 && Math.random() < 0.1) {
            queryCache.entrySet().removeIf(e -> e.getValue().isExpired());
        }

        // 获取或创建会话记忆
        ConversationMemory memory = sessionManager.getOrCreate(sessionId);
        log.info("[FLOW] 会话 {} 当前记忆轮数: {}", sessionId, memory.size());

        // 构建 Agent 请求
        Model model = resolveModel(defaultModelName);

        // 自动路由 Agent 模式
        String agentMode = request.getMode();
        List<ThinkStep> allSteps = new ArrayList<>();
        boolean ruleMatched = false;
        String ruleName = null;
        if (agentMode == null || agentMode.isEmpty()) {
            log.info("[FLOW] >>> 开始模式路由, 用户输入: {}", request.getMessage());
            ModeRouter.RouteResult routeResult = ModeRouter.route(request.getMessage(), model);
            agentMode = routeResult.getMode();
            ruleMatched = routeResult.isRuleMatched();
            ruleName = routeResult.getRuleName();
            log.info("[FLOW] <<< 模式路由结果: {} (来源: {})", agentMode, ruleMatched ? "规则:" + ruleName : "LLM");
            // 记录模式路由步骤
            String routingDesc = ruleMatched
                    ? "规则引擎快速匹配，命中规则: " + ruleName + "，选择模式: " + agentMode
                    : "规则未命中，LLM智能路由，选择模式: " + agentMode;
            allSteps.add(ThinkStep.of(ThinkStep.Type.ROUTING, "智能路由决策", routingDesc, 0));
        }
        log.info("[FLOW] Agent 模式: {}, 模型: {}", agentMode, model);

        // ========== cronjob 模式特殊处理：创建定时任务而非执行Agent ==========
        if ("cronjob".equals(agentMode)) {
            return handleCronJobCreation(request, sessionId, allSteps, startTime);
        }

        AgentRequest agentRequest = new AgentRequest();
        agentRequest.setQuery(request.getMessage());
        agentRequest.setModel(model);
        agentRequest.setSessionId(sessionId);

        // ========== 注入记忆上下文到 system prompt ==========
        String memoryContext = memoryService.buildSystemPromptMemorySection();
        if (memoryContext != null && !memoryContext.isEmpty()) {
            agentRequest.setSystemPrompt(memoryContext);
            log.info("[FLOW] 注入记忆上下文, 长度: {} 字符", memoryContext.length());
        }

        // 创建并运行 Agent
        log.info("[FLOW] >>> 开始执行 Agent, 用户输入: {}", request.getMessage());
        Agent agent = AgentFactory.create(agentMode, toolRegistry);
        AgentResponse agentResponse = agent.run(agentRequest);
        log.info("[FLOW] <<< Agent 执行完毕, 成功: {}, 迭代轮次: {}", agentResponse.isSuccess(), agentResponse.getLoopCount());

        if (agentResponse.isSuccess()) {
            log.info("[FLOW] Agent 回答: {}", agentResponse.getAnswer());
        } else {
            log.warn("[FLOW] Agent 失败: {}", agentResponse.getError());
        }

        // 合并路由步骤 + Agent思考步骤
        allSteps.addAll(agentResponse.getThinkSteps());

        // 保存对话记忆
        memory.addMessage(new com.nano.claw.messages.Message("user", request.getMessage()));
        if (agentResponse.isSuccess()) {
            memory.addMessage(new com.nano.claw.messages.Message("assistant", agentResponse.getAnswer()));
        }

        // ========== 持久化记忆：保存对话到文件 ==========
        if (agentResponse.isSuccess()) {
            try {
                memoryService.saveConversation(request.getMessage(), agentResponse.getAnswer());
            } catch (Exception e) {
                log.warn("[FLOW] 保存持久化记忆失败", e);
            }
        }

        // 构建响应
        ChatResponse chatResponse;
        if (agentResponse.isSuccess()) {
            chatResponse = ChatResponse.success(agentResponse.getAnswer(), sessionId, agentMode, allSteps);
        } else {
            chatResponse = ChatResponse.failure(agentResponse.getError(), sessionId);
            chatResponse.setThinkSteps(allSteps);
        }

        // 设置用时和 token 用量
        long elapsed = System.currentTimeMillis() - startTime;
        chatResponse.setDurationMs(elapsed);
        chatResponse.setTotalTokens(agentResponse.getTotalTokens());

        // 如果请求来自 IM 通道，自动回复
        if (request.getChannel() != null && channels.containsKey(request.getChannel())) {
            Channel channel = channels.get(request.getChannel());
            if (agentResponse.isSuccess()) {
                channel.sendText(sessionId, agentResponse.getAnswer());
            }
        }

        // ========== 写入缓存（仅成功的响应缓存） ==========
        if (agentResponse.isSuccess()) {
            queryCache.put(cacheKey, new CacheEntry(chatResponse));
            log.info("[FLOW] 已缓存问句: {}", cacheKey);
        }

        log.info("[FLOW] 会话 {} 处理耗时: {}ms, tokens: {}", sessionId, elapsed, agentResponse.getTotalTokens());

        return chatResponse;
    }

    /**
     * 处理飞书事件回调
     */
    public ChatResponse handleFeishuEvent(String eventPayload) {
        FeishuChannel feishuChannel = (FeishuChannel) channels.get("feishu");
        if (feishuChannel == null) {
            log.warn("飞书通道未配置");
            return ChatResponse.failure("飞书通道未配置", null);
        }

        ChannelMessage channelMsg = feishuChannel.handleEvent(eventPayload);
        if (channelMsg == null) {
            return ChatResponse.failure("解析飞书事件失败", null);
        }

        // 处理 challenge 验证
        if ("challenge".equals(channelMsg.getMsgType())) {
            // challenge 由 Controller 层直接处理
            return null;
        }

        // 构建聊天请求
        ChatRequest chatRequest = new ChatRequest();
        chatRequest.setMessage(channelMsg.getContent());
        chatRequest.setSessionId(channelMsg.getChatId());
        chatRequest.setChannel("feishu");

        // 异步处理，先回复收到
        ChatResponse response = chat(chatRequest);

        // 通过 ChatFlow.chat() 内部已自动通过 channel 回复，此处不再重复调用
        // chat() 方法会检查 request.getChannel() 并自动调用 channel.sendText()
        return response;
    }

    /**
     * 获取飞书通道（供 Controller 使用）
     */
    public FeishuChannel getFeishuChannel() {
        return (FeishuChannel) channels.get("feishu");
    }

    private Model resolveModel(String modelName) {
        try {
            return Model.valueOf(modelName);
        } catch (Exception e) {
            return Model.MINI_MAX;
        }
    }

    /**
     * 克隆 ChatResponse，避免缓存对象被并发修改
     */
    private ChatResponse cloneResponse(ChatResponse source) {
        ChatResponse clone = new ChatResponse();
        clone.setAnswer(source.getAnswer());
        clone.setSuccess(source.isSuccess());
        clone.setSessionId(source.getSessionId());
        clone.setError(source.getError());
        clone.setMode(source.getMode());
        clone.setThinkSteps(source.getThinkSteps());
        clone.setDurationMs(source.getDurationMs());
        clone.setTotalTokens(source.getTotalTokens());
        clone.setCached(source.isCached());
        return clone;
    }

    /**
     * 处理定时任务创建请求
     * <p>
     * 当 ModeRouter 将用户输入路由到 cronjob 模式时，
     * 解析自然语言为定时任务并创建，返回创建结果。
     */
    private ChatResponse handleCronJobCreation(ChatRequest request, String sessionId, List<ThinkStep> allSteps, long startTime) {
        String message = request.getMessage();
        log.info("[FLOW] 处理定时任务创建请求: {}", message);

        // 记录解析步骤
        allSteps.add(ThinkStep.of(ThinkStep.Type.ACTION, "解析定时任务",
                "正在将自然语言解析为定时任务: " + message, 1));

        // 解析自然语言
        Model model = resolveModel(defaultModelName);
        CronJobParser.ParseResult parseResult = CronJobParser.parse(message, model);

        if (!parseResult.isSuccess()) {
            // 解析失败，返回友好的错误提示
            allSteps.add(ThinkStep.of(ThinkStep.Type.OBSERVATION, "解析失败",
                    "无法解析为定时任务: " + parseResult.getError(), 1));

            ChatResponse chatResponse = ChatResponse.failure("无法理解您的定时需求，请尝试更明确的描述，例如：\n"
                    + "- 每天早上9点提醒我查看邮件\n"
                    + "- 每隔30分钟检查服务器状态\n"
                    + "- 每周一下午3点生成本周报告", sessionId);
            chatResponse.setMode("cronjob");
            chatResponse.setThinkSteps(allSteps);
            chatResponse.setDurationMs(System.currentTimeMillis() - startTime);
            return chatResponse;
        }

        // 解析成功，创建定时任务
        CronJob job = new CronJob();
        job.setName(parseResult.getName());
        job.setCronExpression(parseResult.getCronExpression());
        job.setScheduleDesc(parseResult.getScheduleDesc());
        job.setQuery(parseResult.getQuery());
        job.setDescription(message);

        CronJob created = cronJobManager.addJob(job);

        allSteps.add(ThinkStep.of(ThinkStep.Type.OBSERVATION, "定时任务已创建",
                "任务ID: " + created.getId() + ", cron: " + created.getCronExpression()
                        + ", 调度: " + created.getScheduleDesc(), 1));

        // 构建友好的成功响应
        String answer = "已为您创建定时任务！\n\n"
                + "**任务名称**: " + created.getName() + "\n"
                + "**调度规则**: " + created.getScheduleDesc() + "\n"
                + "**执行内容**: " + created.getQuery() + "\n"
                + "**Cron表达式**: `" + created.getCronExpression() + "`\n"
                + "**任务ID**: " + created.getId() + "\n\n"
                + "任务已开始调度，到时间会自动执行。";

        ChatResponse chatResponse = ChatResponse.success(answer, sessionId, "cronjob", allSteps);
        chatResponse.setDurationMs(System.currentTimeMillis() - startTime);

        // 保存对话记忆
        ConversationMemory memory = sessionManager.getOrCreate(sessionId);
        memory.addMessage(new com.nano.claw.messages.Message("user", message));
        memory.addMessage(new com.nano.claw.messages.Message("assistant", answer));

        return chatResponse;
    }
}
