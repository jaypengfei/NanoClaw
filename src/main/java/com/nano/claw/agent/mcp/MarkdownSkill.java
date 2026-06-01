package com.nano.claw.agent.mcp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 基于 Markdown 文件的技能实现
 * <p>
 * 从 skill.md 文件解析出技能名称、描述、prompt模板、触发关键词等元数据。
 * skill.md 格式规范：
 * <pre>
 * ---
 * name: skill-name
 * description: 技能描述
 * version: 1.0.0
 * author: author-name
 * tags: tag1,tag2
 * triggers: 关键词1,关键词2
 * source: local
 * ---
 * # System Prompt
 * 这里是技能的 prompt 模板内容...
 * </pre>
 *
 * @author Jason
 * @description 基于Markdown文件的技能实现
 * @date 2026/5/21
 */
public class MarkdownSkill implements Skill {

    private final String name;
    private final String description;
    private final String promptTemplate;
    private final String version;
    private final String author;
    private final List<String> tags;
    private final List<String> triggerKeywords;
    private final String source;

    public MarkdownSkill(String name, String description, String promptTemplate,
                         String version, String author, List<String> tags,
                         List<String> triggerKeywords, String source) {
        this.name = name;
        this.description = description;
        this.promptTemplate = promptTemplate;
        this.version = version;
        this.author = author;
        this.tags = tags != null ? tags : new ArrayList<String>();
        this.triggerKeywords = triggerKeywords != null ? triggerKeywords : new ArrayList<String>();
        this.source = source;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public String getPromptTemplate() {
        return promptTemplate;
    }

    @Override
    public String execute(String input) {
        // MarkdownSkill 的执行逻辑：将用户输入注入到 prompt 模板中
        if (promptTemplate == null || promptTemplate.isEmpty()) {
            return "技能 " + name + " 无可用 prompt 模板";
        }
        return promptTemplate.replace("{{input}}", input != null ? input : "");
    }

    @Override
    public String getVersion() {
        return version;
    }

    @Override
    public String getAuthor() {
        return author;
    }

    @Override
    public List<String> getTags() {
        return tags;
    }

    @Override
    public List<String> getTriggerKeywords() {
        return triggerKeywords;
    }

    @Override
    public String getSource() {
        return source;
    }

    /**
     * 从 Markdown 内容解析技能
     *
     * @param markdownContent skill.md 文件内容
     * @param defaultName     默认技能名称（文件名兜底）
     * @return 解析出的 MarkdownSkill 实例
     */
    public static MarkdownSkill parse(String markdownContent, String defaultName) {
        if (markdownContent == null || markdownContent.trim().isEmpty()) {
            return new MarkdownSkill(defaultName, "", "", "1.0.0", "unknown",
                    new ArrayList<String>(), new ArrayList<String>(), "local");
        }

        String name = defaultName;
        String description = "";
        String version = "1.0.0";
        String author = "unknown";
        List<String> tags = new ArrayList<String>();
        List<String> triggerKeywords = new ArrayList<String>();
        String source = "local";
        String promptTemplate = "";

        // 解析 Front Matter（---之间的YAML格式）
        String content = markdownContent;
        if (content.startsWith("---")) {
            int endIdx = content.indexOf("---", 3);
            if (endIdx > 0) {
                String frontMatter = content.substring(3, endIdx).trim();
                content = content.substring(endIdx + 3).trim();

                // 简单解析 YAML 格式的 key: value
                String[] lines = frontMatter.split("\n");
                for (String line : lines) {
                    line = line.trim();
                    if (line.isEmpty() || !line.contains(":")) continue;

                    int colonIdx = line.indexOf(':');
                    String key = line.substring(0, colonIdx).trim().toLowerCase();
                    String value = line.substring(colonIdx + 1).trim();

                    switch (key) {
                        case "name":
                            name = value.isEmpty() ? defaultName : value;
                            break;
                        case "description":
                            description = value;
                            break;
                        case "version":
                            version = value.isEmpty() ? "1.0.0" : value;
                            break;
                        case "author":
                            author = value.isEmpty() ? "unknown" : value;
                            break;
                        case "tags":
                            if (!value.isEmpty()) {
                                tags = splitList(value);
                            }
                            break;
                        case "triggers":
                        case "trigger_keywords":
                            if (!value.isEmpty()) {
                                triggerKeywords = splitList(value);
                            }
                            break;
                        case "source":
                            source = value.isEmpty() ? "local" : value;
                            break;
                    }
                }
            }
        }

        // 剩余内容作为 prompt 模板
        promptTemplate = content.trim();

        return new MarkdownSkill(name, description, promptTemplate, version, author,
                tags, triggerKeywords, source);
    }

    /**
     * 解析逗号分隔的列表字符串
     */
    private static List<String> splitList(String value) {
        List<String> result = new ArrayList<String>();
        String[] parts = value.split(",");
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    @Override
    public String toString() {
        return "MarkdownSkill{name='" + name + "', version='" + version
                + "', tags=" + tags + ", triggers=" + triggerKeywords + "}";
    }
}
