package com.dch.smartrecruters.state;

public record MigrationRecord(
        String tenantId,
        String sourceRecordId,
        MigrationStatus status
) {
}