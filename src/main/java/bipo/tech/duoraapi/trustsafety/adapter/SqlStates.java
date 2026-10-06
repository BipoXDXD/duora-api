package bipo.tech.duoraapi.trustsafety.adapter;

import java.sql.SQLException;

/** Lê o SQLSTATE do PostgreSQL na causa de uma exceção traduzida pelo Spring. */
final class SqlStates {

    /** foreign_key_violation, na tabela de códigos de erro do PostgreSQL. */
    private static final String FOREIGN_KEY_VIOLATION = "23503";

    private SqlStates() {
    }

    static boolean isForeignKeyViolation(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException) {
                return FOREIGN_KEY_VIOLATION.equals(sqlException.getSQLState());
            }
        }
        return false;
    }

}
