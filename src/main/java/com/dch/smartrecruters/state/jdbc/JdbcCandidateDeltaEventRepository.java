package com.dch.smartrecruters.state.jdbc;

import com.dch.smartrecruters.state.CandidateDeltaEventRepository;
import com.dch.smartrecruters.state.DeltaEventRecord;
import com.dch.smartrecruters.state.MigrationStatus;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public class JdbcCandidateDeltaEventRepository implements CandidateDeltaEventRepository {

    private static final int MAX_ERROR_LENGTH = 1000;

    private final DataSource dataSource;
    private final Duration claimTimeout;

    public JdbcCandidateDeltaEventRepository(DataSource dataSource, Duration claimTimeout) {
        if (claimTimeout == null || claimTimeout.isNegative() || claimTimeout.isZero()) {
            throw new IllegalArgumentException("claimTimeout must be positive");
        }
        this.dataSource = dataSource;
        this.claimTimeout = claimTimeout;
    }

    /**
     * Same single-statement claim as candidate_migration, keyed by event_id, plus a fencing token:
     * every successful claim (new, FAILED, stale IN_PROGRESS) installs {@code leaseOwner}.
     * The tenant/candidate condition keeps a reused eventId from ever claiming another candidate's event.
     * Time is taken from the database clock (now()).
     */
    @Override
    public boolean tryClaim(UUID eventId, String tenantId, String candidateId, Instant occurredAt, UUID leaseOwner) {
        String sql = """
                INSERT INTO candidate_delta_event (
                    event_id,
                    tenant_id,
                    candidate_id,
                    status,
                    lease_owner,
                    occurred_at,
                    updated_at
                )
                VALUES (?, ?, ?, 'IN_PROGRESS', ?, ?, now())
                ON CONFLICT (event_id)
                DO UPDATE SET status = 'IN_PROGRESS',
                              lease_owner = EXCLUDED.lease_owner,
                              attempts = candidate_delta_event.attempts + 1,
                              updated_at = now()
                WHERE candidate_delta_event.tenant_id = EXCLUDED.tenant_id
                  AND candidate_delta_event.candidate_id = EXCLUDED.candidate_id
                  AND (candidate_delta_event.status = 'FAILED'
                       OR (candidate_delta_event.status = 'IN_PROGRESS'
                           AND candidate_delta_event.updated_at < now() - (? * INTERVAL '1 millisecond')))
                """;

        try (
                Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            statement.setObject(1, eventId);
            statement.setString(2, tenantId);
            statement.setString(3, candidateId);
            statement.setObject(4, leaseOwner);
            statement.setTimestamp(5, Timestamp.from(occurredAt));
            statement.setLong(6, claimTimeout.toMillis());

            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot claim delta event", e);
        }
    }

    @Override
    public Optional<DeltaEventRecord> findById(UUID eventId) {
        String sql = """
                SELECT event_id, tenant_id, candidate_id, status, attempts, last_error
                FROM candidate_delta_event
                WHERE event_id = ?
                """;

        try (
                Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            statement.setObject(1, eventId);

            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(new DeltaEventRecord(
                        resultSet.getObject("event_id", UUID.class),
                        resultSet.getString("tenant_id"),
                        resultSet.getString("candidate_id"),
                        MigrationStatus.valueOf(resultSet.getString("status")),
                        resultSet.getInt("attempts"),
                        resultSet.getString("last_error")
                ));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read delta event", e);
        }
    }

    @Override
    public boolean markCompleted(UUID eventId, UUID leaseOwner) {
        return finish(eventId, leaseOwner, MigrationStatus.COMPLETED, null);
    }

    @Override
    public boolean markFailed(UUID eventId, UUID leaseOwner, String error) {
        return finish(eventId, leaseOwner, MigrationStatus.FAILED, error);
    }

    /**
     * Fenced: only the current owner of an IN_PROGRESS event can finish it. status = 'IN_PROGRESS' alone
     * is not enough - after a stale lease was reclaimed the row is IN_PROGRESS again, but for another owner.
     * The owner is cleared, so nobody can change a finished event any more.
     */
    private boolean finish(UUID eventId, UUID leaseOwner, MigrationStatus status, String error) {
        String sql = """
                UPDATE candidate_delta_event
                SET status = ?,
                    last_error = ?,
                    lease_owner = NULL,
                    updated_at = now()
                WHERE event_id = ?
                  AND status = 'IN_PROGRESS'
                  AND lease_owner = ?
                """;

        try (
                Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            statement.setString(1, status.name());
            statement.setString(2, truncate(error));
            statement.setObject(3, eventId);
            statement.setObject(4, leaseOwner);

            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot update delta event status", e);
        }
    }

    private static String truncate(String error) {
        if (error == null || error.length() <= MAX_ERROR_LENGTH) {
            return error;
        }
        return error.substring(0, MAX_ERROR_LENGTH);
    }
}
