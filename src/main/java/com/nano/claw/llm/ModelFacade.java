package com.nano.claw.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nano.claw.messages.Message;
import com.nano.claw.utils.HttpUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.function.Consumer;

/**
 * 大模型调用的入口
 * <p>
 * 通过 Spring @Component 管理，API Key 和 API URL 从配置文件注入。
 *
 * @author Jason
 * @description 大模型调用的封装，支持 ReAct Agent 循环调用
 * @date 2026/5/18
 */
@Component
public class ModelFacade {

    private static final Logger log = LoggerFactory.getLogger(ModelFacade.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** API Key，从配置文件注入（支持环境变量 fallback） */
    private static String apiKey;

    /** API URL，从配置文件注入 */
    private static String apiUrl;

    @Value("${nanoclaw.llm.api-key:${MINIMAX_API_KEY:}}")
    private String injectedApiKey;

    @Value("${nanoclaw.llm.api-url:https://api.minimax.chat/v1/chat/completions}")
    private String injectedApiUrl;

    @PostConstruct
    public void init() {
        // 将 Spring 注入的配置值赋给静态字段，供静态方法使用
        ModelFacade.apiKey = injectedApiKey;
        ModelFacade.apiUrl = injectedApiUrl;
        log.info("[ModelFacade] 初始化完成, apiUrl={}, apiKey配置={}",
                apiUrl, (apiKey != null && !apiKey.isEmpty()) ? "已配置" : "未配置");
    }

    /**
     * 调用大模型进行对话补全
     *
     * @param request 包含模型、消息列表等参数的请求
     * @return 模型响应
     */
    public static ModelResponse chatCompletion(ModelRequest request) {
        try {
            // 1. 组装请求体
            String requestBody = buildRequestBody(request, false);

            // 2. 记录日志
            if (log.isDebugEnabled()) {
                log.debug("[LLM Request] traceId={} model={}\n{}", 
                        request.getTraceId(), resolveModelName(request.getModel()), formatMessages(request));
            }

            // 3. 调用 API
            String effectiveApiKey = apiKey;
            String effectiveApiUrl = getEffectiveApiUrl();
            String jsonResponse = HttpUtils.post(effectiveApiUrl, effectiveApiKey, requestBody);

            // 4. 解析响应
            return parseResponse(request.getTraceId(), jsonResponse);

        } catch (Exception e) {
            log.error("[LLM] 调用失败: {}", e.getMessage());
            return new ModelResponse(request.getTraceId(), null, false, "模型调用失败: " + e.getMessage());
        }
    }

    /**
     * 流式调用大模型（stream=true）—— 逐 token 回调
     *
     * @param request       请求参数
     * @param tokenConsumer 每次收到一个 token 片段时回调
     * @return 完整的 ModelResponse
     */
    public static ModelResponse chatCompletionStream(ModelRequest request, Consumer<String> tokenConsumer) {
        try {
            // 1. 组装请求体
            String requestBody = buildRequestBody(request, true);

            String effectiveApiKey = apiKey;
            String effectiveApiUrl = getEffectiveApiUrl();

            // 2. 流式调用，逐行解析
            StringBuilder fullContent = new StringBuilder();
            final int[] totalTokens = {0};

            HttpUtils.postStream(effectiveApiUrl, effectiveApiKey, requestBody, line -> {
                if (line == null || line.isEmpty()) return;
                if (!line.startsWith("data:")) return;

                String data = line.substring(5).trim();
                if ("[DONE]".equals(data)) return;

                try {
                    JsonNode chunk = MAPPER.readTree(data);
                    // 提取 delta content
                    JsonNode choices = chunk.get("choices");
                    if (choices != null && choices.size() > 0) {
                        JsonNode delta = choices.get(0).get("delta");
                        if (delta != null && delta.has("content")) {
                            String token = delta.get("content").asText();
                            if (token != null && !token.isEmpty()) {
                                fullContent.append(token);
                                tokenConsumer.accept(token);
                            }
                        }
                    }
                    // 提取 usage（通常在最后一个 chunk）
                    if (chunk.has("usage") && chunk.get("usage").has("total_tokens")) {
                        totalTokens[0] = chunk.get("usage").get("total_tokens").asInt();
                    }
                } catch (Exception e) {
                    // 单行解析失败不影响整体流程
                    log.debug("[LLM-Stream] 解析chunk失败: {}", data);
                }
            });

            return new ModelResponse(request.getTraceId(), fullContent.toString(), true, null, totalTokens[0]);

        } catch (Exception e) {
            log.error("[LLM-Stream] 流式调用失败", e);
            return new ModelResponse(request.getTraceId(), null, false, "模型流式调用失败: " + e.getMessage());
        }
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 构建请求体 JSON
     */
    private static String buildRequestBody(ModelRequest request, boolean stream) throws Exception {
        ObjectNode requestBody = MAPPER.createObjectNode();
        requestBody.put("model", resolveModelName(request.getModel()));
        requestBody.put("stream", stream);

        ArrayNode messages = MAPPER.createArrayNode();
        for (Message msg : request.getMessages()) {
            ObjectNode msgNode = MAPPER.createObjectNode();
            msgNode.put("role", msg.getRole());
            msgNode.put("content", msg.getContent());
            messages.add(msgNode);
        }
        requestBody.set("messages", messages);

        return MAPPER.writeValueAsString(requestBody);
    }

    /**
     * 解析模型响应
     */
    private static ModelResponse parseResponse(String traceId, String jsonResponse) throws Exception {
        ObjectNode responseJson = (ObjectNode) MAPPER.readTree(jsonResponse);
        
        // 检查是否有 choices
        if (!responseJson.has("choices") || responseJson.get("choices").size() == 0) {
            return new ModelResponse(traceId, null, false, "模型返回空响应");
        }
        
        JsonNode messageNode = responseJson.get("choices").get(0).get("message");
        if (messageNode == null || !messageNode.has("content")) {
            return new ModelResponse(traceId, null, false, "模型返回格式错误");
        }
        
        String content = messageNode.get("content").asText();

        // 解析 token 用量
        int totalTokens = 0;
        if (responseJson.has("usage") && responseJson.get("usage").has("total_tokens")) {
            totalTokens = responseJson.get("usage").get("total_tokens").asInt();
        }

        return new ModelResponse(traceId, content, true, null, totalTokens);
    }

    /**
     * 将 Model 枚举映射为 API 所需的模型名称字符串
     */
    private static String resolveModelName(Model model) {
        switch (model) {
            case MINI_MAX:
                return "MiniMax-M2.7";
            default:
                return "MiniMax-M2.7";
        }
    }

    /**
     * 获取有效的 API URL
     */
    private static String getEffectiveApiUrl() {
        if (apiUrl == null || apiUrl.isEmpty()) {
            return "https://api.minimax.chat/v1/chat/completions";
        }
        return apiUrl;
    }

    /**
     * 格式化消息用于日志输出
     */
    private static String formatMessages(ModelRequest request) {
        StringBuilder sb = new StringBuilder();
        for (Message msg : request.getMessages()) {
            sb.append("-- [").append(msg.getRole()).append("] --\n").append(msg.getContent()).append("\n");
        }
        return sb.toString();
    }
}
