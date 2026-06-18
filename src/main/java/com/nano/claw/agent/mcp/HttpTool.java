package com.nano.claw.agent.mcp;

import com.nano.claw.utils.HttpUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 内置工具 - HTTP 请求工具
 * <p>
 * 支持发起 GET/POST HTTP 请求，用于调用外部 API
 * 包含 SSRF 防护，禁止访问内网地址
 *
 * @author Jason
 * @description HTTP请求工具
 * @date 2026/5/19
 */
public class HttpTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 禁止访问的内网IP前缀 */
    private static final Set<String> BLOCKED_IP_PREFIXES = new HashSet<>(Arrays.asList(
            "127.", "10.", "192.168.", "0.", "169.254."
    ));

    /** 禁止访问的域名后缀 */
    private static final Set<String> BLOCKED_HOSTNAMES = new HashSet<>(Arrays.asList(
            "localhost", "localhost.localdomain"
    ));

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

            // SSRF 防护：校验URL安全性
            String ssrfError = validateUrl(url);
            if (ssrfError != null) {
                return ToolResult.failure("URL安全校验失败: " + ssrfError);
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
     * SSRF 防护：校验URL是否指向内网或危险地址
     *
     * @param url 请求地址
     * @return 错误信息，校验通过返回 null
     */
    private String validateUrl(String url) {
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            if (host == null || host.isEmpty()) {
                return "URL中缺少主机地址";
            }

            // 检查禁止的域名
            String lowerHost = host.toLowerCase();
            if (BLOCKED_HOSTNAMES.contains(lowerHost)) {
                return "禁止访问: " + host;
            }

            // DNS解析后检查IP
            InetAddress address = InetAddress.getByName(host);
            String ip = address.getHostAddress();
            for (String prefix : BLOCKED_IP_PREFIXES) {
                if (ip.startsWith(prefix)) {
                    return "禁止访问内网地址: " + ip;
                }
            }

            // 检查 172.16.0.0/12 范围
            if (ip.startsWith("172.")) {
                String[] parts = ip.split("\\.");
                if (parts.length >= 2) {
                    int secondOctet = Integer.parseInt(parts[1]);
                    if (secondOctet >= 16 && secondOctet <= 31) {
                        return "禁止访问内网地址: " + ip;
                    }
                }
            }

            return null;
        } catch (UnknownHostException e) {
            return "无法解析主机名: " + e.getMessage();
        } catch (Exception e) {
            return "URL解析失败: " + e.getMessage();
        }
    }

    /**
     * 使用 Jackson 安全解析 JSON 值
     */
    private String extractJsonValue(String json, String key) {
        if (json == null) return null;
        try {
            JsonNode node = MAPPER.readTree(json);
            JsonNode value = node.get(key);
            if (value == null || value.isNull()) return null;
            return value.asText();
        } catch (Exception e) {
            return null;
        }
    }
}