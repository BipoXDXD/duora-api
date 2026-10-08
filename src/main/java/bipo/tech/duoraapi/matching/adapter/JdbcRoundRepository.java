package bipo.tech.duoraapi.matching.adapter;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.postgresql.util.PSQLException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import bipo.tech.duoraapi.identity.AccountId;
import bipo.tech.duoraapi.matching.domain.Pair;
import bipo.tech.duoraapi.matching.domain.PairingHistory;
import bipo.tech.duoraapi.matching.domain.Round;
import bipo.tech.duoraapi.matching.domain.RoundNumber;
import bipo.tech.duoraapi.matching.domain.RoundOutOfSequenceException;
import bipo.tech.duoraapi.matching.domain.RoundPairing;
import bipo.tech.duoraapi.matching.domain.RoundRepository;
import bipo.tech.duoraapi.matching.domain.RoundSummary;
import bipo.tech.duoraapi.matching.domain.Seat;

/** Rodadas e assentos nas tabelas round e round_seat, por SQL (docs/adr/0017). */
@Repository
class JdbcRoundRepository implements RoundRepository {

    /**
     * Quanto a criação de uma rodada espera por outra transação que cria a mesma. O sorteio de 200 pessoas
     * leva bem menos que isso; passar do teto indica algo preso, e a resposta é 503.
     */
    static final String LOCK_TIMEOUT = "5s";

    /** A FK que liga a rodada N à N-1, na migration V10. */
    private static final String PREVIOUS_ROUND_CONSTRAINT = "round_previous_fk";

    /** lock_not_available, na tabela de códigos de erro do PostgreSQL: o lock_timeout estourou. */
    private static final String LOCK_NOT_AVAILABLE = "55P03";

    private final JdbcClient jdbcClient;

    JdbcRoundRepository(JdbcClient jdbcClient) {
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
     * {@code on conflict do nothing} na chave primária: com a mesma rodada sendo criada por outra transação,
     * o insert espera ela terminar e, se ela confirmar, não faz nada.
     */
    @Override
    public boolean addIfAbsent(Round round) {
        try {
            int inserted = jdbcClient.sql("""
                            insert into round (event_id, number, previous_number, seed, started_at)
                            values (:eventId, :number, :previousNumber, :seed, :startedAt)
                            on conflict (event_id, number) do nothing
                            """)
                    .param("eventId", round.eventId())
                    .param("number", round.number().value())
                    .param("previousNumber", round.number().previous().map(RoundNumber::value).orElse(null),
                            Types.INTEGER)
                    .param("seed", round.seed())
                    .param("startedAt", OffsetDateTime.ofInstant(round.startedAt(), ZoneOffset.UTC))
                    .update();
            return inserted == 1;
        } catch (DataIntegrityViolationException e) {
            if (violates(e, PREVIOUS_ROUND_CONSTRAINT)) {
                throw new RoundOutOfSequenceException();
            }
            throw e;
        } catch (UncategorizedSQLException e) {
            // O JdbcClient não traduz o lock_timeout (55P03); o resto da aplicação o trata como lock não obtido.
            if (LOCK_NOT_AVAILABLE.equals(e.getSQLException().getSQLState())) {
                throw new CannotAcquireLockException("timed out waiting for the same round to be started", e);
            }
            throw e;
        }
    }

    @Override
    public Optional<RoundSummary> findSummary(UUID eventId, RoundNumber number) {
        return jdbcClient.sql("""
                        select r.event_id, r.number, r.seed, r.started_at,
                               count(s.partner_account_id) / 2 as pair_count,
                               count(s.account_id) filter (where s.partner_account_id is null) as sitting_out_count
                          from round r
                          left join round_seat s on s.event_id = r.event_id and s.round_number = r.number
                         where r.event_id = :eventId and r.number = :number
                         group by r.event_id, r.number
                        """)
                .param("eventId", eventId)
                .param("number", number.value())
                .query(JdbcRoundRepository::toSummary)
                .optional();
    }

    /** Pela chave primária (event_id, number), de trás para frente: lê uma entrada do índice. */
    @Override
    public Optional<RoundNumber> findLatestNumber(UUID eventId) {
        return jdbcClient.sql("""
                        select number from round
                         where event_id = :eventId
                         order by number desc
                         limit 1
                        """)
                .param("eventId", eventId)
                .query(Integer.class)
                .optional()
                .map(RoundNumber::new);
    }

    @Override
    public PairingHistory historyOf(UUID eventId) {
        Set<Pair> pairsFormed = new HashSet<>();
        Map<AccountId, Integer> roundsSatOut = new HashMap<>();
        jdbcClient.sql("""
                        select account_id, partner_account_id
                          from round_seat
                         where event_id = :eventId
                        """)
                .param("eventId", eventId)
                .query((ResultSet row) -> {
                    var account = new AccountId(row.getObject("account_id", UUID.class));
                    UUID partner = row.getObject("partner_account_id", UUID.class);
                    if (partner == null) {
                        roundsSatOut.merge(account, 1, Integer::sum);
                    } else {
                        pairsFormed.add(Pair.of(account, new AccountId(partner)));
                    }
                });
        return new PairingHistory(pairsFormed, roundsSatOut);
    }

    /** Um insert só, com os assentos em dois arrays paralelos: até 200 pessoas sem 200 idas ao banco. */
    @Override
    public void addSeats(UUID eventId, RoundNumber number, RoundPairing pairing) {
        List<UUID> accounts = new ArrayList<>();
        List<UUID> partners = new ArrayList<>();
        for (Pair pair : pairing.pairs()) {
            accounts.add(pair.first().value());
            partners.add(pair.second().value());
            accounts.add(pair.second().value());
            partners.add(pair.first().value());
        }
        for (AccountId account : pairing.sittingOut()) {
            accounts.add(account.value());
            partners.add(null);
        }
        if (accounts.isEmpty()) {
            return;
        }
        jdbcClient.sql("""
                        insert into round_seat (event_id, round_number, account_id, partner_account_id)
                        select :eventId, :number, seat.account_id, seat.partner_account_id
                          from unnest(:accounts, :partners) as seat (account_id, partner_account_id)
                        """)
                .param("eventId", eventId)
                .param("number", number.value())
                .param("accounts", accounts.toArray(UUID[]::new))
                .param("partners", partners.toArray(UUID[]::new))
                .update();
    }

    @Override
    public Optional<Seat> findSeat(UUID eventId, RoundNumber number, AccountId account) {
        return jdbcClient.sql("""
                        select partner_account_id
                          from round_seat
                         where event_id = :eventId and round_number = :number and account_id = :accountId
                        """)
                .param("eventId", eventId)
                .param("number", number.value())
                .param("accountId", account.value())
                .query((row, rowNumber) -> toSeat(row.getObject("partner_account_id", UUID.class)))
                .optional();
    }

    private static Seat toSeat(UUID partner) {
        return partner == null ? new Seat.SittingOut() : new Seat.Paired(new AccountId(partner));
    }

    private static RoundSummary toSummary(ResultSet row, int rowNumber) throws SQLException {
        var round = new Round(row.getObject("event_id", UUID.class), new RoundNumber(row.getInt("number")),
                row.getLong("seed"), row.getObject("started_at", OffsetDateTime.class).toInstant());
        return new RoundSummary(round, row.getInt("pair_count"), row.getInt("sitting_out_count"));
    }

    /** O nome da constraint vem do campo próprio do erro do PostgreSQL, não do texto da mensagem. */
    private static boolean violates(DataIntegrityViolationException exception, String constraint) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof PSQLException postgres && postgres.getServerErrorMessage() != null) {
                return constraint.equals(postgres.getServerErrorMessage().getConstraint());
            }
        }
        return false;
    }

}
