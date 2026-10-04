# SmartRecruiters Migration Lab

A migration platform that moves candidate data of many tenants from a SAP / legacy source system to
SmartRecruiters, built to stay correct under retries, crashes, concurrent workers and a source that keeps
changing while the migration runs.

```
SAP / legacy source  ──►  migration service (this repository)  ──►  SmartRecruiters
```

Both external systems are simulated by local stubs in `stubs/`. The executable scope is **candidates**
(externalId, firstName, lastName, email); other entity types are not implemented.

This README describes the system **as built**. Items that are not implemented are listed explicitly in
[Known limitations](#known-limitations--deliberate-trade-offs) and [Not implemented](#not-implemented-possible-extensions).
`smartrecruiters-migration-lab-spec.md` contains the more detailed design notes, with the same distinction.

## The mental model

| Concern | What it means here | Main classes |
|---|---|---|
| **Move** | initial load: single candidate or a whole tenant in pages | `CandidateMigrationService`, `CandidateBatchMigrationService` |
| **Catch up** | apply source changes made during/after the load, via Kafka | `CandidateChangedListener`, `CandidateDeltaService` |
| **Remember** | all progress lives in PostgreSQL: per-candidate state, job checkpoints, event inbox, leases | `state/jdbc/*Repository` |
| **Don't duplicate** | atomic claims + fencing tokens in PostgreSQL, idempotent target writes keyed by `tenantId + externalId` | `JdbcMigrationRecordRepository`, `JdbcCandidateDeltaEventRepository` |
| **Verify** | independent source-vs-target comparison | `CandidateReconciliationService` |

## Architecture

```
  SAP stub (:8081)                    migration service (:8080)                      SmartRecruiters stub (:8082)
  ─────────────────                   ─────────────────────────                      ────────────────────────────

  GET  /candidates/{id}  ◄──────────  CandidateMigrationService ──── POST (create-if-absent) ──►  /candidates
  GET  /candidates?page  ◄──────────  CandidateBatchMigrationService ┘   (per candidate)

  PUT  /candidates/{id}                                             ┌─ PUT (upsert) ──────────►  /candidates/{externalId}
   │  (simulated change)                                            │
   └──► Kafka "candidate-changes" ──► CandidateChangedListener ──► CandidateDeltaService
         key tenantId:candidateId          │ retries exhausted /      │ GET /candidates/{id} (current state)
                                           │ permanent failure        ▼
                                           └──► "candidate-changes.DLT"  SAP stub

  GET  /candidates?page  ◄──────────  CandidateReconciliationService ─── GET ?page (read-only) ──►  /candidates?page

                    every HTTP call: CircuitBreaker ─► Retry ─► HTTP   (ExternalCallExecutor, one breaker per system)

                                  PostgreSQL (Flyway V1–V6, plain JDBC)
     candidate_migration │ tenant_migration_job │ candidate_delta_event │ reconciliation_run │ candidate_reconciliation_item
```

All three paths run in one Spring Boot application. Background work (tenant jobs, reconciliation runs) runs
on small bounded executors inside it.

## Implemented capabilities

- **Single-candidate migration**: synchronous, idempotent per `tenantId + candidateId`.
- **Tenant batch migration**: paged SAP extraction, a page checkpoint persisted after every completed page,
  restart/resume from the checkpoint, and per-job counters.
- **Bounded concurrency**:
  - within a page, one virtual thread per candidate, gated by a semaphore (`migration.parallelism`);
  - jobs and reconciliation runs execute on fixed-size platform-thread pools with bounded queues.
- **Atomic claims in PostgreSQL**: a single `INSERT … ON CONFLICT … DO UPDATE … WHERE` or `UPDATE … RETURNING` decides the owner, using the database clock.
- **Leases + fencing tokens** (`lease_owner`) on candidate records, delta events, tenant jobs and reconciliation
  runs. A stale worker whose lease was taken over cannot finish or fail work it no longer owns.
- **Idempotency**:
  - candidate migration state is keyed by `tenantId + sourceRecordId`;
  - delta events are deduplicated by `eventId` in an inbox table;
  - target writes are keyed by `tenantId + externalId` (create-if-absent, upsert).
- **HTTP resilience**: connect/read timeouts, bounded retry for transient failures, and a circuit breaker per external system.
- **PII-safe external errors**: upstream response bodies and raw transport messages are never placed in
  exception messages, cause chains, logs or the persisted `last_error`.
- **Kafka delta synchronization**: an event inbox, bounded blocking retries, and a dead letter topic.
- **Reconciliation**: paged SAP and SmartRecruiters scans meet in PostgreSQL; set-based classification; paged difference API.
- **Local stubs** for SAP and SmartRecruiters with failure injection.
- **Tests** with Testcontainers PostgreSQL and Kafka; the application context test is self-contained.

## Technology stack

From the POMs:

- Java 25 (virtual threads)
- Spring Boot 4.1.1: Spring MVC (`spring-boot-starter-webmvc`), `RestClient` (`spring-boot-starter-restclient`)
- **Spring JDBC with explicit SQL** (`spring-boot-starter-jdbc`). There is **no JPA**. PostgreSQL is the store, with
  schema migrations by Flyway.
- Spring Kafka (`spring-boot-starter-kafka`, Jackson 3 JSON serde)
- Resilience4j circuit breaker 2.4.0; retry is Spring Framework's `RetryTemplate`
- Tests: JUnit 5, Mockito, Spring `MockRestServiceServer` / MockMvc, Testcontainers (PostgreSQL, Kafka)
- Local infrastructure: `docker-compose.yml` with PostgreSQL 17 and a single-node KRaft Kafka 4.1

Explicit SQL is a deliberate choice: claims, lease takeover and fenced completion are single conditional
statements whose semantics are visible and tested against real PostgreSQL.

## Core flows

### Initial candidate migration — `CandidateMigrationService`

```
claim (tenantId, candidateId) with a new leaseOwner      INSERT … ON CONFLICT … DO UPDATE WHERE FAILED or stale IN_PROGRESS
  -> SAP GET candidate          (skipped in the batch path: the page already contains it)
  -> map (CandidateMapper) -> validate (CandidateValidator: email required)
  -> SmartRecruiters POST       create-if-absent on tenantId + externalId
  -> markCompleted(leaseOwner)  UPDATE … WHERE status = 'IN_PROGRESS' AND lease_owner = ?
on failure: markFailed(leaseOwner) and rethrow
```

- **Outcomes**: `MIGRATED`; `ALREADY_MIGRATED` (record already `COMPLETED`); `CLAIMED_BY_OTHER_WORKER` (fresh claim held elsewhere);
  `LEASE_LOST` (this attempt's claim was taken over before it could finish; it is never reported as `MIGRATED`).
- **No transaction spans PostgreSQL and HTTP.** A crash between the target write and `markCompleted` leaves the record
  `IN_PROGRESS`. After `migration.claim-timeout` it is reclaimed and re-sent, which is safe because the target create
  is idempotent on `tenantId + externalId`.

### Tenant batch migration — `CandidateBatchMigrationService`, `TenantMigrationJobLauncher`

```
POST /api/migrations/{tenantId}/candidates/batch -> 202 + jobId (new PENDING job, or the tenant's unfinished one)
worker: claim job (lease_owner) -> loop:
   SAP page (page, page_size) -> candidates migrated with bounded parallelism -> one UPDATE:
   next_page + counters + heartbeat, fenced by lease_owner
-> COMPLETED, or COMPLETED_WITH_ERRORS when failed_count > 0
```

- **One unfinished job per tenant** (`PENDING`, `RUNNING`, `FAILED`), enforced by a partial unique index.
- **Resumable**:
  - `FAILED` (e.g. a SAP page could not be read) is resumed from its checkpoint by starting the tenant again;
  - an abandoned `RUNNING` job (no heartbeat within `migration.job-lease-timeout`) and `PENDING` jobs are picked up by the
    launcher's recovery scan.
- **Checkpoint**: written only after a whole page. A crash mid-page re-processes that page; already migrated
  candidates count as succeeded and are not re-sent.
- **Counters** (`processed = succeeded + skipped + failed`):
  - `succeeded`: the candidate is in the target (now or earlier);
  - `skipped`: held by another worker, or this job's claim was taken over;
  - `failed`: this attempt failed.
- **Failure isolation**: one failed candidate never stops the page or the job.
- **Bounds**:
  - `migration.parallelism` candidates in flight per job;
  - `migration.max-concurrent-jobs` jobs per instance;
  - `migration.job-queue-capacity` waiting jobs.
  A job that does not fit stays `PENDING` and is started by the recovery scan.

### Delta synchronization — `CandidateChangedListener`, `CandidateDeltaService`, `DeltaKafkaConfiguration`

```
CandidateChangedEvent {eventId, tenantId, candidateId, occurredAt}   topic candidate-changes, key tenantId:candidateId
 -> listener (thin) -> CandidateDeltaService
    -> claim eventId in candidate_delta_event (lease_owner)
         already COMPLETED          -> ignored (duplicate)
         fresh IN_PROGRESS elsewhere -> retryable exception (not processed twice)
    -> SAP GET current candidate -> map -> validate -> SmartRecruiters PUT (upsert on tenantId + externalId)
    -> markCompleted(leaseOwner)
```

- **The event carries identity only**: the current source state is always re-fetched, so duplicates converge to the latest source state.
- **Delivery is at-least-once.** Correctness relies on the `eventId` inbox, fenced completion and the idempotent upsert.
  Different `eventId`s for the same candidate are all processed.
- A **`FAILED` event is reclaimed** on redelivery.
- **Failure handling** (Spring Kafka `DefaultErrorHandler`):
  - permanent failures (`CandidateValidationException`, permanent HTTP error, malformed/inconsistent event) →
    `PermanentDeltaEventException` → not retried → `candidate-changes.DLT`;
  - everything else is retried with **blocking** exponential back-off on the consumer thread, up to
    `migration.kafka.max-attempts` deliveries (default 3), then dead-lettered.
  - There are no retry topics. Unreadable payloads (deserialization errors) go to the DLT directly.
- **Ordering**: per partition key `tenantId:candidateId`; retries are blocking, so a later change of the same candidate waits.

### Reconciliation — `CandidateReconciliationService`, `ReconciliationRunLauncher`

```
POST /api/reconciliation/{tenantId}/candidates -> 202 + runId
worker: claim run -> SAP pages:            map (CandidateMapper) -> fingerprint -> batch upsert source columns
                  -> SmartRecruiters pages: fingerprint                       -> batch upsert target columns
                  -> one transaction: classify every row in SQL + compute counters -> COMPLETED
any failure -> FAILED (working rows kept for diagnosis)
```

- **Results**:
  - `MATCHED`: on both sides with equal fingerprints;
  - `MISMATCHED`: on both sides with different fingerprints;
  - `MISSING_IN_TARGET`: source only;
  - `UNEXPECTED_IN_TARGET`: target only.
- **Fingerprint** (`CandidateFingerprint`): SHA-256 over a versioned, length-prefixed canonical form of
  externalId, firstName, lastName and email.
  - Canonicalization: Unicode NFC, `String.strip()`, null == blank; **case-sensitive**.
  - Equal fingerprints are treated as equivalence under these rules (SHA-256 collisions considered negligible), not as
    character-for-character equality.
- **Independent verification**:
  - `candidate_migration` is never consulted;
  - both systems are only read;
  - `CandidateValidator` is intentionally not applied, so a source candidate the migration would reject shows as
    `MISSING_IN_TARGET`, or as `MATCHED` if an equivalent candidate already exists in the target.
- **One unfinished run per tenant.** Runs are not resumable: an abandoned run is marked `FAILED`, and a new run starts from scratch.
- **Eventually consistent, not a snapshot**: Kafka consumers keep running, so changes during a scan can produce
  transient differences. Run it after the initial load and again once the delta backlog is drained.
- Only identifiers and fingerprints are stored; no candidate PII is copied into reconciliation tables.

## Persistence model (Flyway V1–V6)

| Table | Migration | Purpose |
|---|---|---|
| `candidate_migration` | V1, V2, V5 | per-candidate migration state: `(tenant_id, source_record_id)` PK, `status` IN_PROGRESS / COMPLETED / FAILED, `updated_at` (lease heartbeat), `lease_owner` (fencing token) |
| `tenant_migration_job` | V3 | tenant batch job: status PENDING / RUNNING / COMPLETED / COMPLETED_WITH_ERRORS / FAILED, `page_size`, `next_page` checkpoint, processed/succeeded/skipped/failed counters, `lease_owner`, `last_error`; partial unique index = one unfinished job per tenant |
| `candidate_delta_event` | V4 | Kafka event inbox: `event_id` PK, tenant/candidate, status IN_PROGRESS / COMPLETED / FAILED, `attempts`, `lease_owner`, `last_error` |
| `reconciliation_run` | V6 | reconciliation run: status PENDING / RUNNING / COMPLETED / FAILED, summary counters, `lease_owner`, `last_error`; partial unique index = one unfinished run per tenant |
| `candidate_reconciliation_item` | V6 | working rows, PK `(run_id, external_id)`: `source_seen`, `target_seen`, both fingerprints, `result` |

Every table is keyed or filtered by tenant (directly or through its run). The same id in two tenants is never mixed.

## Resilience

Every SAP and SmartRecruiters call goes through `ExternalCallExecutor`:

```
CircuitBreaker (one per external system)  ->  Retry (transient failures only)  ->  HTTP (connect/read timeout)
```

- **One business call = one circuit breaker outcome**, however many retries it needed.
- **Transient** (retried and counted by the breaker), exactly as in `HttpFailureClassifier`:
  - HTTP 408, 429, 502, 503 and 504;
  - I/O failures without a response (timeouts, connection refused/reset).
- **Permanent** (no retry, not counted as a breaker failure): every other status. **HTTP 500 is treated as permanent**;
  this is the current project policy.
- **Retry**: `clients.retry.*`, defaults 3 attempts, 200 ms initial back-off, ×2, max 2 s (exponential, no jitter).
- **Timeouts**: `clients.{sap,smartrecruiters}.connect-timeout` 1 s / `read-timeout` 3 s.
- **Circuit breaker**: `clients.circuit-breaker.*`, defaults: count-based window 10, minimum 5 calls, 50 % failure rate,
  30 s open, 2 half-open calls. An OPEN breaker fails fast (transient) without an HTTP request.
- **Safe errors**: failures become `ExternalSystemException` with typed `operation`, `failureType`, `kind` and `httpStatus`.
  - The message is built only from these, e.g. `SmartRecruiters PUT candidate t1:c1 failed (PERMANENT, HTTP 400)`.
  - The raw HTTP exception (whose message can contain the response body) is not kept in the cause chain.

## API

Migration service (port 8080):

| Method | Path | Behaviour |
|---|---|---|
| POST | `/api/migrations/{tenantId}/candidates/{candidateId}` | migrate one candidate synchronously; 204 |
| POST | `/api/migrations/{tenantId}/candidates/batch` | start (or return the unfinished) tenant job; 202 + `Location` |
| GET | `/api/migrations/jobs/{jobId}` | job status, checkpoint, counters |
| POST | `/api/reconciliation/{tenantId}/candidates` | start (or return the unfinished) reconciliation run; 202 + `Location` |
| GET | `/api/reconciliation/runs/{runId}` | run status and summary counters |
| GET | `/api/reconciliation/runs/{runId}/items?result=&page=0&size=100` | paged classified items of a COMPLETED run (size ≤ 1000; without `result`: all differences; 409 while not COMPLETED) |

Delta synchronization has no REST endpoint; it is driven by the Kafka topic.

Stubs:
- **SAP stub (8081)**:
  - `GET /api/tenants/{t}/candidates/{id}`
  - `GET /api/tenants/{t}/candidates?page=&size=` (ordered by id)
  - `PUT /api/tenants/{t}/candidates/{id}`: stores a change and publishes a `CandidateChangedEvent`
  - `/admin/failures` (failure injection)
  - Seed data: `tenant-1`, `tenant-2` and a generated `tenant-bulk` with 1050 candidates, every 100th without email.
- **SmartRecruiters stub (8082)**:
  - `POST /api/tenants/{t}/candidates` (create-if-absent)
  - `PUT /api/tenants/{t}/candidates/{externalId}` (upsert)
  - `GET …/{externalId}`
  - `GET …/candidates` (all) and `GET …/candidates?page=&size=` (ordered by externalId)
  - `/admin/failures`

## Running locally

```bash
docker compose up -d                                        # PostgreSQL 17 (5432) + Kafka 4.1 KRaft (9092)

mvn -f stubs/sap-stub/pom.xml spring-boot:run               # SAP stub, port 8081
mvn -f stubs/smartrecruiters-stub/pom.xml spring-boot:run   # SmartRecruiters stub, port 8082
mvn spring-boot:run                                         # migration service, port 8080 (Flyway migrates on start)
```

Example:

```bash
curl -X POST localhost:8080/api/migrations/tenant-bulk/candidates/batch        # -> jobId
curl localhost:8080/api/migrations/jobs/{jobId}
curl -X PUT localhost:8081/api/tenants/tenant-1/candidates/candidate-1 \
     -H 'Content-Type: application/json' \
     -d '{"firstName":"John","lastName":"Smith","email":"new@example.com"}'    # -> delta event
curl -X POST localhost:8080/api/reconciliation/tenant-bulk/candidates          # -> runId
curl "localhost:8080/api/reconciliation/runs/{runId}/items?result=MISSING_IN_TARGET"
```

Connection settings are overridable through environment variables (see `application.yaml`, e.g. `DB_HOST`,
`KAFKA_BOOTSTRAP_SERVERS`, `SAP_BASE_URL`, `SMARTRECRUITERS_BASE_URL`).

## Testing

```bash
mvn test                                     # migration service
mvn -f stubs/sap-stub/pom.xml test
mvn -f stubs/smartrecruiters-stub/pom.xml test
```

- **Unit and slice tests**: mapping, validation, failure classification, retry, circuit breaker (with a controllable clock),
  real timeouts, controllers (MockMvc) and HTTP clients (`MockRestServiceServer`).
- **PostgreSQL integration tests (Testcontainers)**: claims, lease takeover and fencing under concurrent workers,
  checkpoints, the inbox, reconciliation SQL. These are skipped when no Docker-compatible runtime is available.
- **Kafka integration test (Testcontainers broker)**: plain-JSON delivery, bounded retries, permanent failure → DLT,
  unreadable payload → DLT.
- **Service tests with in-memory fakes**: batch paging, crash/replay, lease loss, concurrency bound, delta idempotency.
- **Application context test**: starts the full context against a Testcontainers PostgreSQL with all Flyway migrations.
  Kafka listeners are not started in this test, so no local PostgreSQL or Kafka is needed. It requires Docker.

## Known limitations / deliberate trade-offs

- **Offset pagination**: source and target listings are page-number based. On live data, pages can shift during a scan
  or job, so a record can be read twice (harmless, idempotent) or skipped in that pass. Keyset pagination would
  need source/target support.
- **Reconciliation is not a snapshot**: results can contain transient differences while data changes; repeat until stable.
- **Reconciliation runs are not resumable**: a failed or abandoned run is restarted from scratch by a new run.
- **No retention for reconciliation working rows**: rows accumulate per run (deleting a run cascades).
- **Target side effects cannot be fenced**: fencing protects PostgreSQL state. An HTTP write already sent by a stale worker can
  still reach the target. This is harmless for create-if-absent; for delta upserts the next change event corrects it.
- **No source version ordering**: delta processing re-fetches the latest source state; there is no entity version or sequence check.
  Two workers processing the same candidate concurrently (e.g. during a rebalance) could write an older read last.
- **The stub's event emission is not an outbox**: the SAP stub stores the change and then publishes. If publishing fails, the change
  stays stored without an event (it returns 503).
- **Blocking Kafka retries**: back-off happens on the consumer thread and holds the partition (bounded by `max-attempts`).
- **No DLT replay tooling**: replaying a DLT record works (its inbox row is `FAILED` and reclaimable), but there is no tool for it.
- **No delete propagation**: a candidate deleted in SAP ends up in the DLT on the delta path; the target is not changed.
- **No global outbound rate limiter**: concurrency is bounded per job/run and per instance. Total target load is roughly
  `max-concurrent-jobs × parallelism` per instance, plus delta consumers.
- **Limited observability**: plain SLF4J log statements only (run/job/event ids, tenant, safe error messages); no metrics,
  tracing or health endpoints.
- **Error responses of the synchronous endpoint**: failures of `POST …/candidates/{candidateId}` use Spring's default error handling.

## Not implemented (possible extensions)

- Entity types beyond candidates (jobs, applications, tenant configuration) and cross-entity dependencies
- Source entity versions / sequence numbers, retry topics, transactional outbox / CDC on the source side
- DLT replay tooling, delete propagation, keyset pagination, resumable reconciliation, working-row retention
- A per-tenant / global outbound rate limiter, pause/resume of jobs
- Metrics (Micrometer), tracing, dashboards, audit trail
- Container images for the services, Kubernetes / cloud deployment manifests
- AI-assisted mapping suggestions (design idea only)
