package bipo.tech.duoraapi.events.domain;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import bipo.tech.duoraapi.identity.AccountId;

/**
 * Inscrições em SQL direto: são pares (evento, conta) sem comportamento próprio, e a inscrição precisa
 * de "insert ... on conflict", que o JPA não tem. Roda na mesma transação do JPA, que expõe a conexão.
 */
@Repository
public class RegistrationRepository {

    /**
     * Quanto uma inscrição espera pelo lock do evento. As transações que o seguram são curtas; passar
     * disso indica algo preso, e a resposta é 503 em vez de prender a conexão até o timeout do pool.
     */
    static final String LOCK_TIMEOUT = "2s";

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
     * Idempotente: a chave primária (evento, conta) decide, sem "consultar e depois inserir". Com o
     * evento travado, o conflito não acontece; o "on conflict" fica como última defesa.
     *
     * @return se a inscrição foi gravada agora
     */
    public boolean insertIfAbsent(Registration registration) {
        return jdbcClient.sql("""
                        insert into registration (event_id, account_id, registered_at)
                        values (:eventId, :accountId, :registeredAt)
                        on conflict (event_id, account_id) do nothing
                        """)
                .param("eventId", registration.eventId())
                .param("accountId", registration.account().value())
                .param("registeredAt", OffsetDateTime.ofInstant(registration.registeredAt(), ZoneOffset.UTC))
                .update() == 1;
    }

    /** Apagar o que não existe também é sucesso: quem chama quer só que a inscrição não exista. */
    public void delete(UUID eventId, AccountId account) {
        jdbcClient.sql("delete from registration where event_id = :eventId and account_id = :accountId")
                .param("eventId", eventId)
                .param("accountId", account.value())
                .update();
    }

    private static Registration toRegistration(ResultSet row, int rowNumber) throws SQLException {
        return new Registration(row.getObject("event_id", UUID.class),
                new AccountId(row.getObject("account_id", UUID.class)),
                row.getObject("registered_at", OffsetDateTime.class).toInstant());
    }

}
