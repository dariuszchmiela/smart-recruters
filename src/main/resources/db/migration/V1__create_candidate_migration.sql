CREATE TABLE candidate_migration (
                                     tenant_id        VARCHAR(100) NOT NULL,
                                     source_record_id VARCHAR(100) NOT NULL,
                                     status           VARCHAR(30) NOT NULL,

                                     PRIMARY KEY (tenant_id, source_record_id)
);