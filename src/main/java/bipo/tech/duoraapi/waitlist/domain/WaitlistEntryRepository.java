package bipo.tech.duoraapi.waitlist.domain;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

public interface WaitlistEntryRepository extends Repository<WaitlistEntry, Long> {

    /** Atômico e idempotente: a constraint UNIQUE decide, sem "consultar e depois inserir". */
    @Modifying
    @Query(value = """
            insert into waitlist_entry (email, joined_at)
            values (:email, :joinedAt)
            on conflict (email) do nothing
            """, nativeQuery = true)
    void insertIfAbsent(String email, Instant joinedAt);

    Optional<WaitlistEntry> findByEmail(String email);

    long count();

}
