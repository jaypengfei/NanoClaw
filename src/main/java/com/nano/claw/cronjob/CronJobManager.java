package com.nano.claw.cronjob;

import com.nano.claw.flow.ChatFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.*;
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

    /** 调度线程池 */
    private ScheduledExecutorService scheduler;

    /** ChatFlow 引用，由 Spring 注入后设置 */
    private ChatFlow chatFlow;

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
        log.info("[CRON-MGR] 定时任务管理器初始化完成");
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
        jobs.put(job.getId(), job);
        scheduleJob(job);
        log.info("[CRON-MGR] 添加定时任务: id={}, name={}, cron={}", job.getId(), job.getName(), job.getCronExpression());
        return job;
    }

    /**
     * 删除定时任务
     *
     * @param jobId 任务ID
     * @return 是否删除成功
     */
    public boolean removeJob(String jobId) {
        CronJob job = jobs.remove(jobId);
        if (job != null) {
            cancelSchedule(jobId);
            log.info("[CRON-MGR] 删除定时任务: id={}, name={}", jobId, job.getName());
            return true;
        }
        return false;
    }

    /**
     * 暂停定时任务
     *
     * @param jobId 任务ID
     * @return 暂停后的任务，不存在返回null
     */
    public CronJob pauseJob(String jobId) {
        CronJob job = jobs.get(jobId);
        if (job == null) {
            return null;
        }

        job.setStatus(CronJob.Status.PAUSED);
        cancelSchedule(jobId);
        log.info("[CRON-MGR] 暂停定时任务: id={}, name={}", jobId, job.getName());
        return job;
    }

    /**
     * 恢复定时任务
     *
     * @param jobId 任务ID
     * @return 恢复后的任务，不存在返回null
     */
    public CronJob resumeJob(String jobId) {
        CronJob job = jobs.get(jobId);
        if (job == null) {
            return null;
        }

        job.setStatus(CronJob.Status.ACTIVE);
        scheduleJob(job);
        log.info("[CRON-MGR] 恢复定时任务: id={}, name={}", jobId, job.getName());
        return job;
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
     * 简化版：将cron表达式解析为固定间隔调度。
     * 对于"每X分钟/小时"类型使用固定间隔；
     * 对于"每天X点"类型计算到下次执行的时间延迟。
     */
    private void scheduleJob(CronJob job) {
        if (job.getStatus() != CronJob.Status.ACTIVE) {
            return;
        }

        cancelSchedule(job.getId());

        try {
            long initialDelay = calculateInitialDelay(job.getCronExpression());
            long period = calculatePeriod(job.getCronExpression());

            if (period > 0) {
                ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                        () -> executeJob(job),
                        initialDelay,
                        period,
                        TimeUnit.MILLISECONDS
                );
                scheduledFutures.put(job.getId(), future);
                job.setNextRunAt(System.currentTimeMillis() + initialDelay);
                log.info("[CRON-MGR] 调度任务: id={}, 首次延迟={}ms, 周期={}ms", job.getId(), initialDelay, period);
            } else {
                // 无法计算周期，使用单次延迟调度
                if (initialDelay > 0) {
                    ScheduledFuture<?> future = scheduler.schedule(
                            () -> {
                                executeJob(job);
                                // 执行完后重新调度（每天型任务）
                                scheduleJob(job);
                            },
                            initialDelay,
                            TimeUnit.MILLISECONDS
                    );
                    scheduledFutures.put(job.getId(), future);
                    job.setNextRunAt(System.currentTimeMillis() + initialDelay);
                    log.info("[CRON-MGR] 单次调度任务: id={}, 延迟={}ms", job.getId(), initialDelay);
                }
            }
        } catch (Exception e) {
            log.error("[CRON-MGR] 调度任务失败: id={}, error={}", job.getId(), e.getMessage());
            job.setStatus(CronJob.Status.ERROR);
        }
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
                resultSummary = response.getAnswer();
                if (resultSummary != null && resultSummary.length() > 200) {
                    resultSummary = resultSummary.substring(0, 200) + "...";
                }
                success = true;
                log.info("[CRON-MGR] 定时任务执行成功: id={}, name={}", job.getId(), job.getName());
            } else {
                resultSummary = "执行失败: " + response.getError();
                success = false;
                log.warn("[CRON-MGR] 定时任务执行失败: id={}, name={}, error={}", job.getId(), job.getName(), response.getError());
            }

            job.setLastResult(resultSummary);
            job.addExecutionRecord(new CronJob.ExecutionRecord(System.currentTimeMillis(), resultSummary, success));

            // 更新下次执行时间
            long period = calculatePeriod(job.getCronExpression());
            if (period > 0) {
                job.setNextRunAt(System.currentTimeMillis() + period);
            } else {
                // 每天型任务，下次执行时间=明天同一时刻
                job.setNextRunAt(System.currentTimeMillis() + 24 * 60 * 60 * 1000L);
            }

        } catch (Exception e) {
            log.error("[CRON-MGR] 定时任务执行异常: id={}, name={}", job.getId(), job.getName(), e);
            job.setLastResult("执行异常: " + e.getMessage());
            job.addExecutionRecord(new CronJob.ExecutionRecord(System.currentTimeMillis(), e.getMessage(), false));
            job.setStatus(CronJob.Status.ERROR);
        }
    }

    /**
     * 计算到下次执行的初始延迟
     * <p>
     * 简化版：解析cron表达式中指定的时/分，计算到今天/明天的延迟
     */
    private long calculateInitialDelay(String cronExpression) {
        String[] parts = cronExpression.trim().split("\\s+");
        if (parts.length != 6) return 60000L; // 默认1分钟后

        try {
            Calendar now = Calendar.getInstance();
            Calendar next = Calendar.getInstance();

            int minute = parseCronPart(parts[1], now.get(Calendar.MINUTE));
            int hour = parseCronPart(parts[2], now.get(Calendar.HOUR_OF_DAY));

            next.set(Calendar.MINUTE, minute);
            next.set(Calendar.SECOND, parseCronPart(parts[0], 0));
            next.set(Calendar.MILLISECOND, 0);

            if (parts[2].equals("*") || parts[2].startsWith("*/")) {
                // 间隔型（每隔X小时），直接从当前时间开始
                return 0;
            }

            next.set(Calendar.HOUR_OF_DAY, hour);

            if (next.before(now)) {
                next.add(Calendar.DAY_OF_MONTH, 1);
            }

            return next.getTimeInMillis() - now.getTimeInMillis();

        } catch (Exception e) {
            return 60000L;
        }
    }

    /**
     * 计算调度周期
     *
     * @return 周期（毫秒），0表示无法计算固定周期（如"每天X点"）
     */
    private long calculatePeriod(String cronExpression) {
        String[] parts = cronExpression.trim().split("\\s+");
        if (parts.length != 6) {
            return 0;
        }

        // 每隔X分钟: "0 */X * * * ?"
        if (parts[1].startsWith("*/")) {
            try {
                int interval = Integer.parseInt(parts[1].substring(2));
                return interval * 60 * 1000L;
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        // 每隔X小时: "0 0 */X * * ?"
        if (parts[2].startsWith("*/")) {
            try {
                int interval = Integer.parseInt(parts[2].substring(2));
                return interval * 60 * 60 * 1000L;
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        // 每天/每周型，无固定周期，返回0（使用单次调度+重新调度方式）
        return 0;
    }

    /**
     * 解析 cron 部分
     */
    private int parseCronPart(String part, int defaultValue) {
        if (part.equals("*") || part.equals("?")) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(part);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
