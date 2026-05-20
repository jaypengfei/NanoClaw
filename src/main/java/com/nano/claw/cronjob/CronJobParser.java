package com.nano.claw.cronjob;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 自然语言定时任务解析器
 * <p>
 * 将用户的自然语言描述（如"每天早上9点提醒我查看邮件"）
 * 解析为结构化的 CronJob 对象，包含 cron 表达式、任务名称、执行查询等。
 * <p>
 * 解析策略：先基于关键词+正则的规则快速匹配常见模式，
 * 规则未命中时降级到 LLM 解析。
 *
 * @author Jason
 * @description 自然语言转定时任务
 * @date 2026/5/19
 */
public class CronJobParser {

    private static final Logger log = LoggerFactory.getLogger(CronJobParser.class);

    /**
     * 解析结果
     */
    public static class ParseResult {
        /** 是否解析成功 */
        private boolean success;
        /** cron 表达式（6位：秒 分 时 日 月 周） */
        private String cronExpression;
        /** 人类可读的调度描述 */
        private String scheduleDesc;
        /** 任务名称 */
        private String name;
        /** 任务执行查询内容 */
        private String query;
        /** 错误信息 */
        private String error;
        /** 是否由规则匹配 */
        private boolean ruleMatched;

        public static ParseResult ok(String cronExpression, String scheduleDesc, String name, String query, boolean ruleMatched) {
            ParseResult r = new ParseResult();
            r.success = true;
            r.cronExpression = cronExpression;
            r.scheduleDesc = scheduleDesc;
            r.name = name;
            r.query = query;
            r.ruleMatched = ruleMatched;
            return r;
        }

        public static ParseResult fail(String error) {
            ParseResult r = new ParseResult();
            r.success = false;
            r.error = error;
            return r;
        }

        public boolean isSuccess() { return success; }
        public String getCronExpression() { return cronExpression; }
        public String getScheduleDesc() { return scheduleDesc; }
        public String getName() { return name; }
        public String getQuery() { return query; }
        public String getError() { return error; }
        public boolean isRuleMatched() { return ruleMatched; }
    }

    /** 规则模式 */
    private static class ParseRule {
        final Pattern pattern;
        final String cronTemplate;
        final String scheduleDesc;

        ParseRule(Pattern pattern, String cronTemplate, String scheduleDesc) {
            this.pattern = pattern;
            this.cronTemplate = cronTemplate;
            this.scheduleDesc = scheduleDesc;
        }
    }

    /** 常见中文时间表达规则 */
    private static final List<ParseRule> TIME_RULES = new ArrayList<>();

    static {
        // 每天早上/上午 X 点
        TIME_RULES.add(new ParseRule(
                Pattern.compile("每天?(?:早上|上午|早晨)\\s*(\\d+)[点时](?:半)?"),
                null, null // 动态生成
        ));
        // 每天下午/晚上 X 点
        TIME_RULES.add(new ParseRule(
                Pattern.compile("每天?(?:下午|晚上|傍晚|晚间)\\s*(\\d+)[点时](?:半)?"),
                null, null
        ));
        // 每天 X 点
        TIME_RULES.add(new ParseRule(
                Pattern.compile("每天\\s*(\\d+)[点时](?:半)?"),
                null, null
        ));
        // 每隔 X 分钟/小时
        TIME_RULES.add(new ParseRule(
                Pattern.compile("每隔\\s*(\\d+)\\s*(分钟|小时|分|小时)"),
                null, null
        ));
        // 每周一/周二/... X 点
        TIME_RULES.add(new ParseRule(
                Pattern.compile("每周?(一|二|三|四|五|六|日|天)\\s*(?:早上|上午|下午|晚上)?\\s*(\\d+)?[点时]?"),
                null, null
        ));
        // 每天 X 点 X 分
        TIME_RULES.add(new ParseRule(
                Pattern.compile("每天\\s*(\\d+)[点时](\\d+)分?"),
                null, null
        ));
    }

    /** LLM 解析用的 system prompt */
    private static final String PARSER_SYSTEM_PROMPT =
            "你是一个定时任务解析专家。你的任务是将用户的自然语言描述解析为结构化的定时任务信息。\n\n"
            + "请严格按照以下JSON格式输出，不要输出任何其他内容：\n"
            + "{\n"
            + "  \"cronExpression\": \"秒 分 时 日 月 周\",\n"
            + "  \"scheduleDesc\": \"人类可读的调度描述\",\n"
            + "  \"name\": \"任务名称（简短）\",\n"
            + "  \"query\": \"任务执行时要查询的内容\"\n"
            + "}\n\n"
            + "cron表达式说明（6位，用空格分隔）：\n"
            + "- 秒：0-59\n"
            + "- 分：0-59\n"
            + "- 时：0-23\n"
            + "- 日：1-31\n"
            + "- 月：1-12\n"
            + "- 周：1-7（1=周日，2=周一，...，7=周六）\n"
            + "- * 表示任意值\n"
            + "- ? 表示不指定（日和周不能同时指定）\n\n"
            + "示例：\n"
            + "用户：每天早上9点提醒我查看邮件\n"
            + "输出：{\"cronExpression\":\"0 0 9 * * ?\",\"scheduleDesc\":\"每天上午9:00\",\"name\":\"查看邮件提醒\",\"query\":\"查看邮件\"}\n\n"
            + "用户：每隔30分钟检查一次服务器状态\n"
            + "输出：{\"cronExpression\":\"0 */30 * * * ?\",\"scheduleDesc\":\"每30分钟\",\"name\":\"服务器状态检查\",\"query\":\"检查服务器状态\"}\n\n"
            + "用户：每周一下午3点生成本周报告\n"
            + "输出：{\"cronExpression\":\"0 0 15 ? * 2\",\"scheduleDesc\":\"每周一下午3:00\",\"name\":\"生成周报\",\"query\":\"生成本周报告\"}\n\n"
            + "用户：每天晚上10点总结今天的新闻\n"
            + "输出：{\"cronExpression\":\"0 0 22 * * ?\",\"scheduleDesc\":\"每天晚上10:00\",\"name\":\"新闻总结\",\"query\":\"总结今天的新闻\"}\n\n"
            + "重要规则：\n"
            + "- 只输出JSON，不要输出其他内容\n"
            + "- cron表达式必须是6位\n"
            + "- name要简短（不超过10个字）\n"
            + "- query是要执行的具体任务内容\n"
            + "- 如果无法解析为定时任务，输出 {\"error\":\"无法解析\"}";

    /**
     * 解析自然语言为定时任务
     *
     * @param input 用户输入的自然语言
     * @param model 使用的模型
     * @return 解析结果
     */
    public static ParseResult parse(String input, Model model) {
        if (input == null || input.trim().isEmpty()) {
            return ParseResult.fail("输入不能为空");
        }

        // ========== 1. 规则快速匹配 ==========
        ParseResult ruleResult = parseByRules(input);
        if (ruleResult != null && ruleResult.isSuccess()) {
            log.info("[CRON-PARSER] 规则解析成功: {} => {}", input, ruleResult.getCronExpression());
            return ruleResult;
        }

        // ========== 2. 降级到 LLM 解析 ==========
        log.info("[CRON-PARSER] 规则未命中，降级LLM解析: {}", input);
        return parseByLLM(input, model);
    }

    /**
     * 基于规则的快速解析
     */
    private static ParseResult parseByRules(String input) {
        String normalized = input.trim();

        // 每天早上/上午 X 点
        Pattern morningPattern = Pattern.compile("每天?(?:早上|上午|早晨)\\s*(\\d+)[点时](?:半)?");
        Matcher m = morningPattern.matcher(normalized);
        if (m.find()) {
            int hour = Integer.parseInt(m.group(1));
            if (hour >= 0 && hour <= 12) {
                String query = extractQuery(normalized);
                String name = extractName(query);
                return ParseResult.ok(
                        "0 0 " + hour + " * * ?",
                        "每天上午" + hour + ":00",
                        name,
                        query,
                        true
                );
            }
        }

        // 每天下午/晚上 X 点
        Pattern afternoonPattern = Pattern.compile("每天?(?:下午|晚上|傍晚|晚间)\\s*(\\d+)[点时](?:半)?");
        m = afternoonPattern.matcher(normalized);
        if (m.find()) {
            int hour = Integer.parseInt(m.group(1));
            int realHour = hour + 12;
            if (realHour > 23) realHour = 23;
            String query = extractQuery(normalized);
            String name = extractName(query);
            return ParseResult.ok(
                    "0 0 " + realHour + " * * ?",
                    "每天" + (hour <= 6 ? "凌晨" : "晚上") + hour + ":00",
                    name,
                    query,
                    true
            );
        }

        // 每天 X 点 X 分
        Pattern hourMinPattern = Pattern.compile("每天\\s*(\\d+)[点时](\\d+)分?");
        m = hourMinPattern.matcher(normalized);
        if (m.find()) {
            int hour = Integer.parseInt(m.group(1));
            int min = Integer.parseInt(m.group(2));
            if (hour >= 0 && hour <= 23 && min >= 0 && min <= 59) {
                String query = extractQuery(normalized);
                String name = extractName(query);
                return ParseResult.ok(
                        "0 " + min + " " + hour + " * * ?",
                        "每天" + hour + ":" + String.format("%02d", min),
                        name,
                        query,
                        true
                );
            }
        }

        // 每天 X 点
        Pattern dailyPattern = Pattern.compile("每天\\s*(\\d+)[点时](?:半)?");
        m = dailyPattern.matcher(normalized);
        if (m.find()) {
            int hour = Integer.parseInt(m.group(1));
            if (hour >= 0 && hour <= 23) {
                String query = extractQuery(normalized);
                String name = extractName(query);
                return ParseResult.ok(
                        "0 0 " + hour + " * * ?",
                        "每天" + hour + ":00",
                        name,
                        query,
                        true
                );
            }
        }

        // 每隔 X 分钟
        Pattern intervalMinPattern = Pattern.compile("每隔\\s*(\\d+)\\s*(?:分钟|分)");
        m = intervalMinPattern.matcher(normalized);
        if (m.find()) {
            int interval = Integer.parseInt(m.group(1));
            if (interval > 0 && interval <= 60) {
                String query = extractQuery(normalized);
                String name = extractName(query);
                return ParseResult.ok(
                        "0 */" + interval + " * * * ?",
                        "每隔" + interval + "分钟",
                        name,
                        query,
                        true
                );
            }
        }

        // 每隔 X 小时
        Pattern intervalHourPattern = Pattern.compile("每隔\\s*(\\d+)\\s*(?:小时|钟头)");
        m = intervalHourPattern.matcher(normalized);
        if (m.find()) {
            int interval = Integer.parseInt(m.group(1));
            if (interval > 0 && interval <= 24) {
                String query = extractQuery(normalized);
                String name = extractName(query);
                return ParseResult.ok(
                        "0 0 */" + interval + " * * ?",
                        "每隔" + interval + "小时",
                        name,
                        query,
                        true
                );
            }
        }

        // 每周X X点
        Pattern weeklyPattern = Pattern.compile("每周?(一|二|三|四|五|六|日|天)\\s*(?:早上|上午|下午|晚上)?\\s*(\\d+)?[点时]?");
        m = weeklyPattern.matcher(normalized);
        if (m.find()) {
            String dayStr = m.group(1);
            int dayOfWeek = parseChineseDayOfWeek(dayStr);
            int hour = m.group(2) != null ? Integer.parseInt(m.group(2)) : 9;
            String query = extractQuery(normalized);
            String name = extractName(query);
            String[] dayNames = {"", "日", "一", "二", "三", "四", "五", "六"};
            return ParseResult.ok(
                    "0 0 " + hour + " ? * " + dayOfWeek,
                    "每周" + dayNames[dayOfWeek] + " " + hour + ":00",
                    name,
                    query,
                    true
            );
        }

        return null;
    }

    /**
     * 通过 LLM 解析自然语言
     */
    private static ParseResult parseByLLM(String input, Model model) {
        try {
            String traceId = UUID.randomUUID().toString();
            List<Message> messages = new ArrayList<>();
            messages.add(new Message("system", PARSER_SYSTEM_PROMPT));
            messages.add(new Message("user", input));

            ModelRequest request = new ModelRequest(model, traceId, messages);
            ModelResponse response = ModelFacade.chatCompletion(request);

            if (!response.isSuccess()) {
                return ParseResult.fail("LLM解析失败: " + response.getError());
            }

            return parseLLMResponse(response.getContent());

        } catch (Exception e) {
            log.error("[CRON-PARSER] LLM解析异常", e);
            return ParseResult.fail("LLM解析异常: " + e.getMessage());
        }
    }

    /**
     * 解析 LLM 返回的 JSON
     */
    private static ParseResult parseLLMResponse(String content) {
        if (content == null || content.trim().isEmpty()) {
            return ParseResult.fail("LLM返回为空");
        }

        try {
            // 提取JSON部分（可能被markdown代码块包裹）
            String json = content.trim();
            if (json.contains("```")) {
                int start = json.indexOf("{");
                int end = json.lastIndexOf("}") + 1;
                if (start >= 0 && end > start) {
                    json = json.substring(start, end);
                }
            } else if (json.startsWith("{")) {
                int end = json.lastIndexOf("}") + 1;
                if (end > 0) {
                    json = json.substring(0, end);
                }
            }

            // 简单JSON解析（不引入额外依赖）
            if (json.contains("\"error\"")) {
                return ParseResult.fail("LLM无法解析该输入为定时任务");
            }

            String cronExpression = extractJsonField(json, "cronExpression");
            String scheduleDesc = extractJsonField(json, "scheduleDesc");
            String name = extractJsonField(json, "name");
            String query = extractJsonField(json, "query");

            if (cronExpression == null || cronExpression.isEmpty()) {
                return ParseResult.fail("LLM未返回cron表达式");
            }

            // 验证cron表达式基本格式
            String[] parts = cronExpression.trim().split("\\s+");
            if (parts.length != 6) {
                return ParseResult.fail("cron表达式格式错误，应为6位: " + cronExpression);
            }

            return ParseResult.ok(cronExpression, scheduleDesc, name, query, false);

        } catch (Exception e) {
            log.error("[CRON-PARSER] 解析LLM返回失败: {}", content, e);
            return ParseResult.fail("解析LLM返回失败: " + e.getMessage());
        }
    }

    /**
     * 从 JSON 字符串中提取字段值
     */
    private static String extractJsonField(String json, String fieldName) {
        String key = "\"" + fieldName + "\"";
        int keyIdx = json.indexOf(key);
        if (keyIdx < 0) return null;

        int colonIdx = json.indexOf(":", keyIdx + key.length());
        if (colonIdx < 0) return null;

        int valueStart = -1;
        int valueEnd = -1;

        // 找值开始位置（跳过空白）
        for (int i = colonIdx + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"') {
                valueStart = i + 1;
                // 找值结束位置
                for (int j = i + 1; j < json.length(); j++) {
                    if (json.charAt(j) == '"' && json.charAt(j - 1) != '\\') {
                        valueEnd = j;
                        break;
                    }
                }
                break;
            } else if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                // 非字符串值（如数字）
                valueStart = i;
                for (int j = i; j < json.length(); j++) {
                    if (json.charAt(j) == ',' || json.charAt(j) == '}' || json.charAt(j) == '\n') {
                        valueEnd = j;
                        break;
                    }
                }
                break;
            }
        }

        if (valueStart >= 0 && valueEnd > valueStart) {
            return json.substring(valueStart, valueEnd).trim();
        }
        return null;
    }

    /**
     * 从自然语言中提取查询内容（去掉时间部分）
     */
    private static String extractQuery(String input) {
        // 去掉常见的时间前缀词
        String query = input
                .replaceAll("每天?(?:早上|上午|早晨|下午|晚上|傍晚|晚间)?\\s*\\d+[点时](?:\\d+分?)?(?:半)?", "")
                .replaceAll("每隔\\s*\\d+\\s*(?:分钟|小时|分)", "")
                .replaceAll("每周?[一二三四五六日天]\\s*(?:早上|上午|下午|晚上)?\\s*\\d*[点时]?", "")
                .replaceAll("每天\\s*", "")
                .replaceAll("提醒我", "")
                .replaceAll("帮我", "")
                .replaceAll("定时", "")
                .trim();
        return query.isEmpty() ? input : query;
    }

    /**
     * 从查询内容中生成简短名称
     */
    private static String extractName(String query) {
        if (query == null || query.isEmpty()) return "定时任务";
        // 截取前8个字符作为名称
        return query.length() > 8 ? query.substring(0, 8) + "..." : query;
    }

    /**
     * 中文星期转数字
     */
    private static int parseChineseDayOfWeek(String day) {
        switch (day) {
            case "一": return 2;
            case "二": return 3;
            case "三": return 4;
            case "四": return 5;
            case "五": return 6;
            case "六": return 7;
            case "日":
            case "天": return 1;
            default: return 2;
        }
    }
}
