package com.healthtrace.rights.deletion;

import com.healthtrace.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * 存储对象清理 outbox（FR-016/FR-017）。
 * <ul>
 *   <li>{@link #enqueue} 必须在业务删除事务内调用：任务行与业务删除同时提交或同时回滚。</li>
 *   <li>提交后在专用线程池上尽力删除；成功即删除任务行，失败保留并由定时任务重试。</li>
 *   <li>清理失败从不抛给调用方，日志只记录任务 id 与异常类型，不记录对象键。</li>
 * </ul>
 */
@Service
public class StoragePurgeService {

    private static final Logger log = LoggerFactory.getLogger(StoragePurgeService.class);

    /** 刚入队的任务先交给提交后的异步删除，避免与重试扫描同时处理。 */
    static final Duration RETRY_GRACE = Duration.ofMinutes(1);
    /** 两次尝试的最小间隔。 */
    static final Duration RETRY_INTERVAL = Duration.ofMinutes(5);
    private static final int SCAN_BATCH = 50;

    private final DeletionMapper deletionMapper;
    private final StorageService storageService;
    private final Executor executor;
    private final Clock clock;
    private final boolean retryEnabled;

    public StoragePurgeService(DeletionMapper deletionMapper, StorageService storageService,
                               @Qualifier(DeletionConfig.PURGE_EXECUTOR) Executor executor, Clock clock,
                               @Value("${healthtrace.deletion.purge-retry-enabled:true}") boolean retryEnabled) {
        this.deletionMapper = deletionMapper;
        this.storageService = storageService;
        this.executor = executor;
        this.clock = clock;
        this.retryEnabled = retryEnabled;
    }

    /** 登记待删除对象（不关联账本）。空键忽略。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(Collection<String> objectKeys) {
        enqueue(objectKeys, null);
    }

    /** 登记待删除对象并关联账本行；对象全部删除后账本转为 COMPLETED。返回登记数量。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public int enqueue(Collection<String> objectKeys, Long ledgerId) {
        LocalDateTime now = now();
        List<StoragePurgeTask> tasks = new ArrayList<>();
        for (String key : objectKeys) {
            if (key == null || key.isBlank()) {
                continue;
            }
            StoragePurgeTask task = new StoragePurgeTask();
            task.setObjectKey(key);
            task.setLedgerId(ledgerId);
            task.setCreatedAt(now);
            deletionMapper.insertTask(task);
            tasks.add(task);
        }
        if (!tasks.isEmpty()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatch(tasks);
                }
            });
        }
        return tasks.size();
    }

    /** 定时重试：每 5 分钟扫描一次残留任务。测试中以 healthtrace.deletion.purge-retry-enabled=false 关闭。 */
    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    public void retryDue() {
        if (!retryEnabled) {
            return;
        }
        processDue();
    }

    /** 同步处理到期任务（测试与调度直接调用），返回成功删除的数量。 */
    public int processDue() {
        LocalDateTime now = now();
        int done = 0;
        for (StoragePurgeTask task : deletionMapper.findDueTasks(now.minus(RETRY_GRACE),
                now.minus(RETRY_INTERVAL), SCAN_BATCH)) {
            if (attempt(task)) {
                done++;
            }
        }
        return done;
    }

    private void dispatch(List<StoragePurgeTask> tasks) {
        try {
            executor.execute(() -> tasks.forEach(this::attempt));
        } catch (RuntimeException ex) {
            // 线程池已关闭等：任务行仍在，由定时任务补偿
            log.warn("存储清理任务未能提交到线程池：count={} type={}", tasks.size(), ex.getClass().getSimpleName());
        }
    }

    /** 尝试删除一个对象；任何异常都被吞掉并记为一次失败。 */
    boolean attempt(StoragePurgeTask task) {
        try {
            storageService.delete(task.getObjectKey());
        } catch (RuntimeException ex) {
            log.warn("存储对象清理失败，稍后重试：taskId={} attempts={} type={}",
                    task.getId(), task.getAttempts() + 1, ex.getClass().getSimpleName());
            markAttemptQuietly(task);
            return false;
        }
        try {
            deletionMapper.deleteTask(task.getId());
            if (task.getLedgerId() != null) {
                deletionMapper.completeLedgerIfDone(task.getLedgerId(), now());
            }
            return true;
        } catch (RuntimeException ex) {
            // 对象已删除，任务行残留只会导致一次幂等的重复删除
            log.warn("存储清理任务状态更新失败：taskId={} type={}", task.getId(), ex.getClass().getSimpleName());
            return false;
        }
    }

    private void markAttemptQuietly(StoragePurgeTask task) {
        try {
            deletionMapper.markAttempt(task.getId(), now());
        } catch (RuntimeException ex) {
            log.warn("存储清理任务记录失败次数出错：taskId={} type={}", task.getId(), ex.getClass().getSimpleName());
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
    }
}
