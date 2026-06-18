package com.nano.claw.cronjob;

import com.nano.claw.flow.ChatFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.support.CronExpression;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 定时任务调度管理器
 * <p>
 * 负责定时任务的生命周期管理：创建、调度、暂停、恢复、删除。
 * 使用 ScheduledExecutorService 实现 cron 表达式调度，
 * 任务触发时通过 ChatFlow 执行查询。
 *
 * @author Jason
 * @description 定时任务调度管理器
 * @date 2026/5/19
 */
@Component
public class CronJobManager {

    private static final Logger log = LoggerFactory.getLogger(CronJobManager.class);

    /** 所有定时任务，key=jobId */
    private final Map<String, CronJob> jobs = new ConcurrentHashMap<>();

    /** 调度 Future，key=jobId */
    private final Map<String, ScheduledFuture<?>> scheduledFutures = new ConcurrentHashMap<>();

    /** 生命周期与持久化锁 */
    private final Object lifecycleLock = new Object();

    /** 定时任务持久化存储 */
    private final CronJobStore cronJobStore;

    /** 调度线程池 */
    private ScheduledExecutorService scheduler;

    /** ChatFlow 引用，由 Spring 注入后设置 */
    private ChatFlow chatFlow;

    public CronJobManager(CronJobStore cronJobStore) {
        this.cronJobStore = cronJobStore;
    }

    @PostConstruct
    public void init() {
        // 创建2个核心线程的调度线程池
        scheduler = Executors.newScheduledThreadPool(2, new ThreadFactory() {
            private final AtomicInteger count = new AtomicInteger(0);
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "cronjob-worker-" + count.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });
        restorePersistedJobs();
        log.info("[CRON-MGR] 定时任务管理器初始化完成，已加载 {} 个任务", jobs.size());
    }

    @PreDestroy
    public void destroy() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            log.info("[CRON-MGR] 定时任务管理器已关闭");
        }
    }

    /**
     * 设置 ChatFlow（由 ChatFlow 初始化后调用）
     */
    public void setChatFlow(ChatFlow chatFlow) {
        this.chatFlow = chatFlow;
    }

    /**
     * 添加定时任务并开始调度
     *
     * @param job 定时任务
     * @return 添加后的任务（含计算好的nextRunAt）
     */
    public CronJob addJob(CronJob job) {
        prepareNewJob(job);
        synchronized (lifecycleLock) {
            jobs.put(job.getId(), job);
            try {
                scheduleJob(job);
                persistJobs();
                log.info("[CRON-MGR] 添加定时任务: id={}, name={}, cron={}", job.getId(), job.getName(), job.getCronExpression());
                return job;
            } catch (RuntimeException e) {
                cancelSchedule(job.getId());
                jobs.remove(job.getId());
                throw e;
            }
        }
    }

    /**
     * 删除定时任务
     *
     * @param jobId 任务ID
     * @return 是否删除成功
     */
    public boolean removeJob(String jobId) {
        synchronized (lifecycleLock) {
            CronJob job = jobs.remove(jobId);
            if (job != null) {
                cancelSchedule(jobId);
                try {
                    persistJobs();
                    log.info("[CRON-MGR] 删除定时任务: id={}, name={}", jobId, job.getName());
                    return true;
                } catch (RuntimeException e) {
                    jobs.put(jobId, job);
                    if (job.getStatus() == CronJob.Status.ACTIVE) {
                        scheduleJob(job);
                    }
                    throw e;
                }
            }
            return false;
        }
    }

    /**
     * 暂停定时任务
     *
     * @param jobId 任务ID
     * @return 暂停后的任务，不存在返回null
     */
    public CronJob pauseJob(String jobId) {
        synchronized (lifecycleLock) {
            CronJob job = jobs.get(jobId);
            if (job == null) {
                return null;
            }

            CronJob.Status previousStatus = job.getStatus();
            long previousNextRunAt = job.getNextRunAt();

            job.setStatus(CronJob.Status.PAUSED);
            job.setNextRunAt(0L);
            cancelSchedule(jobId);

            try {
                persistJobs();
                log.info("[CRON-MGR] 暂停定时任务: id={}, name={}", jobId, job.getName());
                return job;
            } catch (RuntimeException e) {
                job.setStatus(previousStatus);
                job.setNextRunAt(previousNextRunAt);
                if (previousStatus == CronJob.Status.ACTIVE) {
                    scheduleJob(job);
                }
                throw e;
            }
        }
    }

    /**
     * 恢复定时任务
     *
     * @param jobId 任务ID
     * @return 恢复后的任务，不存在返回null
     */
    public CronJob resumeJob(String jobId) {
        synchronized (lifecycleLock) {
            CronJob job = jobs.get(jobId);
            if (job == null) {
                return null;
            }

            CronJob.Status previousStatus = job.getStatus();
            long previousNextRunAt = job.getNextRunAt();

            job.setStatus(CronJob.Status.ACTIVE);
            try {
                scheduleJob(job);
                persistJobs();
                log.info("[CRON-MGR] 恢复定时任务: id={}, name={}", jobId, job.getName());
                return job;
            } catch (RuntimeException e) {
                cancelSchedule(jobId);
                job.setStatus(previousStatus);
                job.setNextRunAt(previousNextRunAt);
                if (previousStatus == CronJob.Status.ACTIVE) {
                    scheduleJob(job);
                }
                throw e;
            }
        }
    }

    /**
     * 获取所有定时任务
     */
    public List<CronJob> getAllJobs() {
        return new ArrayList<>(jobs.values());
    }

    /**
     * 根据ID获取定时任务
     *
     * @param jobId 任务ID
     * @return 定时任务，不存在返回null
     */
    public CronJob getJob(String jobId) {
        return jobs.get(jobId);
    }

    /**
     * 获取活跃任务数
     */
    public int activeJobCount() {
        return (int) jobs.values().stream().filter(j -> j.getStatus() == CronJob.Status.ACTIVE).count();
    }

    /**
     * 获取总任务数
     */
    public int totalJobCount() {
        return jobs.size();
    }

    // ==================== 私有方法 ====================

    /**
     * 调度单个任务
     * <p>
     * 按 cron 表达式逐次计算下一次执行时间并进行单次调度，
     * 每次执行结束后重新计算下一次触发时刻，确保重启恢复后仍按真实 cron 语义运行。
     */
    private void scheduleJob(CronJob job) {
        if (job.getStatus() != CronJob.Status.ACTIVE) {
            job.setNextRunAt(0L);
            return;
        }

        if (scheduler == null) {
            throw new IllegalStateException("调度器尚未初始化");
        }

        cancelSchedule(job.getId());

        Instant nextExecution = calculateNextExecution(job.getCronExpression(), Instant.now());
        if (nextExecution == null) {
            throw new IllegalArgumentException("无法计算下次执行时间: " + job.getCronExpression());
        }

        long delayMs = Math.max(0L, nextExecution.toEpochMilli() - System.currentTimeMillis());
        ScheduledFuture<?> future = scheduler.schedule(
                () -> runScheduledJob(job.getId()),
                delayMs,
                TimeUnit.MILLISECONDS
        );
        scheduledFutures.put(job.getId(), future);
        job.setNextRunAt(nextExecution.toEpochMilli());
        log.info("[CRON-MGR] 调度任务: id={}, nextRunAt={}, delay={}ms, cron={}",
                job.getId(), new Date(job.getNextRunAt()), delayMs, job.getCronExpression());
    }

    /**
     * 取消调度
     */
    private void cancelSchedule(String jobId) {
        ScheduledFuture<?> future = scheduledFutures.remove(jobId);
        if (future != null) {
            future.cancel(false);
        }
    }

    /**
     * 执行定时任务
     */
    private void executeJob(CronJob job) {
        log.info("[CRON-MGR] 执行定时任务: id={}, name={}, query={}", job.getId(), job.getName(), job.getQuery());
        job.setLastRunAt(System.currentTimeMillis());

        try {
            if (chatFlow == null) {
                log.warn("[CRON-MGR] ChatFlow 未初始化，跳过执行");
                return;
            }

            // 通过 ChatFlow 执行查询
            com.nano.claw.agent.common.ChatRequest chatRequest = new com.nano.claw.agent.common.ChatRequest();
            chatRequest.setMessage(job.getQuery());
            chatRequest.setChannel("cronjob");

            com.nano.claw.agent.common.ChatResponse response = chatFlow.chat(chatRequest);

            job.setRunCount(job.getRunCount() + 1);

            String resultSummary;
            boolean success;
            if (response.isSuccess()) {
                resultSummary = truncateResult(response.getAnswer());
                success = true;
                log.info("[CRON-MGR] 定时任务执行成功: id={}, name={}", job.getId(), job.getName());
            } else {
                resultSummary = truncateResult("执行失败: " + response.getError());
                success = false;
                log.warn("[CRON-MGR] 定时任务执行失败: id={}, name={}, error={}", job.getId(), job.getName(), response.getError());
            }

            job.setLastResult(resultSummary);
            job.addExecutionRecord(new CronJob.ExecutionRecord(System.currentTimeMillis(), resultSummary, success));
        } catch (Exception e) {
            log.error("[CRON-MGR] 定时任务执行异常: id={}, name={}", job.getId(), job.getName(), e);
            String errorMessage = "执行异常: " + e.getMessage();
            job.setLastResult(errorMessage);
            job.addExecutionRecord(new CronJob.ExecutionRecord(System.currentTimeMillis(), errorMessage, false));
            markJobError(job, errorMessage);
        }
    }

    private void runScheduledJob(String jobId) {
        CronJob job = jobs.get(jobId);
        if (job == null) {
            scheduledFutures.remove(jobId);
            return;
        }

        if (chatFlow == null) {
            log.warn("[CRON-MGR] ChatFlow 未初始化，推迟本轮调度: id={}", jobId);
        } else if (job.getStatus() == CronJob.Status.ACTIVE) {
            executeJob(job);
        }

        synchronized (lifecycleLock) {
            scheduledFutures.remove(jobId);

            CronJob current = jobs.get(jobId);
            if (current == null) {
                return;
            }

            if (current.getStatus() == CronJob.Status.ACTIVE) {
                try {
                    scheduleJob(current);
                } catch (RuntimeException e) {
                    log.error("[CRON-MGR] 重新调度失败: id={}, error={}", current.getId(), e.getMessage());
                    markJobError(current, "重新调度失败: " + e.getMessage());
                }
            } else {
                current.setNextRunAt(0L);
            }

            try {
                persistJobs();
            } catch (RuntimeException e) {
                log.error("[CRON-MGR] 持久化执行结果失败: id={}, error={}", current.getId(), e.getMessage(), e);
            }
        }
    }

    private void restorePersistedJobs() {
        List<CronJob> persistedJobs = cronJobStore.loadAll();
        boolean changed = false;

        synchronized (lifecycleLock) {
            for (CronJob job : persistedJobs) {
                if (!isValidPersistedJob(job)) {
                    changed = true;
                    continue;
                }

                normalizeLoadedJob(job);
                jobs.put(job.getId(), job);
            }

            for (CronJob job : jobs.values()) {
                if (job.getStatus() == CronJob.Status.ACTIVE) {
                    try {
                        scheduleJob(job);
                    } catch (RuntimeException e) {
                        log.error("[CRON-MGR] 恢复定时任务失败: id={}, error={}", job.getId(), e.getMessage());
                        markJobError(job, "恢复调度失败: " + e.getMessage());
                        changed = true;
                    }
                } else if (job.getNextRunAt() != 0L) {
                    job.setNextRunAt(0L);
                    changed = true;
                }
            }

            if (changed) {
                persistJobs();
            }
        }
    }

    private void prepareNewJob(CronJob job) {
        if (job == null) {
            throw new IllegalArgumentException("定时任务不能为空");
        }

        if (job.getId() == null || job.getId().trim().isEmpty()) {
            job.setId(UUID.randomUUID().toString().substring(0, 8));
        }
        if (job.getStatus() == null) {
            job.setStatus(CronJob.Status.ACTIVE);
        }
        if (job.getExecutionHistory() == null) {
            job.setExecutionHistory(new ArrayList<CronJob.ExecutionRecord>());
        }
        if (job.getCronExpression() == null || job.getCronExpression().trim().isEmpty()) {
            throw new IllegalArgumentException("cron表达式不能为空");
        }
    }

    private void normalizeLoadedJob(CronJob job) {
        if (job.getStatus() == null) {
            job.setStatus(CronJob.Status.ACTIVE);
        }
        if (job.getExecutionHistory() == null) {
            job.setExecutionHistory(new ArrayList<CronJob.ExecutionRecord>());
        }
    }

    private boolean isValidPersistedJob(CronJob job) {
        if (job == null) {
            log.warn("[CRON-MGR] 跳过空的持久化任务记录");
            return false;
        }
        if (job.getId() == null || job.getId().trim().isEmpty()) {
            log.warn("[CRON-MGR] 跳过缺少ID的持久化任务记录");
            return false;
        }
        return true;
    }

    private void persistJobs() {
        cronJobStore.saveAll(jobs.values());
    }

    private void markJobError(CronJob job, String errorMessage) {
        job.setStatus(CronJob.Status.ERROR);
        job.setNextRunAt(0L);
        job.setLastResult(errorMessage);
    }

    private String truncateResult(String result) {
        if (result == null) {
            return "";
        }
        if (result.length() > 200) {
            return result.substring(0, 200) + "...";
        }
        return result;
    }

    private Instant calculateNextExecution(String cronExpression, Instant referenceTime) {
        CronExpression expression = CronExpression.parse(normalizeCronExpression(cronExpression));
        ZonedDateTime next = expression.next(ZonedDateTime.ofInstant(referenceTime, ZoneId.systemDefault()));
        if (next == null) {
            return null;
        }
        return next.toInstant();
    }

    /**
     * 兼容项目中现有 Quartz 风格周字段（1=周日，2=周一...7=周六）。
     */
    private String normalizeCronExpression(String cronExpression) {
        String[] parts = cronExpression.trim().split("\\s+");
        if (parts.length != 6) {
            throw new IllegalArgumentException("cron表达式格式错误，应为6位(秒 分 时 日 月 周): " + cronExpression);
        }

        parts[5] = normalizeDayOfWeekField(parts[5]);
        return String.join(" ", parts);
    }

    private String normalizeDayOfWeekField(String field) {
        if (field == null || field.isEmpty() || "?".equals(field) || "*".equals(field) || field.matches(".*[A-Za-z].*")) {
            return field;
        }

        String[] tokens = field.split(",");
        List<String> normalizedTokens = new ArrayList<>();
        for (String token : tokens) {
            normalizedTokens.add(normalizeDayOfWeekToken(token.trim()));
        }
        return String.join(",", normalizedTokens);
    }

    private String normalizeDayOfWeekToken(String token) {
        if (token.isEmpty() || "*".equals(token) || "?".equals(token)) {
            return token;
        }

        if (token.contains("/")) {
            String[] stepParts = token.split("/", 2);
            return normalizeDayOfWeekToken(stepParts[0]) + "/" + stepParts[1];
        }

        if (token.contains("-")) {
            String[] rangeParts = token.split("-", 2);
            return normalizeDayOfWeekToken(rangeParts[0]) + "-" + normalizeDayOfWeekToken(rangeParts[1]);
        }

        try {
            int value = Integer.parseInt(token);
            return String.valueOf(convertQuartzDayOfWeek(value));
        } catch (NumberFormatException e) {
            return token;
        }
    }

    private int convertQuartzDayOfWeek(int value) {
        if (value == 0) {
            return 0;
        }
        if (value >= 1 && value <= 7) {
            return value - 1;
        }
        throw new IllegalArgumentException("周字段超出范围: " + value);
    }
}
