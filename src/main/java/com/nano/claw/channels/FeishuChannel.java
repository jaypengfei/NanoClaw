package com.nano.claw.channels;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nano.claw.utils.HttpUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 飞书通道 - 对接飞书IM
 * <p>
 * 支持接收飞书事件回调、发送飞书消息
 * 需要在飞书开放平台创建应用，获取 App ID 和 App Secret
 *
 * @author Jason
 * @description 飞书IM通道实现
 * @date 2026/5/19
 */
public class FeishuChannel implements Channel {

    private static final Logger log = LoggerFactory.getLogger(FeishuChannel.class);

    private static final String FEISHU_BASE_URL = "https://open.feishu.cn/open-apis";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String appId;
    private final String appSecret;
    private final String verificationToken;

    /** 缓存的 tenant_access_token */
    private String tenantAccessToken;
    private long tokenExpireTime;

    public FeishuChannel(String appId, String appSecret, String verificationToken) {
        this.appId = appId;
        this.appSecret = appSecret;
        this.verificationToken = verificationToken;
    }

    @Override
    public String getName() {
        return "feishu";
    }

    @Override
    public boolean sendText(String chatId, String content) {
        try {
            String token = getTenantAccessToken();
            if (token == null) {
                log.error("获取飞书 token 失败");
                return false;
            }

            // 构建飞书消息体
            ObjectMapper mapper = new ObjectMapper();
            com.fasterxml.jackson.databind.node.ObjectNode body = mapper.createObjectNode();
            body.put("receive_id", chatId);
            body.put("msg_type", "text");

            com.fasterxml.jackson.databind.node.ObjectNode textContent = mapper.createObjectNode();
            textContent.put("text", content);
            body.set("content", textContent);

            String jsonBody = mapper.writeValueAsString(body);
            String url = FEISHU_BASE_URL + "/im/v1/messages?receive_id_type=chat_id";

            String response = HttpUtils.postWithAuth(url, "Bearer " + token, jsonBody);
            return response != null;

        } catch (Exception e) {
            log.error("发送飞书消息失败", e);
            return false;
        }
    }

    @Override
    public boolean reply(String originalMsgId, String content) {
        try {
            String token = getTenantAccessToken();
            if (token == null) {
                return false;
            }

            ObjectMapper mapper = new ObjectMapper();
            com.fasterxml.jackson.databind.node.ObjectNode body = mapper.createObjectNode();
            body.put("msg_type", "text");

            com.fasterxml.jackson.databind.node.ObjectNode textContent = mapper.createObjectNode();
            textContent.put("text", content);
            body.set("content", textContent);

            String jsonBody = mapper.writeValueAsString(body);
            String url = FEISHU_BASE_URL + "/im/v1/messages/" + originalMsgId + "/reply";

            String response = HttpUtils.postWithAuth(url, "Bearer " + token, jsonBody);
            return response != null;

        } catch (Exception e) {
            log.error("回复飞书消息失败", e);
            return false;
        }
    }

    @Override
    public ChannelMessage handleEvent(String eventPayload) {
        try {
            JsonNode root = MAPPER.readTree(eventPayload);

            // 飞书事件验证（首次配置回调地址）
            if (root.has("challenge")) {
                // 返回 challenge 验证（由 Controller 层处理）
                ChannelMessage msg = new ChannelMessage();
                msg.setMsgType("challenge");
                msg.setContent(root.get("challenge").asText());
                return msg;
            }

            // 解析事件数据
            JsonNode event = root.has("event") ? root.get("event") : root;
            JsonNode message = event.has("message") ? event.get("message") : event;

            ChannelMessage channelMsg = new ChannelMessage();
            channelMsg.setMsgType("text");
            channelMsg.setChannelType("feishu");

            if (message.has("message_id")) {
                channelMsg.setMsgId(message.get("message_id").asText());
            }
            if (message.has("chat_id")) {
                channelMsg.setChatId(message.get("chat_id").asText());
            }

            // 解析消息内容
            if (message.has("content")) {
                String contentStr = message.get("content").asText();
                JsonNode contentNode = MAPPER.readTree(contentStr);
                if (contentNode.has("text")) {
                    channelMsg.setContent(contentNode.get("text").asText());
                }
            }

            // 解析发送者
            JsonNode sender = event.has("sender") ? event.get("sender") : event;
            if (sender.has("sender_id")) {
                channelMsg.setSenderId(sender.get("sender_id").asText());
            }
            if (sender.has("sender_name")) {
                channelMsg.setSenderName(sender.get("sender_name").asText());
            }

            return channelMsg;

        } catch (Exception e) {
            log.error("解析飞书事件失败", e);
            return null;
        }
    }

    /**
     * 验证回调请求的 token
     */
    public boolean verifyToken(String token) {
        return verificationToken != null && verificationToken.equals(token);
    }

    /**
     * 获取飞书 tenant_access_token
     */
    private synchronized String getTenantAccessToken() {
        // 检查缓存是否有效
        if (tenantAccessToken != null && System.currentTimeMillis() < tokenExpireTime) {
            return tenantAccessToken;
        }

        try {
            ObjectMapper mapper = new ObjectMapper();
            com.fasterxml.jackson.databind.node.ObjectNode body = mapper.createObjectNode();
            body.put("app_id", appId);
            body.put("app_secret", appSecret);

            String jsonBody = mapper.writeValueAsString(body);
            String url = FEISHU_BASE_URL + "/auth/v3/tenant_access_token/internal";

            String response = HttpUtils.post(url, null, jsonBody);
            if (response == null) return null;

            JsonNode result = mapper.readTree(response);
            if (result.has("tenant_access_token")) {
                tenantAccessToken = result.get("tenant_access_token").asText();
                int expire = result.has("expire") ? result.get("expire").asInt() : 7200;
                tokenExpireTime = System.currentTimeMillis() + (expire - 300) * 1000L; // 提前5分钟过期
                return tenantAccessToken;
            }

            return null;
        } catch (Exception e) {
            log.error("获取飞书 token 失败", e);
            return null;
        }
    }
}