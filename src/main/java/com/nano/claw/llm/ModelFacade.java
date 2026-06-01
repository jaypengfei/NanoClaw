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
 * 保留静态方法调用方式，通过 @PostConstruct 将配置值赋给静态字段，
 * 兼容非 Spring Bean 的调用方（Agent、ModeRouter 等）。
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
            ObjectNode requestBody = MAPPER.createObjectNode();
            String modelName = resolveModelName(request.getModel());
            requestBody.put("model", modelName);
            requestBody.put("stream", false);

            // 2. 组装消息列表
            ArrayNode messages = MAPPER.createArrayNode();
            for (Message msg : request.getMessages()) {
                ObjectNode msgNode = MAPPER.createObjectNode();
                msgNode.put("role", msg.getRole());
                msgNode.put("content", msg.getContent());
                messages.add(msgNode);
            }
            requestBody.set("messages", messages);

            String jsonRequest = MAPPER.writeValueAsString(requestBody);

            // 记录发送给模型的完整输入（DEBUG级别，生产环境可关闭）
            if (log.isDebugEnabled()) {
                StringBuilder logBuilder = new StringBuilder();
                logBuilder.append("[LLM Request] traceId=").append(request.getTraceId())
                        .append(" model=").append(modelName);
                for (Message msg : request.getMessages()) {
                    logBuilder.append("\n-- [").append(msg.getRole()).append("] --\n")
                            .append(msg.getContent());
                }
                log.debug(logBuilder.toString());
            }

            // 3. 调用 API
            String effectiveApiKey = apiKey;
            String effectiveApiUrl = apiUrl;
            if (effectiveApiUrl == null || effectiveApiUrl.isEmpty()) {
                effectiveApiUrl = "https://api.minimax.chat/v1/chat/completions";
            }
            String jsonResponse = HttpUtils.post(effectiveApiUrl, effectiveApiKey, jsonRequest);

            // 4. 解析响应
            ObjectNode responseJson = (ObjectNode) MAPPER.readTree(jsonResponse);
            String content = responseJson.get("choices").get(0).get("message").get("content").asText();

            // 4.1 解析 token 用量
            int totalTokens = 0;
            if (responseJson.has("usage") && responseJson.get("usage").has("total_tokens")) {
                totalTokens = responseJson.get("usage").get("total_tokens").asInt();
            }

            return new ModelResponse(request.getTraceId(), content, true, null, totalTokens);

        } catch (Exception e) {
            return new ModelResponse(request.getTraceId(), null, false, "模型调用失败: " + e.getMessage());
        }
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
     * 流式调用大模型（stream=true）—— 逐 token 回调
     * <p>
     * 大模型 API 返回 SSE 格式，每行 `data: {...}` 包含 delta.content。
     * 这个方法逐块解析并通过 tokenConsumer 回调每个 token 片段，
     * 最终返回完整拼接的响应。
     *
     * @param request       请求参数
     * @param tokenConsumer 每次收到一个 token 片段时回调
     * @return 完整的 ModelResponse
     */
    public static ModelResponse chatCompletionStream(ModelRequest request, Consumer<String> tokenConsumer) {
        try {
            // 1. 组装请求体
            ObjectNode requestBody = MAPPER.createObjectNode();
            String modelName = resolveModelName(request.getModel());
            requestBody.put("model", modelName);
            requestBody.put("stream", true);

            // 2. 组装消息列表
            ArrayNode messages = MAPPER.createArrayNode();
            for (Message msg : request.getMessages()) {
                ObjectNode msgNode = MAPPER.createObjectNode();
                msgNode.put("role", msg.getRole());
                msgNode.put("content", msg.getContent());
                messages.add(msgNode);
            }
            requestBody.set("messages", messages);

            String jsonRequest = MAPPER.writeValueAsString(requestBody);

            String effectiveApiKey = apiKey;
            String effectiveApiUrl = apiUrl;
            if (effectiveApiUrl == null || effectiveApiUrl.isEmpty()) {
                effectiveApiUrl = "https://api.minimax.chat/v1/chat/completions";
            }

            // 3. 流式调用，逐行解析
            StringBuilder fullContent = new StringBuilder();
            final int[] totalTokens = {0};

            HttpUtils.postStream(effectiveApiUrl, effectiveApiKey, jsonRequest, line -> {
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

}
