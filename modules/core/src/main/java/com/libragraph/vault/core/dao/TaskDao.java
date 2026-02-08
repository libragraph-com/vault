package com.libragraph.vault.core.dao;

import com.libragraph.vault.core.task.TaskRecord;
import com.libragraph.vault.core.task.TaskStatus;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterColumnMapper;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.GetGeneratedKeys;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@RegisterColumnMapper(TaskStatusColumnMapper.class)
@RegisterArgumentFactory(TaskStatusArgumentFactory.class)
@RegisterConstructorMapper(TaskRecord.class)
public interface TaskDao {

    @SqlUpdate("INSERT INTO task (tenant_id, parent_id, type, status, priority, input, retryable) " +
            "VALUES (:tenantId, :parentId, :type, :status, :priority, CAST(:input AS jsonb), :retryable)")
    @GetGeneratedKeys("id")
    int insert(@Bind("tenantId") int tenantId,
               @Bind("parentId") Integer parentId,
               @Bind("type") String type,
               @Bind("status") TaskStatus status,
               @Bind("priority") int priority,
               @Bind("input") String input,
               @Bind("retryable") Boolean retryable);

    @SqlQuery("SELECT * FROM task WHERE id = :id")
    Optional<TaskRecord> findById(@Bind("id") int id);

    @SqlQuery("SELECT * FROM task WHERE parent_id = :parentId")
    List<TaskRecord> findByParent(@Bind("parentId") int parentId);

    @SqlQuery("SELECT * FROM task WHERE status = :status")
    List<TaskRecord> findByStatus(@Bind("status") TaskStatus status);

    @SqlUpdate("UPDATE task SET status = :status, output = CAST(:output AS jsonb), " +
            "completed_at = now() WHERE id = :id")
    void complete(@Bind("id") int id, @Bind("status") TaskStatus status, @Bind("output") String output);

    @SqlUpdate("UPDATE task SET status = :status, expires_at = :expiresAt WHERE id = :id")
    void setBackground(@Bind("id") int id, @Bind("status") TaskStatus status,
                       @Bind("expiresAt") Instant expiresAt);

    @SqlUpdate("UPDATE task SET status = :status, executor = NULL, claimed_at = NULL, " +
            "retry_count = retry_count + 1 WHERE id = :id")
    void returnToOpen(@Bind("id") int id, @Bind("status") TaskStatus status);

    @SqlUpdate("UPDATE task SET status = :status, output = CAST(:errorJson AS jsonb), " +
            "retryable = :retryable, completed_at = now() WHERE id = :id")
    void failTerminal(@Bind("id") int id,
                      @Bind("status") TaskStatus status,
                      @Bind("errorJson") String errorJson,
                      @Bind("retryable") Boolean retryable);

    @SqlUpdate("UPDATE task SET status = :newStatus WHERE id = :id")
    void updateStatus(@Bind("id") int id, @Bind("newStatus") TaskStatus newStatus);

    @SqlUpdate("INSERT INTO task_task_dep (task_id, depends_on) VALUES (:taskId, :dependsOn) " +
            "ON CONFLICT DO NOTHING")
    void insertTaskDep(@Bind("taskId") int taskId, @Bind("dependsOn") int dependsOn);

    @SqlQuery("SELECT COUNT(*) FROM task_task_dep d " +
            "JOIN task t ON t.id = d.depends_on " +
            "WHERE d.task_id = :taskId AND t.status <> :completeStatus")
    int countIncompleteTaskDeps(@Bind("taskId") int taskId,
                                @Bind("completeStatus") TaskStatus completeStatus);

    @SqlQuery("SELECT d.task_id FROM task_task_dep d " +
            "WHERE d.depends_on = :completedTaskId")
    List<Integer> findBlockedByCompletedTask(@Bind("completedTaskId") int completedTaskId);

    @SqlUpdate("INSERT INTO task_resource_dep (task_id, depends_on) " +
            "SELECT :taskId, id FROM task_resource WHERE name = :resourceName")
    void insertResourceDep(@Bind("taskId") int taskId, @Bind("resourceName") String resourceName);

    @SqlQuery("SELECT * FROM task WHERE status = :status AND claimed_at < :cutoff " +
            "FOR UPDATE SKIP LOCKED")
    List<TaskRecord> findStaleInProgress(@Bind("status") TaskStatus status,
                                         @Bind("cutoff") Instant cutoff);

    @SqlQuery("SELECT * FROM task WHERE status = :status AND expires_at < now() " +
            "FOR UPDATE SKIP LOCKED")
    List<TaskRecord> findStaleBackground(@Bind("status") TaskStatus status);

    /**
     * Claims the next available task for the given node, considering resource dependencies.
     * Uses a CTE with FOR UPDATE SKIP LOCKED for concurrent safety.
     */
    default Optional<TaskRecord> claimNext(Handle handle, int nodeId, Collection<String> availableServices) {
        String[] services = availableServices.toArray(new String[0]);

        String sql = """
                WITH available AS (
                    SELECT t.id
                    FROM task t
                    WHERE t.status = :statusOpen
                      AND t.executor IS NULL
                      AND NOT EXISTS (
                          SELECT 1 FROM task_resource_dep trd
                          JOIN task_resource tr ON tr.id = trd.depends_on
                          WHERE trd.task_id = t.id
                            AND tr.name <> ALL(:services)
                      )
                      AND NOT EXISTS (
                          SELECT 1 FROM task_resource_dep trd
                          JOIN task_resource tr ON tr.id = trd.depends_on
                          WHERE trd.task_id = t.id
                            AND tr.max_concurrency IS NOT NULL
                            AND (SELECT COUNT(*) FROM task t2
                                 JOIN task_resource_dep trd2 ON trd2.task_id = t2.id
                                 WHERE trd2.depends_on = tr.id AND t2.status = :statusInProgress
                                ) >= tr.max_concurrency
                      )
                    ORDER BY t.priority DESC, t.created_at
                    LIMIT 1
                    FOR UPDATE OF t SKIP LOCKED
                )
                UPDATE task SET status = :statusInProgress, executor = :nodeId, claimed_at = now()
                FROM available
                WHERE task.id = available.id
                RETURNING task.*
                """;

        return handle.createQuery(sql)
                .bind("nodeId", nodeId)
                .bind("services", services)
                .bind("statusOpen", (short) TaskStatus.OPEN.id())
                .bind("statusInProgress", (short) TaskStatus.IN_PROGRESS.id())
                .registerColumnMapper(new TaskStatusColumnMapper())
                .mapTo(TaskRecord.class)
                .findFirst();
    }
}
