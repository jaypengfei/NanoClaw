package com.nano.claw.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 记忆文件存储 - 基于 Markdown 文件的本地持久化存储
 * <p>
 * 文件结构：
 * <pre>
 *   {base-dir}/
 *     memory.md          ← 全局持久记忆（压缩后的精华）
 *     user-profile.md    ← 结构化用户画像
 *     memory-2026-05-19.md  ← 每日记忆详情
 *     memory-2026-05-18.md
 *     ...
 * </pre>
 *
 * @author Jason
 * @description 记忆文件存储
 * @date 2026/5/19
 */
public class MemoryFileStore {

    private static final Logger log = LoggerFactory.getLogger(MemoryFileStore.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final Path baseDir;

    public MemoryFileStore(String baseDirPath) {
        this.baseDir = Paths.get(baseDirPath);
        ensureDirectoryExists();
        log.info("[MEM-STORE] 记忆存储目录: {}", baseDir.toAbsolutePath());
    }

    // ==================== memory.md（全局持久记忆） ====================

    /**
     * 读取全局持久记忆
     *
     * @return 记忆内容，文件不存在返回空字符串
     */
    public String readGlobalMemory() {
        return readFile(resolve("memory.md"));
    }

    /**
     * 写入全局持久记忆（覆盖）
     */
    public void writeGlobalMemory(String content) {
        writeFile(resolve("memory.md"), content);
        log.info("[MEM-STORE] 全局记忆已写入, 长度: {} 字符", content != null ? content.length() : 0);
    }

    /**
     * 追加到全局持久记忆
     */
    public void appendGlobalMemory(String content) {
        appendFile(resolve("memory.md"), content);
        log.info("[MEM-STORE] 全局记忆已追加, 长度: {} 字符", content != null ? content.length() : 0);
    }

    // ==================== user-profile.md（用户画像） ====================

    /**
     * 读取用户画像
     */
    public String readUserProfile() {
        return readFile(resolve("user-profile.md"));
    }

    /**
     * 写入用户画像（覆盖）
     */
    public void writeUserProfile(String content) {
        writeFile(resolve("user-profile.md"), content);
        log.info("[MEM-STORE] 用户画像已写入, 长度: {} 字符", content != null ? content.length() : 0);
    }

    // ==================== memory-yyyy-mm-dd.md（每日记忆） ====================

    /**
     * 读取指定日期的记忆
     */
    public String readDailyMemory(LocalDate date) {
        return readFile(resolve("memory-" + date.format(DATE_FMT) + ".md"));
    }

    /**
     * 读取今天的记忆
     */
    public String readTodayMemory() {
        return readDailyMemory(LocalDate.now());
    }

    /**
     * 写入指定日期的记忆（覆盖）
     */
    public void writeDailyMemory(LocalDate date, String content) {
        writeFile(resolve("memory-" + date.format(DATE_FMT) + ".md"), content);
        log.info("[MEM-STORE] 每日记忆已写入: {}, 长度: {}", date.format(DATE_FMT), content != null ? content.length() : 0);
    }

    /**
     * 追加到今天的每日记忆
     */
    public void appendTodayMemory(String content) {
        String filename = "memory-" + LocalDate.now().format(DATE_FMT) + ".md";
        appendFile(resolve(filename), content);
        log.info("[MEM-STORE] 每日记忆已追加: {}", filename);
    }

    // ==================== user-queries.md（用户问句历史） ====================

    private static final DateTimeFormatter QUERY_TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 追加一条用户问句到 user-queries.md（单行存储，换行转义）
     */
    public void appendUserQuery(String query) {
        if (query == null || query.trim().isEmpty()) return;
        String timestamp = LocalDateTime.now().format(QUERY_TS_FMT);
        String safeQuery = query.replace("\\", "\\\\").replace("\r", "").replace("\n", "\\n");
        String line = "[" + timestamp + "] " + safeQuery + "\n";
        appendFile(resolve("user-queries.md"), line);
    }

    /**
     * 读取全部用户问句列表（按追加顺序返回）
     */
    public List<String> readAllUserQueries() {
        String content = readFile(resolve("user-queries.md"));
        if (content == null || content.trim().isEmpty()) return Collections.emptyList();
        List<String> result = new ArrayList<>();
        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return result;
    }

    /**
     * 读取最近 N 条用户问句（按时间升序返回）
     */
    public List<String> readRecentUserQueries(int n) {
        List<String> all = readAllUserQueries();
        if (all.size() <= n) return all;
        return new ArrayList<>(all.subList(all.size() - n, all.size()));
    }

    /**
     * 列出所有每日记忆文件（按日期降序）
     */
    public List<LocalDate> listDailyMemoryDates() {
        try {
            if (!Files.exists(baseDir)) return Collections.emptyList();

            return Files.list(baseDir)
                    .filter(p -> p.getFileName().toString().matches("memory-\\d{4}-\\d{2}-\\d{2}\\.md"))
                    .map(p -> {
                        String name = p.getFileName().toString();
                        String dateStr = name.replace("memory-", "").replace(".md", "");
                        return LocalDate.parse(dateStr, DATE_FMT);
                    })
                    .sorted(Comparator.reverseOrder())
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.error("[MEM-STORE] 列出每日记忆文件失败", e);
            return Collections.emptyList();
        }
    }

    /**
     * 读取最近N天的每日记忆（合并为一个字符串）
     */
    public String readRecentDailyMemories(int days) {
        List<LocalDate> dates = listDailyMemoryDates();
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (LocalDate date : dates) {
            if (count >= days) {
                break;
            }
            String content = readDailyMemory(date);
            if (content != null && !content.trim().isEmpty()) {
                if (sb.length() > 0) {
                    sb.append("\n---\n\n");
                }
                sb.append("## ").append(date.format(DATE_FMT)).append("\n\n").append(content);
                count++;
            }
        }
        return sb.toString();
    }

    /**
     * 清理超过指定天数的每日记忆文件
     */
    public void cleanOldDailyMemories(int maxRetentionDays) {
        LocalDate threshold = LocalDate.now().minusDays(maxRetentionDays);
        List<LocalDate> dates = listDailyMemoryDates();
        int cleaned = 0;
        for (LocalDate date : dates) {
            if (date.isBefore(threshold)) {
                Path file = resolve("memory-" + date.format(DATE_FMT) + ".md");
                try {
                    Files.deleteIfExists(file);
                    cleaned++;
                } catch (IOException e) {
                    log.warn("[MEM-STORE] 删除过期记忆文件失败: {}", file, e);
                }
            }
        }
        if (cleaned > 0) {
            log.info("[MEM-STORE] 清理过期每日记忆: {} 个文件", cleaned);
        }
    }

    // ==================== 通用读写方法 ====================

    /**
     * 获取记忆目录下的所有文件信息
     */
    public Map<String, Object> getStorageInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("baseDir", baseDir.toAbsolutePath().toString());

        String global = readGlobalMemory();
        info.put("globalMemoryLength", global != null ? global.length() : 0);

        String profile = readUserProfile();
        info.put("userProfileLength", profile != null ? profile.length() : 0);

        List<LocalDate> dates = listDailyMemoryDates();
        info.put("dailyMemoryCount", dates.size());
        if (!dates.isEmpty()) {
            info.put("latestDailyMemory", dates.get(0).format(DATE_FMT));
            info.put("oldestDailyMemory", dates.get(dates.size() - 1).format(DATE_FMT));
        }

        return info;
    }

    // ==================== 内部工具方法 ====================

    private Path resolve(String filename) {
        return baseDir.resolve(filename);
    }

    private void ensureDirectoryExists() {
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            log.error("[MEM-STORE] 创建记忆目录失败: {}", baseDir, e);
        }
    }

    private String readFile(Path file) {
        if (!Files.exists(file)) {
            return "";
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("[MEM-STORE] 读取文件失败: {}", file, e);
            return "";
        }
    }

    private void writeFile(Path file, String content) {
        try {
            ensureDirectoryExists();
            Files.write(file, content != null ? content.getBytes(StandardCharsets.UTF_8) : new byte[0]);
        } catch (IOException e) {
            log.error("[MEM-STORE] 写入文件失败: {}", file, e);
        }
    }

    private void appendFile(Path file, String content) {
        try {
            ensureDirectoryExists();
            // 文件不存在时先创建
            if (!Files.exists(file)) {
                Files.write(file, new byte[0]);
            }
            // 追加内容
            try (Writer writer = new OutputStreamWriter(
                    new FileOutputStream(file.toFile(), true), StandardCharsets.UTF_8)) {
                writer.write(content);
            }
        } catch (IOException e) {
            log.error("[MEM-STORE] 追加文件失败: {}", file, e);
        }
    }
}
