package bipo.tech.duoraapi.waitlist.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import bipo.tech.duoraapi.TestcontainersConfiguration;

@DataJpaTest
@Import(TestcontainersConfiguration.class)
class WaitlistEntryRepositoryIT {

    private static final Instant FIRST_JOIN = Instant.parse("2026-10-05T12:00:00Z");
    private static final Instant SECOND_JOIN = Instant.parse("2026-10-06T08:30:00Z");

    @Autowired
    private WaitlistEntryRepository repository;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void insertIfAbsentStoresNewEntry() {
        repository.insertIfAbsent("ana@example.com", FIRST_JOIN);

        assertThat(repository.findByEmail("ana@example.com"))
                .hasValueSatisfying(entry -> {
                    assertThat(entry.email()).isEqualTo(new EmailAddress("ana@example.com"));
                    assertThat(entry.joinedAt()).isEqualTo(FIRST_JOIN);
                });
    }

    @Test
    void insertIfAbsentKeepsOriginalEntryOnRepeatedEmail() {
        repository.insertIfAbsent("ana@example.com", FIRST_JOIN);

        repository.insertIfAbsent("ana@example.com", SECOND_JOIN);

        assertThat(repository.count()).isEqualTo(1);
        assertThat(repository.findByEmail("ana@example.com"))
                .hasValueSatisfying(entry -> assertThat(entry.joinedAt()).isEqualTo(FIRST_JOIN));
    }

    @Test
    void insertIfAbsentAssignsTimeOrderedUuidId() {
        repository.insertIfAbsent("ana@example.com", FIRST_JOIN);

        var idVersion = jdbcClient.sql("select uuid_extract_version(id) from waitlist_entry where email = ?")
                .param("ana@example.com")
                .query(Integer.class)
                .single();
        assertThat(idVersion).isEqualTo(7);
    }

}
