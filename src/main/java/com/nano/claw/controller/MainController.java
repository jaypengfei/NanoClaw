package com.nano.claw.controller;

import com.nano.claw.agent.common.ChatRequest;
import com.nano.claw.agent.common.ChatResponse;
import com.nano.claw.channels.FeishuChannel;
import com.nano.claw.flow.ChatFlow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.annotation.Resource;

/**
 * 核心API控制器
 * <p>
 * 提供聊天接口和飞书 Webhook 回调接口
 *
 * @author Jason
 * @description core api
 * @date 2026/5/16
 */
@RestController
@RequestMapping("/api")
public class MainController {

    private static final Logger log = LoggerFactory.getLogger(MainController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Resource
    private ChatFlow chatFlow;

    /**
     * 通用聊天接口（同步，供飞书等渠道使用）
     *
     * @param request 请求参数
     * @return 聊天响应
     */
    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody ChatRequest request) {
        log.info("[CHAT] 收到请求 >>> 消息: {}, 会话: {}, 模式: {}",
                request.getMessage(), request.getSessionId(), request.getMode());
        return chatFlow.chat(request);
    }

    /**
     * SSE 流式聊天接口（Web 前端使用）
     * <p>
     * 实时推送思考步骤（think_step 事件）和最终答案（done 事件）
     *
     * @param request 请求参数
     * @return SseEmitter
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody ChatRequest request) {
        log.info("[CHAT-SSE] 收到流式请求 >>> 消息: {}, 会话: {}, 模式: {}",
                request.getMessage(), request.getSessionId(), request.getMode());
        return chatFlow.chatStream(request);
    }

    /**
     * 飞书事件回调接口
     * <p>
     * 在飞书开放平台配置此地址作为事件回调 URL
     * 支持：
     * - URL Verification（首次配置验证）
     * - 消息事件接收
     *
     * @param body 飞书回调 JSON 数据
     * @return 处理结果
     */
    @PostMapping("/feishu/webhook")
    public Object feishuWebhook(@RequestBody String body) {
        try {
            JsonNode root = MAPPER.readTree(body);

            // 处理 URL Verification 验证
            if (root.has("challenge")) {
                ObjectNode response = MAPPER.createObjectNode();
                response.put("challenge", root.get("challenge").asText());
                return response;
            }

            // 验证 token
            FeishuChannel feishuChannel = chatFlow.getFeishuChannel();
            if (feishuChannel != null && root.has("token")) {
                String token = root.get("token").asText();
                if (!feishuChannel.verifyToken(token)) {
                    log.warn("飞书 webhook token 验证失败");
                    return MAPPER.createObjectNode().put("error", "token verification failed");
                }
            }

            // 处理事件
            chatFlow.handleFeishuEvent(body);
            return MAPPER.createObjectNode();

        } catch (Exception e) {
            log.error("处理飞书 webhook 失败", e);
            return MAPPER.createObjectNode().put("error", e.getMessage());
        }
    }

    /**
     * 健康检查接口
     */
    @GetMapping("/health")
    public String health() {
        return "NanoClaw is running!";
    }
}
