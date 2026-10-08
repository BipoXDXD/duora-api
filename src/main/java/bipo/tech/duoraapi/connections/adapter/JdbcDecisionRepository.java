package bipo.tech.duoraapi.connections.adapter;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import bipo.tech.duoraapi.connections.domain.ConnectionPair;
import bipo.tech.duoraapi.connections.domain.Decision;
import bipo.tech.duoraapi.connections.domain.DecisionRepository;
import bipo.tech.duoraapi.identity.AccountId;

/** Decisões na tabela round_decision, por SQL (docs/adr/0019). */
@Repository
class JdbcDecisionRepository implements DecisionRepository {

    /**
     * Quanto uma decisão espera pela do par na mesma rodada. A outra transação só grava duas ou três linhas;
     * passar do teto indica algo preso, e a resposta é 503.
     */
    static final String LOCK_TIMEOUT = "2s";

    /** lock_not_available, na tabela de códigos de erro do PostgreSQL: o lock_timeout estourou. */
    private static final String LOCK_NOT_AVAILABLE = "55P03";

    /** Prefixo da chave do advisory lock, para não colidir com outro uso de advisory lock no banco. */
    private static final String LOCK_NAMESPACE = "connections.decision";

    private final JdbcClient jdbcClient;

    JdbcDecisionRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public void limitLockWait() {
        jdbcClient.sql("select set_config('lock_timeout', :timeout, true)")
                .param("timeout", LOCK_TIMEOUT)
                .query(String.class)
                .single();
    }

    /**
     * Advisory lock de transação na chave (evento, rodada, par): não há linha para travar enquanto ninguém
     * decidiu, e o lock some sozinho no commit ou rollback. A chave vira um bigint por
     * {@code hashtextextended}; uma colisão entre pares diferentes só faz um esperar o outro.
     */
    @Override
    public void lockPair(UUID eventId, int roundNumber, ConnectionPair pair) {
        String key = String.join(":", LOCK_NAMESPACE, eventId.toString(), Integer.toString(roundNumber),
                pair.first().value().toString(), pair.second().value().toString());
        try {
            jdbcClient.sql("select 1 from (select pg_advisory_xact_lock(hashtextextended(:key, 0))) as locked")
                    .param("key", key)
                    .query(Integer.class)
                    .single();
        } catch (UncategorizedSQLException e) {
            // O JdbcClient não traduz o lock_timeout (55P03); o resto da aplicação o trata como lock não obtido.
            if (LOCK_NOT_AVAILABLE.equals(e.getSQLException().getSQLState())) {
                throw new CannotAcquireLockException("timed out waiting for the partner's decision", e);
            }
            throw e;
        }
    }

    @Override
    public boolean addIfAbsent(Decision decision) {
        int inserted = jdbcClient.sql("""
                        insert into round_decision
                            (event_id, round_number, account_id, partner_account_id, interested, decided_at)
                        values (:eventId, :roundNumber, :accountId, :partnerAccountId, :interested, :decidedAt)
                        on conflict (event_id, round_number, account_id) do nothing
                        """)
                .param("eventId", decision.eventId())
                .param("roundNumber", decision.roundNumber())
                .param("accountId", decision.decider().value())
                .param("partnerAccountId", decision.partner().value())
                .param("interested", decision.interested())
                .param("decidedAt", OffsetDateTime.ofInstant(decision.decidedAt(), ZoneOffset.UTC))
                .update();
        return inserted == 1;
    }

    @Override
    public Optional<Decision> findByDecider(UUID eventId, int roundNumber, AccountId decider) {
        return jdbcClient.sql("""
                        select event_id, round_number, account_id, partner_account_id, interested, decided_at
                          from round_decision
                         where event_id = :eventId and round_number = :roundNumber and account_id = :accountId
                        """)
                .param("eventId", eventId)
                .param("roundNumber", roundNumber)
                .param("accountId", decider.value())
                .query(JdbcDecisionRepository::toDecision)
                .optional();
    }

    private static Decision toDecision(ResultSet row, int rowNumber) throws SQLException {
        return new Decision(
                row.getObject("event_id", UUID.class),
                row.getInt("round_number"),
                new AccountId(row.getObject("account_id", UUID.class)),
                new AccountId(row.getObject("partner_account_id", UUID.class)),
                row.getBoolean("interested"),
                row.getObject("decided_at", OffsetDateTime.class).toInstant());
    }

}
