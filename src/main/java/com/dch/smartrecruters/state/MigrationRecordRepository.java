package com.dch.smartrecruters.state;

import java.util.Optional;

public interface MigrationRecordRepository {

    Optional<MigrationRecord> find(
            String tenantId,
            String sourceRecordId
    );

    void save(MigrationRecord record);
}