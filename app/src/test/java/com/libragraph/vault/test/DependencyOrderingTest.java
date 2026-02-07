package com.libragraph.vault.test;

import com.libragraph.vault.core.db.DatabaseService;
import com.libragraph.vault.core.service.ManagedService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DependencyOrderingTest {

    @Inject
    DatabaseService databaseService;

    @Inject
    TestService testService;

    @Test
    @Order(1)
    void testServiceStartsAfterDatabase() throws Exception {
        // DatabaseService starts via @Startup @PostConstruct
        assertThat(databaseService.state()).isEqualTo(ManagedService.State.RUNNING);

        // TestService can start because its dependency (DatabaseService) is RUNNING
        testService.start();
        assertThat(testService.state()).isEqualTo(ManagedService.State.RUNNING);

        // Clean up: stop TestService for next test
        testService.forceState(ManagedService.State.STOPPED);
    }

    @Test
    @Order(2)
    void testServiceCascadesOnDatabaseFailure() throws Exception {
        // Ensure TestService is running
        testService.start();
        assertThat(testService.state()).isEqualTo(ManagedService.State.RUNNING);

        // Simulate database failure
        databaseService.fail(new RuntimeException("simulated DB failure"));

        // TestService should have cascaded to FAILED via CDI event observer
        assertThat(testService.state()).isEqualTo(ManagedService.State.FAILED);

        // Restore both services for other tests sharing this container
        databaseService.forceState(ManagedService.State.RUNNING);
        testService.forceState(ManagedService.State.STOPPED);
    }
}
