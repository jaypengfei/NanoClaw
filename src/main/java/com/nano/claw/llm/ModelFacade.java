package com.nano.claw.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nano.claw.messages.Message;
import com.nano.claw.utils.HttpUtils;

import java.util.Map;

/**
 * 大模型调用的入口
 *
 * @author Jason
 * @description 大模型调用的封装，支持 ReAct Agent 循环调用
 * @date 2026/5/18
 */
public class ModelFacade {


    private static final String API_KEY = System.getenv("MINIMAX_API_KEY");
    private static final String API_URL = "https://api.minimax.chat/v1/chat/completions";

    private static final ObjectMapper MAPPER = new ObjectMapper();

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

            // 打印发送给模型的完整输入
            System.out.println("\n========== [LLM Request] traceId=" + request.getTraceId() + " model=" + modelName + " ==========");
            for (Message msg : request.getMessages()) {
                System.out.println("-- [" + msg.getRole() + "] --");
                System.out.println(msg.getContent());
            }
            System.out.println("========== [LLM Request End] ==========\n");

            // 3. 调用 API
            String jsonResponse = HttpUtils.post(API_URL, API_KEY, jsonRequest);

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
     * Demo：直接调用 MiniMax 模型进行一次对话
     */
    public static void main(String[] args) {
        try {
            ObjectMapper mapper = new ObjectMapper();

            // 1. 组装 MiniMax 要求的 JSON 请求体
            ObjectNode requestBody = mapper.createObjectNode();
            requestBody.put("model", "MiniMax-M2.7");
            requestBody.put("stream", false);

            ArrayNode messages = mapper.createArrayNode();

            // 系统角色人设
            ObjectNode systemMsg = mapper.createObjectNode();
            systemMsg.put("role", "system");
            systemMsg.put("name", "MiniMax AI");
            systemMsg.put("content", "你是一个精通 Java 开发的架构师。");
            messages.add(systemMsg);

            // 用户问题
            ObjectNode userMsg = mapper.createObjectNode();
            userMsg.put("role", "user");
            userMsg.put("name", "用户");
            userMsg.put("content", "请简述为什么要将 OkHttpClient 声明为静态全局单例？");
            messages.add(userMsg);

            requestBody.set("messages", messages);
            String jsonRequest = mapper.writeValueAsString(requestBody);

            // 2. 调用工具类
            System.out.println("正在向 MiniMax 发送请求...");
            String jsonResponse = HttpUtils.post(API_URL, API_KEY, jsonRequest);

            // 3. 解析返回
            ObjectNode responseJson = (ObjectNode) mapper.readTree(jsonResponse);
            String aiReply = responseJson.get("choices").get(0).get("message").get("content").asText();

            // 4. 打印输出
            System.out.println("\n====== MiniMax 回复 ======");
            System.out.println(aiReply);
            System.out.println("==========================");

        } catch (Exception e) {
            System.err.println("业务处理或网络请求发生错误: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
