package bipo.tech.duoraapi.events.domain;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import bipo.tech.duoraapi.identity.AccountId;

/**
 * Inscrições em SQL direto: são pares (evento, conta) sem comportamento próprio, lidos quase sempre
 * junto com o evento. Roda na mesma transação do JPA, que expõe a conexão ao JDBC.
 */
@Repository
public class RegistrationRepository {

    /**
     * Quanto uma inscrição espera pelo lock do evento. As transações que o seguram são curtas; passar
     * disso indica algo preso, e a resposta é 503 em vez de prender a conexão até o timeout do pool.
     */
    static final String LOCK_TIMEOUT = "2s";

    /** Junta o evento à inscrição: o próprio módulo é dono das duas tabelas. */
    private static final String CURRENT_OF = """
            select r.event_id, e.title, e.starts_at, e.ends_at, e.status, r.registered_at
              from registration r
              join event e on e.id = r.event_id
             where r.account_id = :accountId
               and e.ends_at > :now
            """;
    private static final String AFTER_CURSOR = """
               and (e.starts_at, e.id) > (:afterStartsAt, :afterId)
            """;
    private static final String IN_START_ORDER = """
             order by e.starts_at, e.id
             limit :limit
            """;

    private final JdbcClient jdbcClient;

    RegistrationRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * {@code set local lock_timeout}: vale até o fim da transação atual. Chame antes de
     * {@link EventRepository#findByIdForUpdate}.
     */
    public void limitLockWait() {
        jdbcClient.sql("select set_config('lock_timeout', :timeout, true)")
                .param("timeout", LOCK_TIMEOUT)
                .query(String.class)
                .single();
    }

    public long countByEvent(UUID eventId) {
        return jdbcClient.sql("select count(*) from registration where event_id = :eventId")
                .param("eventId", eventId)
                .query(Long.class)
                .single();
    }

    /**
     * Quantas inscrições cada evento tem, numa consulta só. Evento sem inscrição não aparece no mapa, e uma lista
     * vazia nem chega ao banco.
     */
    public Map<UUID, Long> countByEvents(Collection<UUID> eventIds) {
        if (eventIds.isEmpty()) {
            return Map.of();
        }
        return jdbcClient.sql("select event_id, count(*) as total from registration where event_id in (:eventIds) group by event_id")
                .param("eventIds", eventIds)
                .query((row, rowNumber) -> Map.entry(row.getObject("event_id", UUID.class), row.getLong("total")))
                .list()
                .stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** Quem está inscrito no evento, na ordem dos ids. A PK (evento, conta) atende a consulta. */
    public List<AccountId> findAccountsByEvent(UUID eventId) {
        return jdbcClient.sql("select account_id from registration where event_id = :eventId order by account_id")
                .param("eventId", eventId)
                .query((row, rowNumber) -> new AccountId(row.getObject("account_id", UUID.class)))
                .list();
    }

    public Optional<Registration> find(UUID eventId, AccountId account) {
        return jdbcClient.sql("""
                        select event_id, account_id, registered_at
                          from registration
                         where event_id = :eventId and account_id = :accountId
                        """)
                .param("eventId", eventId)
                .param("accountId", account.value())
                .query(RegistrationRepository::toRegistration)
                .optional();
    }

    /**
     * Grava a inscrição. Chame com o evento travado, depois de conferir que ela não existe: com o lock,
     * duas inscrições do mesmo par não chegam aqui juntas. Se chegarem, a chave primária (evento, conta)
     * recusa a segunda, e a transação falha em vez de duplicar.
     */
    public void insert(Registration registration) {
        jdbcClient.sql("""
                        insert into registration (event_id, account_id, registered_at)
                        values (:eventId, :accountId, :registeredAt)
                        """)
                .param("eventId", registration.eventId())
                .param("accountId", registration.account().value())
                .param("registeredAt", utc(registration.registeredAt()))
                .update();
    }

    /** Apagar o que não existe também é sucesso: quem chama quer só que a inscrição não exista. */
    public void delete(UUID eventId, AccountId account) {
        jdbcClient.sql("delete from registration where event_id = :eventId and account_id = :accountId")
                .param("eventId", eventId)
                .param("accountId", account.value())
                .update();
    }

    /**
     * As inscrições da pessoa em eventos que ainda não acabaram (inclusive cancelados e em andamento),
     * por início do evento e, no empate, por id.
     */
    public List<RegisteredEvent> findCurrentOf(AccountId account, Instant now, int limit) {
        return currentOf(account, now, CURRENT_OF + IN_START_ORDER)
                .param("limit", limit)
                .query(RegistrationRepository::toRegisteredEvent)
                .list();
    }

    /** Como {@link #findCurrentOf}, a partir do par (afterStartsAt, afterId) exclusive: paginação por keyset. */
    public List<RegisteredEvent> findCurrentOfAfter(AccountId account, Instant now, Instant afterStartsAt,
            UUID afterId, int limit) {
        return currentOf(account, now, CURRENT_OF + AFTER_CURSOR + IN_START_ORDER)
                .param("afterStartsAt", utc(afterStartsAt))
                .param("afterId", afterId)
                .param("limit", limit)
                .query(RegistrationRepository::toRegisteredEvent)
                .list();
    }

    private JdbcClient.StatementSpec currentOf(AccountId account, Instant now, String sql) {
        return jdbcClient.sql(sql)
                .param("accountId", account.value())
                .param("now", utc(now));
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static RegisteredEvent toRegisteredEvent(ResultSet row, int rowNumber) throws SQLException {
        return new RegisteredEvent(row.getObject("event_id", UUID.class), row.getString("title"),
                row.getObject("starts_at", OffsetDateTime.class).toInstant(),
                row.getObject("ends_at", OffsetDateTime.class).toInstant(),
                EventStatus.valueOf(row.getString("status")),
                row.getObject("registered_at", OffsetDateTime.class).toInstant());
    }

    private static Registration toRegistration(ResultSet row, int rowNumber) throws SQLException {
        return new Registration(row.getObject("event_id", UUID.class),
                new AccountId(row.getObject("account_id", UUID.class)),
                row.getObject("registered_at", OffsetDateTime.class).toInstant());
    }

}
