# SmartRecruiters Migration Lab — Specification

> **Status dokumentu.** Ten dokument opisuje **aktualnie zaimplementowany** migration lab.
> Elementy projektowe, które nie istnieją w kodzie, są oznaczone wprost jako
> **NIE ZAIMPLEMENTOWANE** (głównie w sekcjach 19–21).
> Źródłem prawdy dla opisu bieżącego zachowania są kod, konfiguracja, migracje Flyway i testy.
> Skrócony opis „as-built” znajduje się w `README.md`.

---

## 1. Cel projektu

Projekt bada projektowanie i implementację odpornej platformy migracji danych enterprise:

**SAP / legacy source system → Migration Platform → SmartRecruiters**

Zakres techniczny:
- architektura platformy migracyjnej,
- Java / Spring Boot / Kafka / PostgreSQL,
- stan migracji w bazie, idempotency, retry, resilience,
- współbieżność (claimy, leasy, fencing),
- strategia testów.

Model mentalny, który porządkuje cały kod:

| Pojęcie | Znaczenie | Główne klasy |
|---|---|---|
| MOVE | migracja początkowa: pojedynczy kandydat lub cały tenant stronami | `CandidateMigrationService`, `CandidateBatchMigrationService` |
| CATCH UP | dogonienie zmian w źródle przez Kafka | `CandidateChangedListener`, `CandidateDeltaService` |
| REMEMBER | cały postęp w PostgreSQL: stan rekordów, checkpointy, inbox, leasy | `state/jdbc/*Repository` |
| DON'T DUPLICATE | atomowe claimy + fencing token + idempotentny zapis w targecie po `tenantId + externalId` | `JdbcMigrationRecordRepository`, `JdbcCandidateDeltaEventRepository` |
| VERIFY | niezależne porównanie source vs target | `CandidateReconciliationService` |

---

## 2. Zakres zaimplementowany

Wykonywalny zakres obejmuje **wyłącznie kandydatów** (pola: `externalId/id`, `firstName`, `lastName`, `email`)
dla wielu tenantów.

Zaimplementowane:
- migracja pojedynczego kandydata,
- migracja całego tenanta stronami z checkpointem i wznawianiem,
- ograniczona współbieżność (per strona, per instancja),
- stan migracji, joby, inbox zdarzeń i przebiegi reconciliation w PostgreSQL,
- atomowe claimy, leasy i fencing tokeny,
- HTTP timeout / retry / circuit breaker per system zewnętrzny,
- bezpieczna (PII-safe) obsługa błędów zewnętrznych,
- delta synchronization przez Kafka z DLT,
- reconciliation source vs target w PostgreSQL,
- lokalne stuby SAP i SmartRecruiters z wstrzykiwaniem błędów,
- testy z Testcontainers (PostgreSQL, Kafka).

**NIE ZAIMPLEMENTOWANE:** Jobs, Applications, Tenant configuration, zależności między encjami (sekcja 21.1).

---

## 3. Aplikacje i struktura repozytorium

```text
smart-recruters/
├── pom.xml                      migration service (główna aplikacja, port 8080)
├── src/                         kod i testy migration service
├── stubs/
│   ├── sap-stub/                osobny projekt Maven, port 8081
│   └── smartrecruiters-stub/    osobny projekt Maven, port 8082
├── docker-compose.yml           PostgreSQL 17 + Kafka 4.1 (KRaft, single node)
├── README.md
└── smartrecruiters-migration-lab-spec.md
```

Stuby nie są modułami wspólnego buildu: każdy ma własny `pom.xml`.

### 3.1 SAP stub (`stubs/sap-stub`)

Symuluje system źródłowy (dane w pamięci):
- tenanty `tenant-1`, `tenant-2` oraz generowany `tenant-bulk` (1050 kandydatów; co setny bez emaila).

API:

```http
GET /api/tenants/{tenantId}/candidates/{candidateId}
GET /api/tenants/{tenantId}/candidates?page=0&size=100      # sortowanie po id, size ≤ 1000
PUT /api/tenants/{tenantId}/candidates/{candidateId}        # zapis zmiany + publikacja CandidateChangedEvent
POST/GET/DELETE /admin/failures                             # wstrzykiwanie błędów: UNAVAILABLE (503), DELAY
```

Model: `SapCandidate(id, tenantId, firstName, lastName, email)`.

**NIE ZAIMPLEMENTOWANE:** `updatedSince`, pola `status` / `updatedAt` w źródle.

### 3.2 Migration service (repozytorium główne)

Odpowiedzialności (zaimplementowane): ekstrakcja z SAP, mapping, walidacja, zapis do SmartRecruiters,
stan migracji, checkpointy, retry, idempotency, delta synchronization, reconciliation.

Przepływ dla jednego kandydata:

```text
SAP → (SapCandidate) → CandidateMapper → Candidate → CandidateValidator → CandidateMapper → SmartRecruitersCandidateRequest → SmartRecruiters
```

### 3.3 SmartRecruiters stub (`stubs/smartrecruiters-stub`)

Lokalny fake targetu (dane w pamięci), tożsamość biznesowa `tenantId + externalId`:

```http
POST /api/tenants/{tenantId}/candidates                     # create-if-absent: 201 nowy, 200 istniejący (bez zmian)
PUT  /api/tenants/{tenantId}/candidates/{externalId}        # upsert: 201 nowy, 200 zaktualizowany (to samo id)
GET  /api/tenants/{tenantId}/candidates/{externalId}
GET  /api/tenants/{tenantId}/candidates                     # wszystkie (bez stronicowania)
GET  /api/tenants/{tenantId}/candidates?page=0&size=100     # sortowanie po externalId
POST/GET/DELETE /admin/failures                             # UNAVAILABLE (503), UNAVAILABLE_AFTER_SAVE, DELAY_AFTER_SAVE
```

`UNAVAILABLE_AFTER_SAVE` i `DELAY_AFTER_SAVE` symulują zapis, po którym odpowiedź się gubi. To jest scenariusz,
dla którego idempotentny zapis jest niezbędny.

---

## 4. Stack technologiczny (z `pom.xml`)

- Java 25 (virtual threads),
- Spring Boot 4.1.1: Spring MVC, `RestClient`,
- **Spring JDBC z jawnym SQL** (`spring-boot-starter-jdbc`), PostgreSQL, Flyway,
- Spring Kafka (JSON przez Jackson 3),
- Resilience4j (circuit breaker); retry przez `RetryTemplate` ze Spring Framework,
- testy: JUnit 5, Mockito, MockMvc, `MockRestServiceServer`, Testcontainers (PostgreSQL, Kafka),
- lokalnie: `docker-compose.yml` (PostgreSQL 17, Kafka 4.1 KRaft).

**Dlaczego JDBC, a nie JPA.** Kluczowe przejścia stanu, czyli claim, przejęcie przeterminowanego leasa i fenced
zakończenie, to pojedyncze warunkowe instrukcje SQL (`INSERT … ON CONFLICT … DO UPDATE … WHERE`,
`UPDATE … WHERE status = ? AND lease_owner = ?`, `RETURNING`, partial unique index).
Ich semantyka jest widoczna w kodzie i testowana na prawdziwym PostgreSQL. Projekt nie używa Spring Data JPA.

**NIE ZAIMPLEMENTOWANE w stacku:** OpenAPI, Micrometer / metryki, tracing, Actuator, Kubernetes / Helm.

---

## 5. Model danych (Flyway V1–V6)

| Tabela | Migracje | Rola |
|---|---|---|
| `candidate_migration` | V1, V2, V5 | stan migracji kandydata. PK `(tenant_id, source_record_id)`; `status` IN_PROGRESS / COMPLETED / FAILED; `updated_at` (heartbeat leasa); `lease_owner` (fencing token) |
| `tenant_migration_job` | V3 | job migracji tenanta. `status` PENDING / RUNNING / COMPLETED / COMPLETED_WITH_ERRORS / FAILED; `page_size`; `next_page` (checkpoint); liczniki processed / succeeded / skipped / failed; `lease_owner`; `last_error`. Partial unique index: jeden niezakończony (PENDING / RUNNING / FAILED) job na tenanta |
| `candidate_delta_event` | V4 | inbox zdarzeń Kafka. PK `event_id`; tenant / kandydat; `status` IN_PROGRESS / COMPLETED / FAILED; `attempts`; `lease_owner`; `last_error` |
| `reconciliation_run` | V6 | przebieg reconciliation. `status` PENDING / RUNNING / COMPLETED / FAILED; liczniki wyników; `lease_owner`; `last_error`. Partial unique index: jeden niezakończony (PENDING / RUNNING) przebieg na tenanta |
| `candidate_reconciliation_item` | V6 | wiersze robocze. PK `(run_id, external_id)`; `source_seen`, `target_seen`, `source_fingerprint`, `target_fingerprint`, `result` |

Relacje:

```text
candidate_migration        (niezależna, klucz tenant + rekord)
tenant_migration_job       (niezależna, jeden niezakończony per tenant)
candidate_delta_event      (niezależna, klucz eventId)
reconciliation_run ──< candidate_reconciliation_item   (ON DELETE CASCADE)
```

Job batchowy i przebieg reconciliation są **od siebie niezależne**: zakończenie joba nie zależy od reconciliation.

**NIE ZAIMPLEMENTOWANE (wcześniejszy model koncepcyjny):** `MigrationJob` ze statusami NEW / PAUSED / RECONCILING,
`MigrationBatch` (osobna tabela partii), `MigrationRecord` z `targetId` / `sourceVersion`, `AuditEvent` / `audit_event`.
Ich rolę pełnią dziś odpowiednio `tenant_migration_job` (checkpoint = numer strony), `candidate_migration`
i logi aplikacji. Mapowania `sourceId → targetId` nie przechowujemy, bo target jest adresowany po `externalId`.

---

## 6. Migracja pojedynczego kandydata — `CandidateMigrationService`

```text
claim(tenantId, candidateId, leaseOwner = nowy UUID)
   nowy rekord / FAILED / IN_PROGRESS starszy niż migration.claim-timeout → IN_PROGRESS (wygrywa jeden worker)
→ SAP GET candidate        (w ścieżce batch pomijane: kandydat jest już na stronie)
→ map → validate (wymagany niepusty email; błąd: CandidateValidationException)
→ SmartRecruiters POST     (create-if-absent po tenantId + externalId)
→ markCompleted(leaseOwner)
błąd → markFailed(leaseOwner), wyjątek propagowany
```

Wyniki (`CandidateMigrationOutcome`):
- `MIGRATED`: ta próba zapisała kandydata;
- `ALREADY_MIGRATED`: rekord jest już COMPLETED;
- `CLAIMED_BY_OTHER_WORKER`: świeży claim należy do innego workera;
- `LEASE_LOST`: claim tej próby został przejęty, zanim ją zakończyła. Nigdy nie jest raportowany jako `MIGRATED`;
  błąd takiej próby nie jest zapisywany.

**Brak transakcji obejmującej DB i HTTP.** Claim jest osobnym, natychmiast zatwierdzonym statementem; wywołanie
HTTP odbywa się bez otwartej transakcji. Zakończenie to kolejny statement. Crash między zapisem w targecie a
`markCompleted` zostawia rekord IN_PROGRESS. Po `claim-timeout` rekord jest przejmowany i wysyłany ponownie, co jest
bezpieczne dzięki idempotentnemu create po `externalId`.

---

## 7. Batch migration tenanta — `CandidateBatchMigrationService`, `TenantMigrationJobLauncher`

```text
POST /api/migrations/{tenantId}/candidates/batch → 202 + jobId
   (nowy job PENDING albo istniejący niezakończony job tenanta)
worker: tryClaim(job, leaseOwner)   PENDING / FAILED / RUNNING bez heartbeatu > job-lease-timeout → RUNNING
pętla:
   SAP GET ?page=next_page&size=page_size
   → kandydaci strony (virtual thread per kandydat, Semaphore = migration.parallelism)
   → CandidateMigrationService (bez ponownego pobierania z SAP)
   → jeden UPDATE: next_page + liczniki + heartbeat, warunkowo na lease_owner
koniec stron (hasNext=false lub pusta strona) → COMPLETED / COMPLETED_WITH_ERRORS (failed_count > 0)
```

- **Checkpoint** to numer następnej strony (`next_page`) przy stałym `page_size` joba. Jest zapisywany dopiero po
  przetworzeniu całej strony, razem z licznikami w jednym statemencie.
- **Restart**: crash w środku strony powoduje ponowne przetworzenie tej strony. Kandydaci już COMPLETED liczą się
  jako `succeeded` i nie są wysyłani ponownie.
- **Liczniki** (`processed = succeeded + skipped + failed`):
  - `succeeded`: kandydat jest w targecie (teraz lub wcześniej);
  - `skipped`: kandydat jest trzymany przez innego workera albo claim tej próby przejęto;
  - `failed`: ta próba się nie powiodła.
- **Izolacja błędów**: błąd jednego kandydata nie zatrzymuje strony ani joba.
- **Błąd odczytu strony z SAP** → job `FAILED` z checkpointem. Ponowny `POST` dla tenanta zwraca **ten sam** job
  i wznawia go od checkpointu.
- **Wykonanie w tle**:
  - stała pula wątków `migration.max-concurrent-jobs`, kolejka `migration.job-queue-capacity`;
  - job, który się nie mieści, zostaje PENDING;
  - recovery scan (`migration.job-recovery-interval`, start po `ApplicationReadyEvent`) uruchamia joby PENDING
    i przejmuje porzucone RUNNING.
- **Fencing**: po utracie leasa każdy zapis postępu zwraca `false` i worker kończy pracę.

Domyślne wartości: `batch-size` 100, `parallelism` 8, `max-concurrent-jobs` 2, `job-queue-capacity` 100,
`job-lease-timeout` 10m (musi być ≥ `claim-timeout` 5m), `job-recovery-interval` 1m.

**NIE ZAIMPLEMENTOWANE:** retry pojedynczej partii jako osobnej encji, pause / resume, chunk tables,
paginacja keyset (`lastProcessedSourceId`).

---

## 8. Idempotency

| Ścieżka | Klucz idempotencji | Mechanizm |
|---|---|---|
| migracja początkowa | `tenantId + sourceRecordId` | rekord `candidate_migration`; COMPLETED nigdy nie jest przetwarzany ponownie |
| delta | `eventId` | rekord `candidate_delta_event`; COMPLETED oznacza duplikat, który jest ignorowany |
| target | `tenantId + externalId` | POST = create-if-absent, PUT = upsert. Bez duplikatów także przy retry po zgubionej odpowiedzi |

Delta **nie** używa `candidate_migration` jako idempotencji: ten sam kandydat zmienia się wielokrotnie,
a każde zdarzenie (inny `eventId`) jest poprawne i przetwarzane.

---

## 9. Współbieżność: claimy, leasy, fencing

Aplikacja może działać w wielu instancjach, więc nie używamy blokad JVM jako zabezpieczenia.

- **Claim**: jeden atomowy statement, np. dla `candidate_migration`:

```sql
INSERT INTO candidate_migration (tenant_id, source_record_id, status, lease_owner, updated_at)
VALUES (?, ?, 'IN_PROGRESS', ?, now())
ON CONFLICT (tenant_id, source_record_id)
DO UPDATE SET status = 'IN_PROGRESS', lease_owner = EXCLUDED.lease_owner, updated_at = now()
WHERE candidate_migration.status = 'FAILED'
   OR (candidate_migration.status = 'IN_PROGRESS'
       AND candidate_migration.updated_at < now() - (? * INTERVAL '1 millisecond'))
```

  Zmieniony 1 wiersz oznacza, że worker zdobył pracę; 0 wierszy, że pracę ma ktoś inny albo jest już zakończona.
  Czas pochodzi z zegara bazy (`now()`).

- **Fencing**: każdy claim zapisuje nowy `lease_owner` (UUID per próba). Zakończenie wymaga
  `status = 'IN_PROGRESS' AND lease_owner = ?`. Worker, którego lease przejęto, nie może zmienić wyniku.
  Ten sam wzorzec mają `candidate_delta_event`, `tenant_migration_job` i `reconciliation_run`.
- **Jeden niezakończony job / przebieg na tenanta**: partial unique index + `INSERT … ON CONFLICT DO NOTHING`.
- Każda z tych reguł jest testowana równoległymi workerami na prawdziwym PostgreSQL (Testcontainers).

Ograniczenie: fencing chroni stan w bazie, a nie wysłany już request HTTP (sekcja 18).

---

## 10. Delta synchronization (Kafka)

Batch nie wystarcza, bo źródło zmienia się w trakcie migracji. Ścieżka delta dogania te zmiany.

```text
SAP stub PUT candidate → topic candidate-changes (key tenantId:candidateId)
  → CandidateChangedListener (cienki adapter) → CandidateDeltaService
       → claim eventId w candidate_delta_event (lease_owner)
       → SAP GET aktualny stan kandydata
       → map → validate → SmartRecruiters PUT (upsert)
       → markCompleted(lease_owner)
```

- **Zdarzenie**: `CandidateChangedEvent { eventId, tenantId, candidateId, occurredAt }`. Zawiera tylko tożsamość:
  dane zawsze pobieramy aktualne z SAP, więc duplikaty zbiegają do bieżącego stanu źródła. Format to zwykły
  JSON bez nagłówków typu.
- **Dostarczanie at-least-once.** Poprawność zapewniają inbox po `eventId`, fenced zakończenie i idempotentny upsert.
  Nie ma exactly-once.
- **Claim nieudany**:
  - COMPLETED → ignorowany duplikat;
  - świeży IN_PROGRESS → `DeltaEventInProgressException` (retryable);
  - `eventId` zapisany dla innego tenanta / kandydata → błąd trwały.
- Zdarzenie **FAILED** jest przejmowane przy ponownym dostarczeniu.
- **Klasyfikacja błędów**:
  - trwałe (`CandidateValidationException`, trwały błąd HTTP, niekompletne lub niespójne zdarzenie) →
    `PermanentDeltaEventException`;
  - utrata leasa → `DeltaEventLeaseLostException` (retryable). Oryginalny błąd jest dołączony jako suppressed,
    a nie jako cause;
  - pozostałe, w tym zwykły `IllegalArgumentException`, są retryable.
- **Retry Kafka** (`DefaultErrorHandler`):
  - **blokujące** ponowienia na wątku konsumenta z wykładniczym back-off (`migration.kafka.retry-*`: 1s, ×2, max 10s);
  - maksymalnie `migration.kafka.max-attempts` (domyślnie 3) dostarczeń, potem `candidate-changes.DLT`;
  - `PermanentDeltaEventException` i błędy deserializacji trafiają do DLT od razu.
  - Retry Kafka jest oddzielone od retry HTTP: jedno dostarczenie obejmuje już ponowienia HTTP.
- **Kolejność**: per klucz partycji `tenantId:candidateId`. Blokujące retry zachowują kolejność w partycji.
- **Konsument**: grupa `migration-service`, `ack-mode: record`, `concurrency` 3, 3 partycje (topiki tworzone przez aplikację).

**NIE ZAIMPLEMENTOWANE:** `processed_event` (zastąpione przez `candidate_delta_event`), retry topics
(nieblokujące), entity version / sequence number (ochrona przed out-of-order), domain events / CDC / outbox po stronie
źródła, polling po `updatedAt`, topiki dla innych encji (`job-changes` itd.), narzędzie do replay DLT,
propagacja usunięć.

---

## 11. Retry / timeout / circuit breaker

Każde wywołanie SAP i SmartRecruiters przechodzi przez `ExternalCallExecutor`:

```text
CircuitBreaker (jeden na system zewnętrzny) → Retry (tylko transient) → HTTP (connect/read timeout)
```

- Jedno wywołanie biznesowe to jeden wynik circuit breakera, niezależnie od liczby ponowień.
- **Transient** (`HttpFailureClassifier`, dokładnie):
  - HTTP **408, 429, 502, 503, 504**;
  - błędy I/O bez odpowiedzi (timeout, connection refused / reset).
- **Permanent**: wszystkie pozostałe statusy, w tym 400, 401, 403, 404, 409, 422 oraz **500**. Traktowanie 500 jako
  trwałego to obecna polityka projektu. Błędy trwałe nie są ponawiane i nie liczą się jako porażki breakera.
- **Retry HTTP** (`clients.retry.*`): 3 próby, back-off 200 ms, ×2, max 2 s. Wykładniczy, **bez jittera**.
- **Timeout** (`clients.{sap,smartrecruiters}.*`): connect 1 s, read 3 s.
- **Circuit breaker** (`clients.circuit-breaker.*`): okno count-based 10, min. 5 wywołań, próg 50 %, 30 s w OPEN,
  2 wywołania w HALF_OPEN. OPEN oznacza fail-fast (transient) bez wysyłania żądania HTTP.

**NIE ZAIMPLEMENTOWANE:** jitter, rate limiter.

---

## 12. Backpressure

Zaimplementowane ograniczenia:
- `migration.parallelism`: kandydaci przetwarzani jednocześnie w jednym jobie (virtual threads + `Semaphore`;
  same virtual threads nie ograniczają współbieżności);
- `migration.max-concurrent-jobs` + `job-queue-capacity`: joby na instancję;
- `migration.reconciliation.max-concurrent-runs` + `queue-capacity`: przebiegi reconciliation na instancję;
- `migration.batch-size` / `migration.reconciliation.page-size`: rozmiar strony w pamięci;
- `spring.kafka.listener.concurrency`: konsumenci delta;
- circuit breaker, który chroni niedostępny system.

**NIE ZAIMPLEMENTOWANE:** globalny / per-tenant rate limiter ruchu wychodzącego, monitoring consumer lag.

---

## 13. Mapping i walidacja

Zaimplementowane warstwy (anti-corruption layer w minimalnej formie):

```text
SapCandidate (model źródła) → Candidate (model kanoniczny) → SmartRecruitersCandidateRequest (model targetu)
```

- `CandidateMapper` kopiuje `id → externalId`, `firstName`, `lastName` i `email` bez transformacji.
- `CandidateValidator` ma jedną regułę: niepusty email. Naruszenie zgłasza `CandidateValidationException`
  (kontrolowany komunikat, bez danych kandydata).
- Spójność tenanta w ścieżce delta i reconciliation: rekord ze źródła musi należeć do przetwarzanego tenanta.

**NIE ZAIMPLEMENTOWANE:** mapowanie statusów / enumów, walidacja formatu email, dozwolone wartości,
integralność referencyjna między encjami.

---

## 14. Reconciliation

Sukces zapisu nie jest dowodem sukcesu migracji. Reconciliation niezależnie porównuje źródło i target.

```text
POST /api/reconciliation/{tenantId}/candidates → 202 + runId
worker: claim run (PENDING → RUNNING, lease_owner)
  → SAP strony:            CandidateMapper → CandidateFingerprint → batch upsert (source_seen, source_fingerprint)
  → SmartRecruiters strony: CandidateFingerprint                 → batch upsert (target_seen, target_fingerprint)
  → jedna transakcja: klasyfikacja wszystkich wierszy w SQL + liczniki → COMPLETED
błąd → FAILED (wiersze robocze zostają do diagnozy)
```

- **Pamięć**: w aplikacji jest najwyżej jedna strona. Logika zbiorowa (obecność po obu stronach, równość,
  liczniki) działa w PostgreSQL.
- **Wyniki**:
  - `MATCHED`: po obu stronach, równe fingerprinty;
  - `MISMATCHED`: po obu stronach, różne fingerprinty;
  - `MISSING_IN_TARGET`: tylko w źródle;
  - `UNEXPECTED_IN_TARGET`: tylko w targecie.
- **Fingerprint** (`CandidateFingerprint`): SHA-256 z wersjonowanej, length-prefixed postaci kanonicznej
  pól `externalId, firstName, lastName, email`.
  - Kanonizacja: Unicode NFC, `String.strip()`, null == blank, **bez case folding**.
  - Równe fingerprinty są traktowane jako równoważność w sensie tych reguł (kolizje SHA-256 uznane za pomijalne),
    a nie jako identyczność znak po znaku.
- **Niezależność**:
  - `candidate_migration` nie jest odczytywane;
  - oba systemy są tylko czytane;
  - `CandidateValidator` celowo nie jest stosowany. Kandydat, którego migracja by odrzuciła, wychodzi jako
    `MISSING_IN_TARGET`, albo jako `MATCHED`, jeśli równoważny kandydat już jest w targecie.
- **Jeden niezakończony przebieg na tenanta.** Przebiegi nie są wznawiane: porzucony RUNNING (brak heartbeatu przez
  `migration.reconciliation.lease-timeout`) staje się FAILED, a nowy przebieg zaczyna od zera.
- **Model spójności**: eventually-consistent verification pass, nie rozproszony snapshot. Konsumenci Kafka działają
  dalej, więc zmiana w trakcie skanu może dać przejściową różnicę. Reconciliation uruchamia się po migracji
  początkowej i ponownie po opróżnieniu backlogu delta, do uzyskania stabilnego wyniku.
- **PII**: w tabelach reconciliation są tylko identyfikatory i fingerprinty.
- Zakończenie joba batchowego **nie** zależy od reconciliation; to niezależne procesy.

---

## 15. REST API migration service (port 8080)

```http
POST /api/migrations/{tenantId}/candidates/{candidateId}       # migracja jednego kandydata, synchronicznie, 204
POST /api/migrations/{tenantId}/candidates/batch               # start (lub istniejący niezakończony) jobu, 202 + Location
GET  /api/migrations/jobs/{jobId}                              # status, checkpoint, liczniki
POST /api/reconciliation/{tenantId}/candidates                 # start (lub istniejący niezakończony) przebiegu, 202 + Location
GET  /api/reconciliation/runs/{runId}                          # status i liczniki
GET  /api/reconciliation/runs/{runId}/items?result=&page=0&size=100
     # elementy zakończonego przebiegu; size ≤ 1000; bez result: wszystkie różnice; 409 gdy przebieg nie jest COMPLETED
```

Przykładowa odpowiedź `GET /api/migrations/jobs/{jobId}`:

```json
{
  "jobId": "…", "tenantId": "tenant-bulk", "status": "COMPLETED_WITH_ERRORS",
  "pageSize": 100, "nextPage": 11,
  "processedCount": 1050, "succeededCount": 1040, "skippedCount": 0, "failedCount": 10,
  "lastError": null, "createdAt": "…", "updatedAt": "…"
}
```

Ścieżka delta nie ma endpointu REST: steruje nią topic Kafka.

**NIE ZAIMPLEMENTOWANE:** `pause`, `resume`, lista partii, endpoint błędów, lista jobów tenanta, dedykowane
mapowanie błędów endpointu synchronicznego (używana jest domyślna obsługa Springa).

---

## 16. Bezpieczeństwo

Zaimplementowane w kodzie:
- **PII-safe błędy zewnętrzne**: `ExternalSystemException` zawiera tylko operację, typ błędu (TRANSIENT / PERMANENT),
  kategorię (`HTTP_RESPONSE`, `TIMEOUT`, `TRANSPORT_ERROR`, `INVALID_RESPONSE`, `CIRCUIT_OPEN`) i status HTTP.
  - Treść odpowiedzi upstream ani surowy wyjątek HTTP nie trafiają do komunikatów, łańcucha przyczyn, logów,
    `last_error` ani nagłówków DLT.
  - Pokrywają to testy z wartownikiem (sentinel PII w treści odpowiedzi).
- Kontrolowane komunikaty walidacji, bez wartości pól kandydata.
- Brak danych kandydata w tabelach reconciliation; zdarzenia Kafka niosą tylko identyfikatory.
- Izolacja tenantów w kluczach stanu i w sprawdzeniach spójności (sekcja 17).
- Sekrety połączeń przez zmienne środowiskowe (`DB_PASSWORD` itd.); w repozytorium są tylko lokalne wartości domyślne.

Wymagania środowiska produkcyjnego (**NIE ZAIMPLEMENTOWANE / poza repozytorium**): TLS, szyfrowanie at rest,
zarządzanie sekretami, least privilege, audit trail, polityka retencji, procesy GDPR.

---

## 17. Multi-tenancy

Zaimplementowane:
- `tenant_id` w kluczu stanu kandydata i zdarzeń; przebiegi i joby są per tenant;
- ten sam identyfikator w dwóch tenantach to zawsze dwa niezależne rekordy (testy);
- delta: `eventId` zapisany dla innego tenanta nie może być przejęty; rekord SAP innego tenanta nie jest zapisywany;
- reconciliation: kandydat innego tenanta w odpowiedzi SAP przerywa przebieg.

Zasada: samo `tenant_id` w tabeli nie rozwiązuje multi-tenancy.

**NIE ZAIMPLEMENTOWANE:** credentials per tenant, rate limits per tenant, konfiguracja mapowania per tenant.

---

## 18. Ograniczenia i świadome kompromisy

- **Paginacja offsetowa** (numer strony) na żywych danych: strony mogą się przesuwać. Rekord może zostać przeczytany
  dwa razy (nieszkodliwe) albo pominięty w danym przebiegu.
- Reconciliation nie jest snapshotem; przebiegi nie są wznawiane; wiersze robocze nie mają retencji.
- Fencing nie cofa wysłanego już requestu HTTP starego workera. Dla create-if-absent jest to nieszkodliwe;
  dla upsert w delta koryguje to następne zdarzenie.
- Delta bez wersji / sekwencji źródła: dwa równoległe przetworzenia tego samego kandydata (np. rebalance)
  mogą zapisać starszy odczyt jako ostatni.
- Emisja zdarzeń w SAP stub nie jest outboxem (zapis, potem publikacja; przy błędzie publikacji 503).
- Retry Kafka blokuje partycję na czas back-off (ograniczone `max-attempts`).
- Brak narzędzia do replay DLT i brak propagacji usunięć.
- Brak globalnego limitu ruchu wychodzącego.
- Obserwowalność ograniczona do logów.

---

## 19. Obserwowalność

Zaimplementowane: logi SLF4J z identyfikatorami jobu / przebiegu / zdarzenia i tenanta, wynikami skanów
i bezpiecznymi komunikatami błędów.

**NIE ZAIMPLEMENTOWANE (przyszłe utwardzenie):**
- metryki (np. Micrometer): `records_processed_total`, `records_failed_total`, `retry_count`, `dlq_size`,
  `consumer_lag`, `reconciliation_mismatches`, `target_api_latency`;
- tracing (extraction → mapping → validation → load);
- dashboardy, health / Actuator, structured logging (JSON).

---

## 20. Deployment

Zaimplementowane: `docker-compose.yml` uruchamia PostgreSQL 17 i Kafka 4.1 (KRaft). Migration service i stuby
uruchamia się Mavenem (`mvn spring-boot:run`) albo jako jary. Szczegóły są w `README.md`.

**NIE ZAIMPLEMENTOWANE (możliwy kierunek produkcyjny):** obrazy kontenerów usług, Kubernetes / Helm, np. EKS z
osobnymi deploymentami (API, batch worker, delta processor, reconciliation worker), RDS PostgreSQL, MSK;
rolling / canary deployment; reguły kompatybilności schematu DB i zdarzeń. Obecnie wszystkie role działają w
jednej aplikacji.

---

## 21. Przyszłe rozszerzenia (NIE ZAIMPLEMENTOWANE)

### 21.1 Kolejne encje

```text
Candidate ─┐
           ├──> Application
Job ───────┘
```

Candidate i Job mogłyby migrować równolegle. Application dopiero po migracji obu i zapisaniu mapowań
source → target. Dla takich zależności potrzebne byłoby przechowywanie `targetId`.

### 21.2 Utwardzenie delta / Kafka
- entity version / sequence number ze źródła (odrzucanie out-of-order),
- nieblokujące retry topics,
- outbox / CDC po stronie źródła, polling `updatedSince` jako fallback,
- replay DLT, propagacja usunięć.

### 21.3 Operacje
- pause / resume jobów, lista błędów per job, audit trail,
- uzależnienie „cutover ready” od stabilnego wyniku reconciliation (obecnie job i reconciliation są niezależne),
- paginacja keyset, wznawianie reconciliation, retencja wierszy roboczych,
- rate limiter per tenant / globalny.

### 21.4 AI-assisted migration (wyłącznie koncepcja)

AI mogłoby proponować mapping pól, wykrywać podobne schematy, sugerować transformacje, wykrywać anomalie
i wspierać operatora. Nie powinno samodzielnie wykonywać nieodwracalnych zmian, zatwierdzać mappingów o niskiej
pewności, robić cutoveru ani nadpisywać deterministycznych reguł bez audytu. Proponowany flow:

```text
schema comparison → AI suggestion → human review → approved deterministic rule → migration
```

---

## 22. Testy (stan obecny)

- **Unit**: mapper, walidator, klasyfikacja błędów HTTP, fingerprint, sanitizacja błędów.
- **Klienci HTTP**: `MockRestServiceServer` (retry, brak retry dla błędów trwałych); timeout na prawdziwym serwerze
  loopback; circuit breaker z kontrolowanym zegarem.
- **Integracyjne (Testcontainers PostgreSQL)**: claimy, przejęcie leasa i fencing przy równoległych workerach,
  checkpointy jobów, inbox delta, SQL reconciliation.
- **Integracyjne (Testcontainers Kafka)**: dostarczenie JSON, ograniczone retry, błąd trwały → DLT,
  nieczytelny payload → DLT, `IllegalArgumentException` retryable, utrata leasa retryable.
- **Serwisowe z fake'ami w pamięci**: stronicowanie batch, crash przed checkpointem i replay, utrata leasa,
  limit współbieżności, idempotency delta, izolacja tenantów.
- **Kontrolery**: MockMvc.
- **Stuby**: własne testy (`mvn -f stubs/<stub>/pom.xml test`).
- **Test kontekstu aplikacji**: pełny kontekst na Testcontainers PostgreSQL ze wszystkimi migracjami Flyway,
  bez potrzeby lokalnego PostgreSQL ani Kafka (wymaga Dockera).

Uruchomienie: `mvn test` (zwykły Maven).

**NIE ZAIMPLEMENTOWANE:** automatyczny test end-to-end uruchamiający wszystkie trzy aplikacje razem
(taki przebieg wykonywano ręcznie na lokalnym stacku).

---

## 23. Status milestone'ów

| Milestone | Status |
|---|---|
| 1. Happy path: SAP stub, SmartRecruiters stub, mapping, validation, load | zrobione |
| 2. Stan migracji: PostgreSQL, checkpoint, resume | zrobione (`candidate_migration`, `tenant_migration_job`; bez `MigrationBatch` / `AuditEvent`) |
| 3. Resilience: timeout, retry, backoff, circuit breaker, idempotency | zrobione (bez jittera) |
| 4. Concurrency: atomic claim, równoległe workery, limit współbieżności, virtual threads | zrobione (+ leasy i fencing) |
| 5. Kafka delta: candidate-changes, eventId, idempotent consumer, DLQ | zrobione (DLT; bez entity version i retry topics) |
| 6. Reconciliation: liczniki, brakujące ID, raport różnic | zrobione (fingerprinty w PostgreSQL) |
| 7. Observability: metrics, structured logs, tracing, dashboards | nie zrobione (tylko logi) |
| 8. Deployment: docker-compose / Kubernetes / AWS | częściowo (docker-compose dla infrastruktury) |

---

## 24. Zasady rozwoju projektu

- małe, inkrementalne kroki,
- najpierw prosty działający flow, potem resilience i współbieżność,
- review kodu po każdym etapie,
- każdy mechanizm dokumentujemy: co, dlaczego, trade-off, scenariusz awarii,
- dokumentacja opisuje stan faktyczny; plany są oznaczone jako niezaimplementowane.
- plan (NIE ZAIMPLEMENTOWANE): Java najpierw, potem wybrane elementy porównać z implementacją w Kotlinie.

---

## 25. Kluczowe zagadnienia inżynierskie

1. Architektura platformy migracyjnej.
2. Batch + delta synchronization + reconciliation.
3. Kafka: partycje, consumer groups, at-least-once, DLT.
4. Idempotency i atomowe przejścia stanu w PostgreSQL.
5. Leasy i fencing tokeny przy wielu workerach.
6. Transakcje DB vs wywołania zewnętrznego API (brak transakcji rozproszonej).
7. Restartowalna migracja z checkpointami.
8. Retry / circuit breaker / klasyfikacja błędów.
9. Multi-tenancy i bezpieczeństwo PII.
10. Jakość implementacji weryfikowana testami i code review.
