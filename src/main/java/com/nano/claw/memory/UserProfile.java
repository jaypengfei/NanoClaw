package com.nano.claw.memory;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.util.*;

/**
 * 结构化用户画像 - 从对话记忆中提取的用户特征
 * <p>
 * 通过 LLM 从历史记忆中自动提取和更新，存储为 Markdown 格式。
 * 包含用户的基本信息、偏好、习惯、兴趣等维度。
 *
 * @author Jason
 * @description 结构化用户画像
 * @date 2026/5/19
 */
public class UserProfile {

    /** 基本信息 */
    private Map<String, String> basicInfo = new LinkedHashMap<>();

    /** 偏好与习惯 */
    private List<String> preferences = new ArrayList<>();

    /** 兴趣领域 */
    private List<String> interests = new ArrayList<>();

    /** 沟通风格偏好 */
    private Map<String, String> communicationStyle = new LinkedHashMap<>();

    /** 关键记忆标签 */
    private List<String> keyTags = new ArrayList<>();

    /** 画像最后更新时间 */
    private long lastUpdatedAt;

    /** 画像版本号（每次更新递增） */
    private int version;

    public UserProfile() {
        this.lastUpdatedAt = System.currentTimeMillis();
        this.version = 1;
    }

    // ==================== Markdown 序列化 ====================

    /**
     * 将用户画像序列化为 Markdown 格式
     */
    public String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("# 用户画像\n\n");
        sb.append("> 最后更新: ").append(formatTime(lastUpdatedAt))
                .append(" | 版本: v").append(version).append("\n\n");

        // 基本信息
        sb.append("## 基本信息\n\n");
        if (basicInfo.isEmpty()) {
            sb.append("- 暂无\n");
        } else {
            for (Map.Entry<String, String> e : basicInfo.entrySet()) {
                sb.append("- **").append(e.getKey()).append("**: ").append(e.getValue()).append("\n");
            }
        }
        sb.append("\n");

        // 偏好与习惯
        sb.append("## 偏好与习惯\n\n");
        if (preferences.isEmpty()) {
            sb.append("- 暂无\n");
        } else {
            for (String p : preferences) {
                sb.append("- ").append(p).append("\n");
            }
        }
        sb.append("\n");

        // 兴趣领域
        sb.append("## 兴趣领域\n\n");
        if (interests.isEmpty()) {
            sb.append("- 暂无\n");
        } else {
            for (String i : interests) {
                sb.append("- ").append(i).append("\n");
            }
        }
        sb.append("\n");

        // 沟通风格
        sb.append("## 沟通风格\n\n");
        if (communicationStyle.isEmpty()) {
            sb.append("- 暂无\n");
        } else {
            for (Map.Entry<String, String> e : communicationStyle.entrySet()) {
                sb.append("- **").append(e.getKey()).append("**: ").append(e.getValue()).append("\n");
            }
        }
        sb.append("\n");

        // 关键标签
        sb.append("## 关键标签\n\n");
        if (keyTags.isEmpty()) {
            sb.append("暂无\n");
        } else {
            sb.append(String.join(" · ", keyTags)).append("\n");
        }

        return sb.toString();
    }

    /**
     * 从 Markdown 文本中解析用户画像（简易解析）
     */
    public static UserProfile fromMarkdown(String markdown) {
        UserProfile profile = new UserProfile();
        if (markdown == null || markdown.trim().isEmpty()) return profile;

        String currentSection = null;

        for (String line : markdown.split("\n")) {
            String trimmed = line.trim();

            // 识别章节
            if (trimmed.startsWith("## 基本信息")) {
                currentSection = "basicInfo";
                continue;
            } else if (trimmed.startsWith("## 偏好与习惯")) {
                currentSection = "preferences";
                continue;
            } else if (trimmed.startsWith("## 兴趣领域")) {
                currentSection = "interests";
                continue;
            } else if (trimmed.startsWith("## 沟通风格")) {
                currentSection = "communicationStyle";
                continue;
            } else if (trimmed.startsWith("## 关键标签")) {
                currentSection = "keyTags";
                continue;
            }

            if (currentSection == null) continue;

            // 解析列表项
            if (trimmed.startsWith("- ")) {
                String value = trimmed.substring(2);

                switch (currentSection) {
                    case "basicInfo":
                        // 格式：- **key**: value
                        int boldEnd = value.indexOf("**: ");
                        if (boldEnd > 0 && value.startsWith("**")) {
                            String key = value.substring(2, boldEnd);
                            String val = value.substring(boldEnd + 4);
                            profile.basicInfo.put(key, val);
                        }
                        break;
                    case "preferences":
                        // 去掉加粗前缀
                        value = stripBoldPrefix(value);
                        if (!value.equals("暂无")) profile.preferences.add(value);
                        break;
                    case "interests":
                        value = stripBoldPrefix(value);
                        if (!value.equals("暂无")) profile.interests.add(value);
                        break;
                    case "communicationStyle":
                        boldEnd = value.indexOf("**: ");
                        if (boldEnd > 0 && value.startsWith("**")) {
                            String key = value.substring(2, boldEnd);
                            String val = value.substring(boldEnd + 4);
                            profile.communicationStyle.put(key, val);
                        }
                        break;
                    case "keyTags":
                        // 关键标签用 " · " 分隔
                        for (String tag : value.split("·")) {
                            tag = tag.trim();
                            if (!tag.isEmpty() && !tag.equals("暂无")) {
                                profile.keyTags.add(tag);
                            }
                        }
                        currentSection = null; // 关键标签只有一行
                        break;
                }
            }
        }

        return profile;
    }

    private static String stripBoldPrefix(String value) {
        int idx = value.indexOf("**: ");
        if (idx > 0 && value.startsWith("**")) {
            return value.substring(idx + 4);
        }
        return value;
    }

    private static String formatTime(long ts) {
        if (ts <= 0) return "未知";
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm");
        return sdf.format(new Date(ts));
    }

    // ==================== Getters & Setters ====================

    public Map<String, String> getBasicInfo() { return basicInfo; }
    public void setBasicInfo(Map<String, String> basicInfo) { this.basicInfo = basicInfo; }

    public List<String> getPreferences() { return preferences; }
    public void setPreferences(List<String> preferences) { this.preferences = preferences; }

    public List<String> getInterests() { return interests; }
    public void setInterests(List<String> interests) { this.interests = interests; }

    public Map<String, String> getCommunicationStyle() { return communicationStyle; }
    public void setCommunicationStyle(Map<String, String> communicationStyle) { this.communicationStyle = communicationStyle; }

    public List<String> getKeyTags() { return keyTags; }
    public void setKeyTags(List<String> keyTags) { this.keyTags = keyTags; }

    public long getLastUpdatedAt() { return lastUpdatedAt; }
    public void setLastUpdatedAt(long lastUpdatedAt) { this.lastUpdatedAt = lastUpdatedAt; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    /**
     * 转为前端展示的 Map
     */
    public Map<String, Object> toDisplayMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("basicInfo", basicInfo);
        map.put("preferences", preferences);
        map.put("interests", interests);
        map.put("communicationStyle", communicationStyle);
        map.put("keyTags", keyTags);
        map.put("lastUpdatedAt", lastUpdatedAt);
        map.put("version", version);
        return map;
    }
}
