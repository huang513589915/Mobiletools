package com.mobiletools.arrears.dataingestion.ledger;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LedgerErasurePreviewRegistryTests {
    @Test
    void previewsAreBoundToOperatorAndCanOnlyBeConsumedOnce() {
        LedgerErasurePreviewRegistry registry = new LedgerErasurePreviewRegistry(Clock.systemUTC());
        var plan = new LedgerErasurePreviewRegistry.Plan("ARREARS", List.of(), Map.of(), List.of());
        var issued = registry.issue("admin-a", plan);

        assertThrows(AccessDeniedException.class, () -> registry.consume(issued.token(), "admin-b"));
        assertEquals(plan, registry.consume(issued.token(), "admin-a"));
        assertThrows(IllegalArgumentException.class, () -> registry.consume(issued.token(), "admin-a"));
    }

    @Test
    void expiredPreviewsCannotBeConsumed() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-11T00:00:00Z"));
        Clock clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        LedgerErasurePreviewRegistry registry = new LedgerErasurePreviewRegistry(clock);
        var issued = registry.issue("admin", new LedgerErasurePreviewRegistry.Plan("ARREARS", List.of(), Map.of(), List.of()));
        now.set(now.get().plusSeconds(301));

        assertThrows(IllegalArgumentException.class, () -> registry.consume(issued.token(), "admin"));
    }
}
