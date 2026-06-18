package com.nano.claw.cronjob;

import com.nano.claw.agent.common.ChatResponse;
import com.nano.claw.flow.ChatFlow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CronJobManagerPersistenceTests {

    @TempDir
    Path tempDir;

    @Test
    void shouldReloadPersistedActiveJobsAfterRestart() {
        CronJobManager manager = createManager();

        CronJob job = createJob();
        CronJob created = manager.addJob(job);
        long initialNextRunAt = created.getNextRunAt();

        manager.destroy();

        CronJobManager restartedManager = createManager();
        CronJob restored = restartedManager.getJob(created.getId());

        assertNotNull(restored);
        assertEquals(CronJob.Status.ACTIVE, restored.getStatus());
        assertEquals(created.getCronExpression(), restored.getCronExpression());
        assertTrue(restored.getNextRunAt() > 0L);
        assertTrue(restored.getNextRunAt() >= initialNextRunAt);

        restartedManager.destroy();
    }

    @Test
    void shouldKeepPausedJobsPausedAfterRestart() {
        CronJobManager manager = createManager();

        CronJob created = manager.addJob(createJob());
        manager.pauseJob(created.getId());
        manager.destroy();

        CronJobManager restartedManager = createManager();
        CronJob restored = restartedManager.getJob(created.getId());

        assertNotNull(restored);
        assertEquals(CronJob.Status.PAUSED, restored.getStatus());
        assertEquals(0L, restored.getNextRunAt());

        restartedManager.destroy();
    }

    @Test
    void shouldPersistExecutionHistoryAfterRestart() {
        CronJobManager manager = createManager();
        ChatFlow chatFlow = mock(ChatFlow.class);
        when(chatFlow.chat(any())).thenReturn(ChatResponse.success("巡检完成，一切正常", "cron-session"));
        manager.setChatFlow(chatFlow);

        CronJob created = manager.addJob(createJob());
        ReflectionTestUtils.invokeMethod(manager, "executeJob", created);
        ReflectionTestUtils.invokeMethod(manager, "persistJobs");
        manager.destroy();

        CronJobManager restartedManager = createManager();
        CronJob restored = restartedManager.getJob(created.getId());

        assertNotNull(restored);
        assertEquals(1, restored.getRunCount());
        assertEquals(1, restored.getExecutionHistory().size());
        assertTrue(restored.getLastResult().contains("巡检完成"));

        restartedManager.destroy();
    }

    @Test
    void shouldKeepQuartzStyleWeeklyCronSemantics() {
        CronJobManager manager = createManager();
        ZonedDateTime mondayMorning = ZonedDateTime.of(2026, 6, 15, 8, 0, 0, 0, ZoneId.systemDefault());

        Instant nextExecution = ReflectionTestUtils.invokeMethod(
                manager,
                "calculateNextExecution",
                "0 0 9 ? * 2",
                mondayMorning.toInstant()
        );

        ZonedDateTime scheduledTime = ZonedDateTime.ofInstant(nextExecution, ZoneId.systemDefault());
        assertEquals(DayOfWeek.MONDAY, scheduledTime.getDayOfWeek());
        assertEquals(9, scheduledTime.getHour());

        manager.destroy();
    }

    private CronJobManager createManager() {
        CronJobStore store = new CronJobStore();
        ReflectionTestUtils.setField(store, "baseDir", tempDir.toString());
        store.init();

        CronJobManager manager = new CronJobManager(store);
        manager.init();
        return manager;
    }

    private CronJob createJob() {
        CronJob job = new CronJob();
        job.setName("日报任务");
        job.setDescription("每天生成日报");
        job.setCronExpression("0 0 9 * * ?");
        job.setScheduleDesc("每天 9:00");
        job.setQuery("生成日报");
        return job;
    }
}
