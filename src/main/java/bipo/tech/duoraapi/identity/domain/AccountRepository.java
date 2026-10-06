package bipo.tech.duoraapi.identity.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

public interface AccountRepository extends Repository<Account, UUID> {

    @Query("select account.id from Account account where account.issuer = :issuer and account.subject = :subject")
    Optional<UUID> findIdByExternalIdentity(String issuer, String subject);

    /**
     * Atômico e idempotente: a constraint UNIQUE decide, sem "consultar e depois inserir". Com outra
     * transação inserindo a mesma identidade, espera o commit dela e não faz nada.
     */
    @Modifying
    @Query(value = """
            insert into account (issuer, subject, created_at)
            values (:issuer, :subject, :createdAt)
            on conflict (issuer, subject) do nothing
            """, nativeQuery = true)
    void insertIfAbsent(String issuer, String subject, Instant createdAt);

}
