package com.healthtrace.rights.deletion;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/** DELETION_LEDGER / STORAGE_PURGE_TASK 表访问。账本只追加，状态只允许 STORAGE_PENDING → COMPLETED。 */
@Mapper
public interface DeletionMapper {

    int insertLedger(DeletionLedgerEntry entry);

    int insertTask(StoragePurgeTask task);

    /** 待重试任务：创建早于 createdBefore，且从未尝试或上次尝试早于 attemptedBefore。 */
    List<StoragePurgeTask> findDueTasks(@Param("createdBefore") LocalDateTime createdBefore,
                                        @Param("attemptedBefore") LocalDateTime attemptedBefore,
                                        @Param("limit") int limit);

    int markAttempt(@Param("id") long id, @Param("now") LocalDateTime now);

    int deleteTask(@Param("id") long id);

    /** 账本下已无待删对象时转为 COMPLETED；返回 0 表示仍有对象或已完成。 */
    int completeLedgerIfDone(@Param("ledgerId") long ledgerId, @Param("now") LocalDateTime now);
}
