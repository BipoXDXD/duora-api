package bipo.tech.duoraapi.trustsafety.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class SqlStatesTest {

    private static final String FOREIGN_KEY_VIOLATION = "23503";
    private static final String UNIQUE_VIOLATION = "23505";

    @Test
    void recognizesAForeignKeyViolationInTheCause() {
        var exception = new DataIntegrityViolationException("insert failed", new SQLException("fk", FOREIGN_KEY_VIOLATION));

        assertThat(SqlStates.isForeignKeyViolation(exception)).isTrue();
    }

    @Test
    void recognizesAForeignKeyViolationDeeperInTheCauseChain() {
        var sqlException = new SQLException("fk", FOREIGN_KEY_VIOLATION);
        var exception = new DataIntegrityViolationException("insert failed", new IllegalStateException(sqlException));

        assertThat(SqlStates.isForeignKeyViolation(exception)).isTrue();
    }

    /** Só a FK vira "conta não existe": outra violação de integridade segue como erro inesperado. */
    @Test
    void anotherIntegrityViolationIsNotAForeignKeyViolation() {
        var exception = new DataIntegrityViolationException("insert failed", new SQLException("dup", UNIQUE_VIOLATION));

        assertThat(SqlStates.isForeignKeyViolation(exception)).isFalse();
    }

    @Test
    void exceptionWithoutASqlCauseIsNotAForeignKeyViolation() {
        assertThat(SqlStates.isForeignKeyViolation(new DataIntegrityViolationException("insert failed"))).isFalse();
    }

}
