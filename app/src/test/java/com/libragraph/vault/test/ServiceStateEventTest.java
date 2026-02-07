package com.libragraph.vault.test;

import com.libragraph.vault.core.service.ManagedService;
import com.libragraph.vault.core.service.ServiceStateChangedEvent;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class ServiceStateEventTest {

    @Inject
    TestService testService;

    @Inject
    EventCollector eventCollector;

    @BeforeEach
    void setUp() {
        eventCollector.clear();
        // Ensure TestService starts in STOPPED state
        testService.forceState(ManagedService.State.STOPPED);
    }

    @AfterEach
    void tearDown() {
        // Restore TestService to STOPPED for other tests
        testService.forceState(ManagedService.State.STOPPED);
    }

    @Test
    void stoppingServiceFiresEvents() throws Exception {
        testService.start();
        eventCollector.clear(); // ignore start events

        testService.stop();

        List<ServiceStateChangedEvent> events = eventCollector.eventsFor("test-service");
        assertThat(events).extracting(ServiceStateChangedEvent::newState)
                .containsExactly(ManagedService.State.STOPPING, ManagedService.State.STOPPED);
    }

    @Test
    void failingServiceFiresEvent() throws Exception {
        testService.start();
        eventCollector.clear();

        testService.fail(new RuntimeException("boom"));

        List<ServiceStateChangedEvent> events = eventCollector.eventsFor("test-service");
        assertThat(events).extracting(ServiceStateChangedEvent::newState)
                .containsExactly(ManagedService.State.FAILED);
    }

    /** CDI bean that collects all {@link ServiceStateChangedEvent}s for assertions. */
    @ApplicationScoped
    public static class EventCollector {

        private final List<ServiceStateChangedEvent> events = new CopyOnWriteArrayList<>();

        void onEvent(@Observes ServiceStateChangedEvent event) {
            events.add(event);
        }

        List<ServiceStateChangedEvent> eventsFor(String serviceId) {
            return events.stream()
                    .filter(e -> e.serviceId().equals(serviceId))
                    .toList();
        }

        void clear() {
            events.clear();
        }
    }
}
