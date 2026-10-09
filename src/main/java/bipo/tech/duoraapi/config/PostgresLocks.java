package bipo.tech.duoraapi.config;

import java.util.function.Supplier;

import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * O teto de espera por lock do PostgreSQL e a tradução do estouro dele. Cada adapter escolhe o próprio teto e
 * a mensagem; o mecanismo é igual em todos.
 */
public final class PostgresLocks {

    /** lock_not_available, na tabela de códigos de erro do PostgreSQL: o lock_timeout estourou. */
    private static final String LOCK_NOT_AVAILABLE = "55P03";

    private PostgresLocks() {
    }

    /**
     * {@code set local lock_timeout}: o teto vale até o fim da transação atual, também para o que ela grava
     * depois de travar. Chame antes do comando que espera o lock.
     *
     * @param timeout a duração no formato do PostgreSQL, como {@code "2s"}
     */
    public static void limitWait(JdbcClient jdbcClient, String timeout) {
        jdbcClient.sql("select set_config('lock_timeout', :timeout, true)")
                .param("timeout", timeout)
                .query(String.class)
                .single();
    }

    /**
     * Roda o comando e, se o {@code lock_timeout} estourar, lança {@link CannotAcquireLockException}. O
     * {@code JdbcClient} não traduz o 55P03, e o resto da aplicação trata lock não obtido como 503.
     */
    public static <T> T translatingTimeout(Supplier<T> statement, String message) {
        try {
            return statement.get();
        } catch (UncategorizedSQLException e) {
            if (LOCK_NOT_AVAILABLE.equals(e.getSQLException().getSQLState())) {
                throw new CannotAcquireLockException(message, e);
            }
            throw e;
        }
    }

}
