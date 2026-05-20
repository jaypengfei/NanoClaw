package com.nano.claw.agent.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 基于规则的意图识别引擎
 * <p>
 * 用关键词 + 正则模式快速匹配常见意图，避免每次都调用 LLM 做路由判断，
 * 仅当规则未命中时才降级到 LLM 路由。
 * <p>
 * 规则优先级：react > plan_and_execute > reflection > chat
 * 即越具体的规则越靠前匹配，chat 作为兜底。
 *
 * @author Jason
 * @description 基于关键词+正则的意图规则引擎
 * @date 2026/5/19
 */
public class IntentRuleEngine {

    private static final Logger log = LoggerFactory.getLogger(IntentRuleEngine.class);

    /**
     * 路由结果，携带来源信息
     */
    public static class RouteResult {
        /** 路由到的模式 */
        private final String mode;
        /** 是否由规则匹配 */
        private final boolean ruleMatched;
        /** 匹配到的规则名称（调试用） */
        private final String ruleName;

        public RouteResult(String mode, boolean ruleMatched, String ruleName) {
            this.mode = mode;
            this.ruleMatched = ruleMatched;
            this.ruleName = ruleName;
        }

        public String getMode() { return mode; }
        public boolean isRuleMatched() { return ruleMatched; }
        public String getRuleName() { return ruleName; }
    }

    // ==================== 规则定义 ====================

    /** 单条规则 */
    private static class Rule {
        final String name;
        final String mode;
        final List<Pattern> patterns;
        final List<String> keywords;

        Rule(String name, String mode, List<Pattern> patterns, List<String> keywords) {
            this.name = name;
            this.mode = mode;
            this.patterns = patterns != null ? patterns : new ArrayList<Pattern>();
            this.keywords = keywords != null ? keywords : new ArrayList<String>();
        }
    }

    /** 所有规则，按优先级排列 */
    private static final List<Rule> RULES = new ArrayList<>();

    static {
        // ========== CRONJOB 规则：定时任务意图 ==========
        RULES.add(new Rule("定时任务", "cronjob",
                patterns("每天.*提醒", "每天.*定时", "每隔.*提醒", "每周.*提醒",
                        "每天.*点.*执行", "定时.*执行", "定期.*执行",
                        "每天.*点.*查", "每天.*点.*检查", "每天.*点.*推送",
                        "设置.*定时", "添加.*定时", "创建.*定时", "新建.*定时",
                        "每天.*通知", "定时.*通知", "每天.*推送"),
                kw("定时任务", "定时提醒", "每天提醒", "每隔", "定期执行",
                        "每天几点", "设置定时", "添加定时", "创建定时",
                        "定时通知", "定时推送", "定时检查", "定时查询",
                        "每天执行", "每周执行", "cron", "周期性")));

        // ========== REACT 规则：需要调用工具的明确意图 ==========
        RULES.add(new Rule("计算类", "react",
                null,
                kw("计算", "算一下", "等于多少", "求值", "开方", "平方", "乘以", "除以", "加减",
                        "百分比", "求余", "对数", "阶乘", "算术", "换算", "cos", "sin", "tan", "sqrt", "log")));

        RULES.add(new Rule("HTTP请求类", "react",
                null,
                kw("请求", "调用接口", "API", "发请求", "抓取", "爬取", "获取数据", "查询接口")));

        RULES.add(new Rule("天气查询", "react",
                null,
                kw("天气", "气温", "下雨", "下雪", "温度多少", "风速", "紫外线", "空气质量")));

        RULES.add(new Rule("实时信息查询", "react",
                null,
                kw("汇率", "股价", "股票", "实时", "最新消息", "新闻", "热搜", "行情", "比分", "航班", "列车")));

        // ========== PLAN_AND_EXECUTE 规则：复杂多步骤任务 ==========
        RULES.add(new Rule("计划制定", "plan_and_execute",
                patterns("制定.*计划", "规划.*方案", "设计.*方案", "实施方案"),
                kw("制定计划", "规划方案", "实施方案", "步骤计划", "项目计划", "学习计划", "减肥计划")));

        RULES.add(new Rule("多步骤任务", "plan_and_execute",
                patterns("如何搭建", "如何部署", "如何安装", "如何配置", "从零开始",
                        "开发一个.*系统", "构建一个.*应用", "实现.*功能"),
                kw("搭建", "部署", "安装配置", "从零开始", "步骤指南", "分步骤", "多步骤")));

        RULES.add(new Rule("对比分析", "plan_and_execute",
                patterns("对比.*和.*", "比较.*与.*", ".*和.*的区别", ".*vs.*"),
                kw("对比分析", "比较", "区别是什么", "哪个更好", "选型")));

        // ========== REFLECTION 规则：需要高质量输出 ==========
        RULES.add(new Rule("写作类", "reflection",
                patterns("写一篇.*", "写一首.*", "写一个.*故事", "撰写.*报告", "写.*文章"),
                kw("写文章", "写论文", "写报告", "写小说", "写诗", "写故事", "写邮件",
                        "写作", "撰写", "起草", "润色", "修改文章", "改写")));

        RULES.add(new Rule("代码生成", "reflection",
                patterns("写一个.*程序", "实现.*代码", "编写.*函数", "开发.*模块",
                        "用.*实现.*", "写.*算法"),
                kw("写代码", "实现代码", "编写函数", "代码实现", "编码", "编程实现")));

        RULES.add(new Rule("深度分析", "reflection",
                patterns("深入分析.*", "详细解释.*", "全面评估.*"),
                kw("深度分析", "详细分析", "全面评估", "深度思考", "仔细分析", "批判性")));

        // ========== CHAT 规则：简单对话兜底 ==========
        // chat 不需要显式规则，是默认兜底
        RULES.add(new Rule("日常问候", "chat",
                patterns("你好|hello|hi|hey|嗨|早上好|下午好|晚上好|早安|晚安"),
                null));

        RULES.add(new Rule("简单问答", "chat",
                null,
                kw("是什么", "什么是", "为什么", "怎么样", "如何理解", "能不能解释",
                        "告诉我", "科普", "介绍", "解释一下", "说明")));

        RULES.add(new Rule("闲聊翻译", "chat",
                patterns("翻译.*", ".*翻译成.*", ".*用.*怎么说"),
                kw("翻译")));

        RULES.add(new Rule("闲聊情感", "chat",
                null,
                kw("笑话", "搞笑", "开心", "难过", "无聊", "心情")));

        // ========== CRONJOB 规则（关键词兜底）：定时任务意图 ==========
        RULES.add(new Rule("定时任务兜底", "cronjob",
                null,
                kw("定时", "每天", "每隔", "定期", "每周", "每月", "周期")));
    }

    // ==================== 匹配逻辑 ====================

    /**
     * 基于规则匹配意图
     *
     * @param query 用户输入
     * @return 路由结果，包含模式和来源信息
     */
    public static RouteResult match(String query) {
        if (query == null || query.trim().isEmpty()) {
            return new RouteResult("chat", true, "空输入兜底");
        }

        String normalized = query.trim().toLowerCase();

        // 按优先级遍历规则
        for (Rule rule : RULES) {
            // 1. 关键词匹配（任一命中即匹配）
            for (String keyword : rule.keywords) {
                if (normalized.contains(keyword.toLowerCase())) {
                    log.debug("[RULE] 命中规则: {} (关键词: {}), 输入: {}", rule.name, keyword, truncate(query));
                    return new RouteResult(rule.mode, true, rule.name + "(关键词:" + keyword + ")");
                }
            }

            // 2. 正则匹配
            for (Pattern pattern : rule.patterns) {
                if (pattern.matcher(normalized).find()) {
                    log.debug("[RULE] 命中规则: {} (正则), 输入: {}", rule.name, truncate(query));
                    return new RouteResult(rule.mode, true, rule.name + "(正则)");
                }
            }
        }

        // 规则未命中
        log.debug("[RULE] 规则未命中, 输入: {}", truncate(query));
        return new RouteResult(null, false, null);
    }

    // ==================== 辅助方法 ====================

    /** 快速构建关键词列表 */
    private static List<String> kw(String... keywords) {
        List<String> list = new ArrayList<>();
        for (String k : keywords) {
            list.add(k);
        }
        return list;
    }

    /** 快速构建正则列表 */
    private static List<Pattern> patterns(String... regexes) {
        List<Pattern> list = new ArrayList<>();
        for (String regex : regexes) {
            list.add(Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
        }
        return list;
    }

    /** 截断日志输出 */
    private static String truncate(String text) {
        if (text == null) return "";
        return text.length() > 60 ? text.substring(0, 60) + "..." : text;
    }
}
