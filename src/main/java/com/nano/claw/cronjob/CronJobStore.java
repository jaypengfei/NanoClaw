package com.nano.claw.cronjob;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * 定时任务持久化存储
 */
@Service
public class CronJobStore {

    private static final Logger log = LoggerFactory.getLogger(CronJobStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<CronJob>> JOB_LIST_TYPE = new TypeReference<List<CronJob>>() { };

    static {
        MAPPER.enable(SerializationFeature.INDENT_OUTPUT);
    }

    @Value("${nanoclaw.cronjob.base-dir:./data/cronjobs}")
    private String baseDir;

    private Path jobsFile;

    @PostConstruct
    public void init() {
        Path dir = Paths.get(baseDir);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("[CRON-STORE] 创建定时任务存储目录失败: " + dir.toAbsolutePath(), e);
        }
        jobsFile = dir.resolve("jobs.json");
        log.info("[CRON-STORE] 定时任务存储文件: {}", jobsFile.toAbsolutePath());
    }

    public synchronized List<CronJob> loadAll() {
        ensureInitialized();
        if (!Files.exists(jobsFile)) {
            return Collections.emptyList();
        }

        try {
            List<CronJob> jobs = MAPPER.readValue(jobsFile.toFile(), JOB_LIST_TYPE);
            return jobs == null ? Collections.<CronJob>emptyList() : jobs;
        } catch (IOException e) {
            throw new IllegalStateException("[CRON-STORE] 加载定时任务失败: " + jobsFile.toAbsolutePath(), e);
        }
    }

    public synchronized void saveAll(Collection<CronJob> jobs) {
        ensureInitialized();

        Path tempFile = jobsFile.resolveSibling(jobsFile.getFileName().toString() + ".tmp");
        try {
            MAPPER.writeValue(tempFile.toFile(), new ArrayList<>(jobs));
            moveWithFallback(tempFile, jobsFile);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException cleanupError) {
                log.warn("[CRON-STORE] 清理临时文件失败: {}", tempFile.toAbsolutePath(), cleanupError);
            }
            throw new IllegalStateException("[CRON-STORE] 保存定时任务失败: " + jobsFile.toAbsolutePath(), e);
        }
    }

    private void moveWithFallback(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void ensureInitialized() {
        if (jobsFile == null) {
            init();
        }
    }
}
