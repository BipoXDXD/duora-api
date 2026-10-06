package bipo.tech.duoraapi.waitlist.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Só leitura pelo JPA: a inscrição nasce em {@link WaitlistEntryRepository#insertIfAbsent}, com o id do default do banco. */
@Entity
@Table(name = "waitlist_entry")
public class WaitlistEntry {

    @Id
    private UUID id;

    private String email;

    private Instant joinedAt;

    protected WaitlistEntry() {
        // exigido pelo JPA
    }

    public EmailAddress email() {
        return new EmailAddress(email);
    }

    public Instant joinedAt() {
        return joinedAt;
    }

}
