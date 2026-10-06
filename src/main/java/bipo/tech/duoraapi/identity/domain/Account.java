package bipo.tech.duoraapi.identity.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A conta interna, ligada a uma identidade externa. Só leitura pelo JPA: nasce em
 * {@link AccountRepository#insertIfAbsent}, com o id do default do banco.
 */
@Entity
@Table(name = "account")
public class Account {

    @Id
    private UUID id;

    private String issuer;

    private String subject;

    private Instant createdAt;

    protected Account() {
        // exigido pelo JPA
    }

}
