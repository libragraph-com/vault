package com.libragraph.vault.test;

import com.libragraph.vault.core.db.DatabaseService;
import com.libragraph.vault.core.service.ManagedService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class DatabaseServiceTest {

    @Inject
    DatabaseService databaseService;

    @Test
    void startsInRunningState() {
        assertThat(databaseService.state()).isEqualTo(ManagedService.State.RUNNING);
    }

    @Test
    void pingReturnsTrue() {
        assertThat(databaseService.ping()).isTrue();
    }

    @Test
    void pgVersionContainsPostgreSQL() {
        assertThat(databaseService.pgVersion()).containsIgnoringCase("PostgreSQL");
    }

    @Test
    void jdbiIsAccessible() {
        var result = databaseService.jdbi()
                .withHandle(h -> h.createQuery("SELECT 1").mapTo(Integer.class).one());
        assertThat(result).isEqualTo(1);
    }
}
