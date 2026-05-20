package com.nano.claw.cronjob;

import com.nano.claw.llm.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 定时任务 REST API 控制器
 * <p>
 * 提供定时任务的增删查改接口，以及通过自然语言创建定时任务的能力。
 *
 * @author Jason
 * @description 定时任务API
 * @date 2026/5/19
 */
@RestController
@RequestMapping("/api/cron-jobs")
public class CronJobController {

    private static final Logger log = LoggerFactory.getLogger(CronJobController.class);

    @Resource
    private CronJobManager cronJobManager;

    /**
     * 获取所有定时任务列表
     */
    @GetMapping
    public Map<String, Object> listJobs() {
        List<CronJob> jobs = cronJobManager.getAllJobs();
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("jobs", jobs);
        result.put("total", jobs.size());
        result.put("active", cronJobManager.activeJobCount());
        return result;
    }

    /**
     * 获取单个定时任务详情
     */
    @GetMapping("/{id}")
    public Map<String, Object> getJob(@PathVariable String id) {
        CronJob job = cronJobManager.getJob(id);
        Map<String, Object> result = new HashMap<>();
        if (job != null) {
            result.put("success", true);
            result.put("job", job);
        } else {
            result.put("success", false);
            result.put("error", "任务不存在: " + id);
        }
        return result;
    }

    /**
     * 通过自然语言创建定时任务
     * <p>
     * 请求体：{ "message": "每天早上9点提醒我查看邮件" }
     */
    @PostMapping
    public Map<String, Object> createJob(@RequestBody Map<String, String> request) {
        Map<String, Object> result = new HashMap<>();
        String message = request.get("message");

        if (message == null || message.trim().isEmpty()) {
            result.put("success", false);
            result.put("error", "message 参数不能为空");
            return result;
        }

        log.info("[CRON-API] 收到自然语言创建请求: {}", message);

        // 解析自然语言
        CronJobParser.ParseResult parseResult = CronJobParser.parse(message, Model.MINI_MAX);
        if (!parseResult.isSuccess()) {
            result.put("success", false);
            result.put("error", "无法解析为定时任务: " + parseResult.getError());
            return result;
        }

        // 构建 CronJob
        CronJob job = new CronJob();
        job.setName(parseResult.getName());
        job.setCronExpression(parseResult.getCronExpression());
        job.setScheduleDesc(parseResult.getScheduleDesc());
        job.setQuery(parseResult.getQuery());
        job.setDescription(message);

        // 添加到管理器
        CronJob created = cronJobManager.addJob(job);

        result.put("success", true);
        result.put("job", created);
        result.put("parseSource", parseResult.isRuleMatched() ? "rule" : "llm");
        log.info("[CRON-API] 创建定时任务成功: id={}, name={}, cron={}", created.getId(), created.getName(), created.getCronExpression());
        return result;
    }

    /**
     * 通过指定参数直接创建定时任务
     * <p>
     * 请求体：{ "name": "...", "cronExpression": "...", "query": "...", "scheduleDesc": "..." }
     */
    @PostMapping("/direct")
    public Map<String, Object> createJobDirect(@RequestBody Map<String, String> request) {
        Map<String, Object> result = new HashMap<>();

        String name = request.get("name");
        String cronExpression = request.get("cronExpression");
        String query = request.get("query");
        String scheduleDesc = request.get("scheduleDesc");

        if (cronExpression == null || cronExpression.trim().isEmpty()) {
            result.put("success", false);
            result.put("error", "cronExpression 参数不能为空");
            return result;
        }
        if (query == null || query.trim().isEmpty()) {
            result.put("success", false);
            result.put("error", "query 参数不能为空");
            return result;
        }

        // 验证cron表达式基本格式
        String[] parts = cronExpression.trim().split("\\s+");
        if (parts.length != 6) {
            result.put("success", false);
            result.put("error", "cron表达式格式错误，应为6位(秒 分 时 日 月 周)");
            return result;
        }

        CronJob job = new CronJob();
        job.setName(name != null ? name : "定时任务");
        job.setCronExpression(cronExpression);
        job.setQuery(query);
        job.setScheduleDesc(scheduleDesc != null ? scheduleDesc : cronExpression);

        CronJob created = cronJobManager.addJob(job);

        result.put("success", true);
        result.put("job", created);
        return result;
    }

    /**
     * 删除定时任务
     */
    @DeleteMapping("/{id}")
    public Map<String, Object> deleteJob(@PathVariable String id) {
        Map<String, Object> result = new HashMap<>();
        boolean deleted = cronJobManager.removeJob(id);
        result.put("success", deleted);
        if (!deleted) {
            result.put("error", "任务不存在: " + id);
        }
        return result;
    }

    /**
     * 暂停定时任务
     */
    @PutMapping("/{id}/pause")
    public Map<String, Object> pauseJob(@PathVariable String id) {
        Map<String, Object> result = new HashMap<>();
        CronJob job = cronJobManager.pauseJob(id);
        if (job != null) {
            result.put("success", true);
            result.put("job", job);
        } else {
            result.put("success", false);
            result.put("error", "任务不存在: " + id);
        }
        return result;
    }

    /**
     * 恢复定时任务
     */
    @PutMapping("/{id}/resume")
    public Map<String, Object> resumeJob(@PathVariable String id) {
        Map<String, Object> result = new HashMap<>();
        CronJob job = cronJobManager.resumeJob(id);
        if (job != null) {
            result.put("success", true);
            result.put("job", job);
        } else {
            result.put("success", false);
            result.put("error", "任务不存在: " + id);
        }
        return result;
    }
}
