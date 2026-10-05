package bipo.tech.duoraapi.waitlist.domain;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "waitlist_entry")
public class WaitlistEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

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
