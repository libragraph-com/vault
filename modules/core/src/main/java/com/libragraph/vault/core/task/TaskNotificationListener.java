package com.libragraph.vault.core.task;

import io.agroal.api.AgroalDataSource;
import org.jboss.logging.Logger;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

class TaskNotificationListener {

    private static final Logger log = Logger.getLogger(TaskNotificationListener.class);

    private final AgroalDataSource dataSource;
    private volatile boolean running;
    private Connection connection;
    private Thread listenerThread;
    private final Semaphore workAvailable = new Semaphore(0);
    private IntConsumer completionHandler;

    TaskNotificationListener(AgroalDataSource dataSource) {
        this.dataSource = dataSource;
    }

    void start(IntConsumer onTaskCompleted) {
        this.completionHandler = onTaskCompleted;
        this.running = true;

        try {
            connection = dataSource.getConnection();
            connection.setAutoCommit(true);

            try (Statement stmt = connection.createStatement()) {
                stmt.execute("LISTEN task_available");
                stmt.execute("LISTEN task_completed");
            }

            listenerThread = Thread.ofVirtual()
                    .name("task-notification-listener")
                    .start(this::listenLoop);

            log.info("TaskNotificationListener started");
        } catch (SQLException e) {
            running = false;
            throw new RuntimeException("Failed to start TaskNotificationListener", e);
        }
    }

    void stop() {
        running = false;
        if (listenerThread != null) {
            listenerThread.interrupt();
        }
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                log.warn("Error closing notification connection", e);
            }
        }
        // Release any waiting workers
        workAvailable.release(Integer.MAX_VALUE / 2);
        log.info("TaskNotificationListener stopped");
    }

    boolean awaitWork(long timeout, TimeUnit unit) throws InterruptedException {
        return workAvailable.tryAcquire(timeout, unit);
    }

    private void listenLoop() {
        while (running) {
            try {
                PGConnection pgConn = connection.unwrap(PGConnection.class);
                PGNotification[] notifications = pgConn.getNotifications(100);

                if (notifications != null) {
                    for (PGNotification n : notifications) {
                        switch (n.getName()) {
                            case "task_available" -> workAvailable.release();
                            case "task_completed" -> {
                                try {
                                    int taskId = Integer.parseInt(n.getParameter());
                                    if (completionHandler != null) {
                                        completionHandler.accept(taskId);
                                    }
                                } catch (NumberFormatException e) {
                                    log.warnf("Invalid task_completed payload: %s", n.getParameter());
                                }
                            }
                        }
                    }
                }
            } catch (SQLException e) {
                if (running) {
                    log.warn("Error in notification listener loop", e);
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
    }
}
