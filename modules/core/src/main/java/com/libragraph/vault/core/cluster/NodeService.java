package com.libragraph.vault.core.cluster;

import com.libragraph.vault.core.dao.NodeDao;
import com.libragraph.vault.core.db.DatabaseService;
import com.libragraph.vault.core.service.AbstractManagedService;
import com.libragraph.vault.core.service.DependsOn;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jdbi.v3.core.Jdbi;

@ApplicationScoped
@Startup
@DependsOn(DatabaseService.class)
public class NodeService extends AbstractManagedService {

    @Inject
    Jdbi jdbi;

    @ConfigProperty(name = "vault.cluster.node-id", defaultValue = "localhost")
    String configuredHostname;

    private int nodeId;
    private String hostname;

    @Override
    public String serviceId() {
        return "node";
    }

    @Override
    protected void doStart() {
        hostname = configuredHostname;
        nodeId = jdbi.withExtension(NodeDao.class, dao -> dao.upsert(hostname));
        log.infof("Node registered: id=%d, hostname=%s", nodeId, hostname);
    }

    @Override
    protected void doStop() {
        log.infof("NodeService stopping (node=%d, hostname=%s)", nodeId, hostname);
    }

    public int nodeId() {
        if (!isRunning()) {
            throw new IllegalStateException("NodeService is not running (state=" + state() + ")");
        }
        return nodeId;
    }

    public String hostname() {
        return hostname;
    }

    @PostConstruct
    void init() {
        try {
            start();
        } catch (Exception e) {
            throw new RuntimeException("NodeService failed to start", e);
        }
    }

    @PreDestroy
    void shutdown() {
        try {
            stop();
        } catch (Exception e) {
            log.warn("Error stopping NodeService", e);
        }
    }
}
