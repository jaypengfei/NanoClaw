package com.nano.claw.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.Map;

/**
 * 记忆模块 REST API 控制器
 * <p>
 * 提供记忆概览、每日记忆查看、用户画像管理、压缩触发等接口。
 *
 * @author Jason
 * @description 记忆API
 * @date 2026/5/19
 */
@RestController
@RequestMapping("/api/memory")
public class MemoryController {

    private static final Logger log = LoggerFactory.getLogger(MemoryController.class);

    @Resource
    private MemoryService memoryService;

    /**
     * 获取记忆概览
     */
    @GetMapping("/overview")
    public Map<String, Object> overview() {
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("data", memoryService.getMemoryOverview());
        return result;
    }

    /**
     * 获取全局记忆全文
     */
    @GetMapping("/global")
    public Map<String, Object> globalMemory() {
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("content", memoryService.getGlobalMemory());
        return result;
    }

    /**
     * 获取用户画像
     */
    @GetMapping("/profile")
    public Map<String, Object> profile() {
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("markdown", memoryService.getUserProfileMarkdown());
        result.put("profile", memoryService.getUserProfile().toDisplayMap());
        return result;
    }

    /**
     * 获取今天的每日记忆
     */
    @GetMapping("/daily/today")
    public Map<String, Object> todayMemory() {
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("content", memoryService.getTodayMemory());
        return result;
    }

    /**
     * 获取指定日期的每日记忆
     */
    @GetMapping("/daily/{date}")
    public Map<String, Object> dailyMemory(@PathVariable String date) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("date", date);
        result.put("content", memoryService.getDailyMemory(date));
        return result;
    }

    /**
     * 手动写入全局记忆（覆盖）
     */
    @PostMapping("/global")
    public Map<String, Object> writeGlobalMemory(@RequestBody Map<String, String> request) {
        Map<String, Object> result = new HashMap<>();
        String content = request.get("content");
        if (content == null || content.trim().isEmpty()) {
            result.put("success", false);
            result.put("error", "content 不能为空");
            return result;
        }
        memoryService.writeGlobalMemory(content);
        result.put("success", true);
        return result;
    }

    /**
     * 手动写入用户画像（覆盖）
     */
    @PostMapping("/profile")
    public Map<String, Object> writeUserProfile(@RequestBody Map<String, String> request) {
        Map<String, Object> result = new HashMap<>();
        String content = request.get("content");
        if (content == null || content.trim().isEmpty()) {
            result.put("success", false);
            result.put("error", "content 不能为空");
            return result;
        }
        memoryService.writeUserProfile(content);
        result.put("success", true);
        return result;
    }

    /**
     * 手动触发记忆压缩
     */
    @PostMapping("/compress")
    public Map<String, Object> triggerCompress() {
        Map<String, Object> result = new HashMap<>();
        try {
            memoryService.checkAndCompress();
            result.put("success", true);
            result.put("message", "压缩检查已触发");
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return result;
    }

    /**
     * 手动触发用户画像更新
     */
    @PostMapping("/profile/update")
    public Map<String, Object> triggerProfileUpdate() {
        Map<String, Object> result = new HashMap<>();
        try {
            memoryService.triggerProfileUpdate();
            result.put("success", true);
            result.put("message", "用户画像更新已触发");
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return result;
    }
}
