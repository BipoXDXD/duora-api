package bipo.tech.duoraapi.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;

import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.UncategorizedSQLException;

class PostgresLocksTest {

    private static final String LOCK_NOT_AVAILABLE = "55P03";
    private static final String DEADLOCK_DETECTED = "40P01";
    private static final String MESSAGE = "timed out waiting for the lock";

    @Test
    void returnsWhatTheStatementReturns() {
        assertThat(PostgresLocks.translatingTimeout(() -> 42, MESSAGE)).isEqualTo(42);
    }

    @Test
    void translatesALockTimeoutIntoACannotAcquireLockException() {
        var timeout = uncategorized(LOCK_NOT_AVAILABLE);

        assertThatThrownBy(() -> PostgresLocks.translatingTimeout(() -> {
            throw timeout;
        }, MESSAGE))
                .isInstanceOf(CannotAcquireLockException.class)
                .hasMessageContaining(MESSAGE)
                .hasCause(timeout);
    }

    /** Só o estouro do lock_timeout vira 503: outro erro de SQL segue como erro inesperado. */
    @Test
    void anotherSqlErrorPropagatesUntouched() {
        var deadlock = uncategorized(DEADLOCK_DETECTED);

        assertThatThrownBy(() -> PostgresLocks.translatingTimeout(() -> {
            throw deadlock;
        }, MESSAGE)).isSameAs(deadlock);
    }

    private static UncategorizedSQLException uncategorized(String sqlState) {
        return new UncategorizedSQLException("statement", "select 1", new SQLException("failed", sqlState));
    }

}
