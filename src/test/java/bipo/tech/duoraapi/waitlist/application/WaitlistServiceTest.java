package bipo.tech.duoraapi.waitlist.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import bipo.tech.duoraapi.waitlist.domain.EmailAddress;
import bipo.tech.duoraapi.waitlist.domain.WaitlistEntryRepository;

@ExtendWith(MockitoExtension.class)
class WaitlistServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");

    @Mock
    private WaitlistEntryRepository repository;

    private WaitlistService service;

    @BeforeEach
    void setUp() {
        service = new WaitlistService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void joinRecordsNormalizedEmailWithCurrentTime() {
        service.join(new EmailAddress("Ana@Example.com"));

        then(repository).should().insertIfAbsent("ana@example.com", NOW);
    }

    @Test
    void countEntriesReturnsRepositoryCount() {
        given(repository.count()).willReturn(3L);

        assertThat(service.countEntries()).isEqualTo(3L);
    }

}
