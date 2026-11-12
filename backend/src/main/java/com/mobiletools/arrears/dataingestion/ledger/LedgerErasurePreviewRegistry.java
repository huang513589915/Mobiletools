package com.mobiletools.arrears.dataingestion.ledger;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Keeps short-lived deletion plans out of the business database. */
@Component
public class LedgerErasurePreviewRegistry {
    private static final Duration LIFETIME = Duration.ofMinutes(5);
    private final Clock clock;
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    public LedgerErasurePreviewRegistry() {
        this(Clock.systemUTC());
    }

    LedgerErasurePreviewRegistry(Clock clock) {
        this.clock = clock;
    }

    public IssuedPreview issue(String operator, Plan plan) {
        Instant now = clock.instant();
        entries.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
        String token = UUID.randomUUID().toString();
        Instant expiresAt = now.plus(LIFETIME);
        entries.put(token, new Entry(operator, expiresAt, plan));
        return new IssuedPreview(token, expiresAt);
    }

    /** Atomically consumes a preview after ensuring it belongs to this operator and is unexpired. */
    public Plan consume(String token, String operator) {
        Entry entry = entries.get(token);
        if (entry == null || !entry.expiresAt().isAfter(clock.instant())) {
            entries.remove(token);
            throw new IllegalArgumentException("删除预览已过期或已使用，请重新预览");
        }
        if (!entry.operator().equals(operator)) {
            throw new AccessDeniedException("该删除预览不属于当前管理员");
        }
        if (!entries.remove(token, entry)) {
            throw new IllegalArgumentException("删除预览已过期或已使用，请重新预览");
        }
        return entry.plan();
    }

    public record IssuedPreview(String token, Instant expiresAt) {}
    public record Plan(String sourceType, List<TargetIdentity> targets,
                       Map<String, Long> currentVersionByPeriod,
                       List<FileReference> originalFiles) {
        public Plan {
            targets = List.copyOf(targets);
            currentVersionByPeriod = Map.copyOf(currentVersionByPeriod);
            originalFiles = List.copyOf(originalFiles);
        }
    }
    public record TargetIdentity(long selectedSnapshotRecordId, String statisticsKey,
                                 String identityKey, String stableIdentity,
                                 String groupCode, String groupName) {}
    public record FileReference(long sourceFileId, String storageKey, List<Long> batchIds,
                                List<String> filenames) {
        public FileReference {
            batchIds = List.copyOf(batchIds);
            filenames = List.copyOf(filenames);
        }
    }
    private record Entry(String operator, Instant expiresAt, Plan plan) {}
}
