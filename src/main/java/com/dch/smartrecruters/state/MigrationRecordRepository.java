package com.dch.smartrecruters.state;

public interface MigrationRecordRepository {

    boolean tryStart(String tenantId, String sourceRecordId);

    void markCompleted(String tenantId, String sourceRecordId);

    void markFailed(String tenantId, String sourceRecordId);
}