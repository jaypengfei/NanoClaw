package com.nano.claw.agent.mcp;

import com.nano.claw.utils.HttpUtils;

/**
 * 内置工具 - HTTP 请求工具
 * <p>
 * 支持发起 GET/POST HTTP 请求，用于调用外部 API
 *
 * @author Jason
 * @description HTTP请求工具
 * @date 2026/5/19
 */
public class HttpTool implements Tool {

    @Override
    public String getName() {
        return "http_request";
    }

    @Override
    public String getDescription() {
        return "发起HTTP请求，获取外部API数据。输入JSON格式: {\"url\": \"请求地址\", \"method\": \"GET或POST\", \"body\": \"POST请求体(可选)\"}";
    }

    @Override
    public ToolResult execute(String input) {
        try {
            // 简单解析输入参数
            String url = extractJsonValue(input, "url");
            String method = extractJsonValue(input, "method");
            String body = extractJsonValue(input, "body");

            if (url == null || url.isEmpty()) {
                return ToolResult.failure("缺少必要参数: url");
            }
            if (method == null) {
                method = "GET";
            }

            String response;
            if ("POST".equalsIgnoreCase(method)) {
                response = HttpUtils.post(url, null, body != null ? body : "");
            } else {
                // GET 请求 - 使用 OkHttp 直接调用
                response = HttpUtils.get(url);
            }

            return ToolResult.success(response);

        } catch (Exception e) {
            return ToolResult.failure("HTTP请求失败: " + e.getMessage());
        }
    }

    /**
     * 简单的 JSON 值提取（避免依赖复杂 JSON 解析）
     */
    private String extractJsonValue(String json, String key) {
        if (json == null) return null;
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return null;
        // 找到冒号后的值
        int colonIdx = json.indexOf(":", idx + pattern.length());
        if (colonIdx < 0) return null;
        // 找到值的起始引号
        int startQuote = json.indexOf("\"", colonIdx + 1);
        if (startQuote < 0) return null;
        int endQuote = json.indexOf("\"", startQuote + 1);
        if (endQuote < 0) return null;
        return json.substring(startQuote + 1, endQuote);
    }
}