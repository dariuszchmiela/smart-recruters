package com.dch.smartrecruters.state;

import java.util.Optional;

public interface MigrationRecordRepository {

    boolean tryStart(String tenantId, String sourceRecordId);

    void markCompleted(String tenantId, String sourceRecordId);

    void markFailed(String tenantId, String sourceRecordId);

    Optional<MigrationStatus> findStatus(String tenantId, String sourceRecordId);
}
