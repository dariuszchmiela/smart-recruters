# SmartRecruiters Migration Lab — Full Specification

## 1. Cel projektu

Zbudować praktyczny system migracyjny odwzorowujący case rekrutacyjny:

**SAP / Legacy Recruiting System → Migration Platform → SmartRecruiters**

Projekt ma służyć jednocześnie do:
- nauki System Design,
- ćwiczenia Code Review,
- praktyki Java / Spring Boot / Kafka / PostgreSQL,
- ćwiczenia transakcji, idempotency, retry, resilience i testów,
- przygotowania do rozmowy senior backend / SmartRecruiters.

---

## 2. Główne założenia

Migrujemy wielu tenantów enterprise.

Dane przykładowe:
- Candidates
- Jobs
- Applications
- Tenant configuration

Migracja ma być:
- restartowalna,
- idempotentna,
- audytowalna,
- odporna na częściowe awarie,
- wykonywana partiami,
- możliwa do uruchamiania równolegle dla wielu tenantów,
- bezpieczna dla PII,
- rozszerzalna o delta synchronization przez Kafka.

---

## 3. Aplikacje

Repozytorium może być multi-module:

```text
smartrecruiters-migration-lab/
├── sap-source/
├── migration-service/
├── smartrecruiters-stub/
├── docker-compose.yml
└── README.md
```

### 3.1 `sap-source`

Symuluje stary system SAP.

Odpowiedzialności:
- przechowuje dane źródłowe,
- wystawia REST API do ekstrakcji,
- później może emitować zmiany delta.

Przykładowe API:

```http
GET /api/tenants/{tenantId}/candidates?page=0&size=100
GET /api/tenants/{tenantId}/candidates/{candidateId}
GET /api/tenants/{tenantId}/candidates?updatedSince=...
```

Przykładowy model:

```text
SapCandidate
- id
- tenantId
- firstName
- lastName
- email
- status
- updatedAt
```

---

### 3.2 `migration-service`

Główna aplikacja.

Stack:
- Java 25
- Spring Boot
- Spring Data JPA
- PostgreSQL
- Kafka
- Resilience4j
- Docker
- Testcontainers
- OpenAPI
- Micrometer

Odpowiedzialności:
- uruchamianie migracji tenantów,
- extraction,
- mapping,
- validation,
- load do SmartRecruiters,
- migration state,
- checkpoints,
- retry,
- idempotency,
- audit,
- reconciliation,
- później delta synchronization.

Główny flow:

```text
SAP
 ↓
Extraction
 ↓
Mapping
 ↓
Validation
 ↓
Load
 ↓
SmartRecruiters
```

---

### 3.3 `smartrecruiters-stub`

Lokalny fake target systemu SmartRecruiters.

Cel:
- bezpieczne testowanie,
- symulowanie błędów,
- test resilience.

Przykładowe API:

```http
POST /api/candidates
GET  /api/candidates/{externalId}
```

Stub powinien umieć symulować:

```text
201 Created
400 Bad Request
409 Conflict
429 Too Many Requests
500 Internal Server Error
503 Service Unavailable
timeout
duplicate request
```

---

## 4. Model domenowy migracji

### `MigrationJob`

Jedna migracja jednego tenanta.

```text
id
tenantId
status
currentStage
startedAt
completedAt
```

Statusy:

```text
NEW
RUNNING
PAUSED
FAILED
RECONCILING
COMPLETED
```

### `MigrationBatch`

Jedna partia danych.

```text
id
migrationJobId
entityType
batchNumber
status
retryCount
lastProcessedSourceId
startedAt
completedAt
```

### `MigrationRecord`

Mapowanie source → target.

```text
id
migrationJobId
batchId
entityType
sourceId
targetId
status
sourceVersion
```

### `AuditEvent`

Append-only historia.

```text
id
migrationJobId
batchId
type
message
createdAt
```

---

## 5. Relacje tabel

```text
migration_job
   │
   ├──< migration_batch
   │        │
   │        └──< migration_record
   │
   └──< audit_event
```

---

## 6. Batch migration

Batch służy do migracji pełnego historycznego stanu.

Przykład:

```text
tenant 471
batch 1 → candidates 1-500
batch 2 → candidates 501-1000
batch 3 → candidates 1001-1500
```

Nie używamy jednej wielkiej transakcji.

Schemat:

```text
TX1
claim chunk / update state
COMMIT

HTTP calls do SmartRecruiters
bez otwartej transakcji DB

TX2
update COMPLETED / FAILED
COMMIT
```

Wymagania:
- małe bounded chunks,
- checkpoint,
- retry tylko dla failed chunk,
- idempotent load,
- możliwość resume po restarcie.

---

## 7. Idempotency

Każdy rekord musi mieć stabilną tożsamość.

Preferowany klucz:

```text
tenantId + sourceRecordId
```

Przykład:

```text
471:123
```

Jeśli target wspiera external ID / idempotency key:
- używamy go.

Jeśli nie:
- zapisujemy mapowanie `sourceId → targetId` lokalnie.

Cel:

```text
ten sam request 2 razy
→ jeden efekt biznesowy
```

---

## 8. Concurrency

Nie używamy JVM-only locking jako głównego zabezpieczenia, bo aplikacja może działać na wielu podach.

Preferowany claim:

```sql
UPDATE candidate_migration
SET status = 'IN_PROGRESS'
WHERE id = ?
  AND status = 'NEW';
```

Interpretacja:

```text
updated rows = 1 → worker zdobył pracę
updated rows = 0 → ktoś inny już ją przejął
```

Dla prostych state transitions preferujemy atomic update.

Alternatywy:
- optimistic locking `@Version`,
- pessimistic locking,
- distributed lock tylko jeśli naprawdę potrzebny.

---

## 9. Delta synchronization

Po rozpoczęciu batch migration SAP może nadal się zmieniać.

Definiujemy:

```text
T0 = migration cutover point
```

```text
stan do T0 → Batch
zmiany po T0 → Delta
```

Preferencje źródła zmian:

```text
1. Domain events
2. CDC
3. Polling po updatedAt + stable ID
```

---

## 10. Kafka

Kafka służy głównie do delta path.

```text
SAP changes
 ↓
Events / CDC
 ↓
Kafka
 ↓
Delta Processor
 ↓
SmartRecruiters
```

Przykładowe topics:

```text
candidate-changes
job-changes
application-changes
tenant-config-changes
```

Partition key:

```text
tenantId + entityId
```

Cel:
- ordering dla jednej encji,
- równoległość między encjami.

Consumer group:

```text
candidate-migration
```

Wymagania:
- at-least-once delivery,
- idempotent consumer,
- eventId,
- entity version / sequence number,
- retry topic,
- DLQ.

---

## 11. Processed events

Tabela:

```text
processed_event
- eventId
- processedAt
```

Consumer:

```text
event przychodzi
 ↓
eventId istnieje?
 ├─ tak → ignore
 └─ nie
      ↓
   update entity
      ↓
   save processed_event
      ↓
   COMMIT
```

`entity version` chroni przed out-of-order events.

---

## 12. Retry / timeout / circuit breaker

### Timeout

Dla HTTP:
- connect timeout,
- response timeout.

### Retry

Tylko transient errors:

```text
429
502
503
504
timeout
```

Nie retryujemy automatycznie:

```text
400
401
403
validation errors
```

Retry:
- bounded,
- exponential backoff,
- jitter.

### Circuit breaker

Stany:

```text
CLOSED
OPEN
HALF_OPEN
```

Cel:
- nie dobijać niedostępnego targetu.

---

## 13. Backpressure

Migration Service nie może generować większego ruchu niż target przyjmie.

Mechanizmy:
- concurrency limit,
- bounded executor,
- rate limiter,
- batch size,
- consumer lag monitoring.

Virtual threads mogą obsługiwać blocking I/O, ale nie usuwają potrzeby limitowania concurrency.

---

## 14. Mapping i validation

Przykład:

```text
SAP.status = ACTIVE_INTERNAL
        ↓
InternalStatus.ACTIVE
        ↓
SmartRecruiters.state = IN_PROCESS
```

Rozdzielamy:
- source adapter,
- internal migration model,
- target adapter.

Warstwa ACL:

```text
SAP model
 ↓
SAP Adapter
 ↓
Internal Model
 ↓
SmartRecruiters Adapter
 ↓
SmartRecruiters model
```

Validation:
- required fields,
- allowed enum values,
- format email,
- referential integrity,
- tenant consistency.

---

## 15. Zależności encji

Przykład:

```text
Candidate ─┐
           ├──> Application
Job ───────┘
```

Candidate i Job mogą migrować równolegle.

Application dopiero po:
- Candidate migrated,
- Job migrated,
- source → target IDs zapisane.

---

## 16. Reconciliation

Load success ≠ migration success.

Reconciliation porównuje source i target.

Przykład:

```text
SAP Candidates              120000
SmartRecruiters Candidates  120000  ✅

SAP Applications            870000
SmartRecruiters Applications 869997 ❌
```

Sprawdzamy:
- counts,
- external IDs,
- checksums,
- versions,
- missing records,
- duplicates.

MigrationJob może przejść do `COMPLETED` dopiero po reconciliation.

---

## 17. REST API migration-service

Przykładowe API:

```http
POST /api/migrations/{tenantId}
POST /api/migrations/{tenantId}/pause
POST /api/migrations/{tenantId}/resume

GET /api/migrations/{tenantId}
GET /api/migrations/{tenantId}/batches
GET /api/migrations/{tenantId}/errors
```

Przykładowa odpowiedź:

```json
{
  "tenantId": 471,
  "status": "RUNNING",
  "currentStage": "LOAD",
  "processed": 15000,
  "failed": 7
}
```

---

## 18. Security

Dane:
- Candidates,
- CV,
- email,
- phone,
- employment history.

Wymagania:
- HTTPS/TLS,
- encryption at rest,
- secrets poza kodem,
- least privilege,
- tenant isolation,
- audit,
- brak PII w zwykłych logach,
- retention policy,
- GDPR.

---

## 19. Observability

Metrics:

```text
migrations_running
migrations_failed
records_processed_total
records_failed_total
batch_duration
retry_count
dlq_size
consumer_lag
reconciliation_mismatches
target_api_latency
```

Każdy log powinien zawierać:

```text
tenantId
migrationJobId
batchId
entityType
sourceRecordId
```

Tracing:

```text
Extraction
→ Mapping
→ Validation
→ Load
→ SmartRecruiters
```

---

## 20. Deployment

AWS:

```text
EKS
├── migration-api
├── batch-worker
├── delta-processor
└── reconciliation-worker

RDS PostgreSQL

Kafka / MSK
```

Deployment:
- rolling,
- canary,
- backward-compatible DB schema,
- backward-compatible event schema.

---

## 21. Multi-tenancy

Tenant context musi być propagowany end-to-end.

Każdy rekord migracyjny:

```text
tenantId
```

Izolujemy:
- credentials,
- state,
- audit,
- rate limits,
- target mapping.

Nigdy nie zakładamy, że `tenant_id` w jednej tabeli sam rozwiązuje cały problem multi-tenancy.

---

## 22. AI-assisted migration

AI może:
- proponować mapping pól,
- wykrywać podobne schematy,
- sugerować transformacje,
- wykrywać anomalie,
- wspierać operatora.

AI nie powinno samodzielnie:
- wykonywać nieodwracalnych zmian,
- usuwać PII,
- zatwierdzać low-confidence mappingów,
- robić cutoveru,
- nadpisywać deterministycznych reguł bez audytu.

Preferowany flow:

```text
schema comparison
 ↓
AI suggestion
 ↓
human review
 ↓
approved deterministic rule
 ↓
migration
```

---

## 23. Testy

### Unit
- mapper,
- validator,
- domain rules,
- retry classification.

### Integration
Testcontainers:
- PostgreSQL,
- Kafka.

Testujemy:
- JPA mappings,
- transactions,
- locking,
- Kafka serialization,
- consumer,
- processed_event,
- retry/DLQ.

### Component / end-to-end

```text
SAP Stub
 ↓
Migration Service
 ↓
SmartRecruiters Stub
```

Scenariusze:
- happy path,
- timeout,
- duplicate,
- retry,
- restart,
- partial failure,
- reconciliation mismatch.

---

## 24. Milestones implementacji

### Milestone 1 — najprostszy happy path
- `sap-source`
- `smartrecruiters-stub`
- `migration-service`
- GET candidates
- POST candidate
- mapping
- validation
- load

### Milestone 2 — migration state
- MigrationJob
- MigrationBatch
- MigrationRecord
- PostgreSQL
- checkpoint
- resume

### Milestone 3 — resilience
- timeout
- retry
- backoff
- circuit breaker
- idempotency

### Milestone 4 — concurrency
- atomic claim
- parallel workers
- concurrency limit
- virtual threads

### Milestone 5 — Kafka delta
- candidate-changes
- eventId
- version
- idempotent consumer
- retry topic
- DLQ

### Milestone 6 — reconciliation
- count comparison
- missing IDs
- mismatch report

### Milestone 7 — observability
- metrics
- structured logs
- tracing
- dashboards

### Milestone 8 — deployment
- Docker
- docker-compose
- Kubernetes manifests / Helm
- AWS mapping

---

## 25. Pierwszy krok implementacyjny

Nie zaczynamy od Kafka ani Kubernetes.

Pierwszy działający flow:

```text
sap-source
GET /api/tenants/{tenantId}/candidates

        ↓

migration-service
GET source
→ map
→ validate
→ POST target

        ↓

smartrecruiters-stub
POST /api/candidates
```

Gdy to działa end-to-end, dokładamy stan migracji i kolejne mechanizmy.

---

## 26. Zasady pracy nad projektem

- jeden krok naraz,
- użytkownik pisze kod sam,
- nie wklejamy całych dużych plików bez potrzeby,
- najpierw prosty working flow,
- potem resilience i concurrency,
- po każdym etapie code review,
- Java najpierw,
- potem wybrane elementy przepisujemy / porównujemy z Kotlinem,
- każdy mechanizm tłumaczymy: co, dlaczego, trade-off, failure scenario.

---

## 27. Cel końcowy

Po ukończeniu projektu użytkownik powinien umieć:

1. Narysować architekturę migracji.
2. Wyjaśnić batch + delta.
3. Wyjaśnić Kafka topics / partitions / consumer groups.
4. Wyjaśnić idempotency i at-least-once.
5. Zaprojektować retry / DLQ / circuit breaker.
6. Wyjaśnić transakcje DB vs external API.
7. Zaprojektować restartable migration.
8. Rozpoznać N+1, race condition i transaction pitfalls.
9. Wyjaśnić multi-tenancy i PII security.
10. Zrobić senior-level Code Review implementacji.
