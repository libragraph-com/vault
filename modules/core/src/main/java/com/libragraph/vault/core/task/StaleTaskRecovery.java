package com.libragraph.vault.core.task;

import com.libragraph.vault.core.dao.TaskDao;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jdbi.v3.core.Jdbi;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;

import static io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP;

@ApplicationScoped
public class StaleTaskRecovery {

    private static final Logger log = Logger.getLogger(StaleTaskRecovery.class);

    @Inject
    Jdbi jdbi;

    @ConfigProperty(name = "vault.tasks.claim-lease-minutes", defaultValue = "5")
    int claimLeaseMinutes;

    @Scheduled(every = "30s", concurrentExecution = SKIP)
    public void sweep() {
        recoverStaleBackground();
        recoverExpiredLeases();
    }

    void recoverStaleBackground() {
        jdbi.useExtension(TaskDao.class, dao -> {
            var stale = dao.findStaleBackground(TaskStatus.BACKGROUND);
            for (var task : stale) {
                dao.failTerminal(task.id(), TaskStatus.DEAD,
                        "{\"message\":\"Background task expired\"}", false);
                log.warnf("Task %d (type=%s) expired — marked DEAD", task.id(), task.type());
            }
        });
    }

    void recoverExpiredLeases() {
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(claimLeaseMinutes));
        jdbi.useExtension(TaskDao.class, dao -> {
            var stale = dao.findStaleInProgress(TaskStatus.IN_PROGRESS, cutoff);
            for (var task : stale) {
                dao.returnToOpen(task.id(), TaskStatus.OPEN);
                log.warnf("Task %d (type=%s) lease expired — returned to OPEN (retry #%d)",
                        task.id(), task.type(), task.retryCount() + 1);
            }
        });
    }
}
