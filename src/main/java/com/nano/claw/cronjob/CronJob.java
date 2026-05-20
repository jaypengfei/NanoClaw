package com.nano.claw.cronjob;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 定时任务实体
 * <p>
 * 表示一个由用户通过自然语言创建的定时任务，
 * 系统按 cron 表达式周期性执行，并通过 Agent 处理任务内容。
 *
 * @author Jason
 * @description 定时任务数据模型
 * @date 2026/5/19
 */
public class CronJob {

    /**
     * 任务状态枚举
     */
    public enum Status {
        /** 活跃，正常调度 */
        ACTIVE,
        /** 已暂停 */
        PAUSED,
        /** 执行出错 */
        ERROR
    }

    /** 任务唯一ID */
    private String id;

    /** 任务名称（由LLM从自然语言中提取） */
    private String name;

    /** 任务描述 */
    private String description;

    /** cron 表达式（6位：秒 分 时 日 月 周） */
    private String cronExpression;

    /** 人类可读的调度描述（如"每天上午9:00"） */
    private String scheduleDesc;

    /** 任务执行的自然语言查询内容 */
    private String query;

    /** 任务状态 */
    private Status status;

    /** 创建时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private long createdAt;

    /** 上次执行时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private long lastRunAt;

    /** 下次预计执行时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private long nextRunAt;

    /** 已执行次数 */
    private int runCount;

    /** 最近一次执行结果 */
    private String lastResult;

    /** 执行历史（最近10条） */
    private List<ExecutionRecord> executionHistory;

    public CronJob() {
        this.id = UUID.randomUUID().toString().substring(0, 8);
        this.status = Status.ACTIVE;
        this.createdAt = System.currentTimeMillis();
        this.runCount = 0;
        this.executionHistory = new ArrayList<>();
    }

    /**
     * 执行历史记录
     */
    public static class ExecutionRecord {
        /** 执行时间 */
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
        private long executedAt;
        /** 执行结果摘要 */
        private String result;
        /** 是否成功 */
        private boolean success;

        public ExecutionRecord() {
        }

        public ExecutionRecord(long executedAt, String result, boolean success) {
            this.executedAt = executedAt;
            this.result = result;
            this.success = success;
        }

        public long getExecutedAt() { return executedAt; }
        public void setExecutedAt(long executedAt) { this.executedAt = executedAt; }
        public String getResult() { return result; }
        public void setResult(String result) { this.result = result; }
        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }
    }

    // ==================== Getters & Setters ====================

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getCronExpression() { return cronExpression; }
    public void setCronExpression(String cronExpression) { this.cronExpression = cronExpression; }

    public String getScheduleDesc() { return scheduleDesc; }
    public void setScheduleDesc(String scheduleDesc) { this.scheduleDesc = scheduleDesc; }

    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getLastRunAt() { return lastRunAt; }
    public void setLastRunAt(long lastRunAt) { this.lastRunAt = lastRunAt; }

    public long getNextRunAt() { return nextRunAt; }
    public void setNextRunAt(long nextRunAt) { this.nextRunAt = nextRunAt; }

    public int getRunCount() { return runCount; }
    public void setRunCount(int runCount) { this.runCount = runCount; }

    public String getLastResult() { return lastResult; }
    public void setLastResult(String lastResult) { this.lastResult = lastResult; }

    public List<ExecutionRecord> getExecutionHistory() { return executionHistory; }
    public void setExecutionHistory(List<ExecutionRecord> executionHistory) { this.executionHistory = executionHistory; }

    /**
     * 添加执行记录（保留最近10条）
     */
    public void addExecutionRecord(ExecutionRecord record) {
        this.executionHistory.add(0, record);
        if (this.executionHistory.size() > 10) {
            this.executionHistory = this.executionHistory.subList(0, 10);
        }
    }
}
