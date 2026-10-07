package bipo.tech.duoraapi.events;

import static bipo.tech.duoraapi.events.EventFixtures.admin;
import static bipo.tech.duoraapi.events.EventFixtures.adminEventPath;
import static bipo.tech.duoraapi.events.EventFixtures.createDraft;
import static bipo.tech.duoraapi.events.EventFixtures.createPublishedEvent;
import static bipo.tech.duoraapi.events.EventFixtures.eventJson;
import static bipo.tech.duoraapi.events.EventFixtures.registerWithCompleteProfile;
import static bipo.tech.duoraapi.events.EventFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import bipo.tech.duoraapi.TestClockConfiguration;
import bipo.tech.duoraapi.TestClockConfiguration.TestClock;
import bipo.tech.duoraapi.TestcontainersConfiguration;
import bipo.tech.duoraapi.identity.AccountId;

/** A API publicada que entrega ao pareamento os inscritos de um evento em andamento (docs/adr/0017). */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestClockConfiguration.class})
class EventRosterIT {

    private static final Instant STARTS_AT = Instant.parse(EventFixtures.STARTS_AT);
    private static final Instant ENDS_AT = Instant.parse(EventFixtures.ENDS_AT);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private TestClock clock;

    @Autowired
    private EventRoster roster;

    @BeforeEach
    void resetState() {
        clock.setTo(TestClockConfiguration.NOW);
        EventFixtures.cleanDatabase(jdbcClient);
    }

    @Test
    void underwayEventListsWhoRegisteredInIdOrder() throws Exception {
        String eventId = createPublishedEvent(mockMvc);
        registerWithCompleteProfile(mockMvc, user("ana"), eventId);
        registerWithCompleteProfile(mockMvc, user("bruno"), eventId);

        Roster found = roster.rosterOf(UUID.fromString(eventId), STARTS_AT);

        assertThat(found).isEqualTo(new Roster.Underway(
                Stream.of(accountOf("ana"), accountOf("bruno"))
                        .sorted(Comparator.comparing(account -> account.value().toString()))
                        .toList()));
    }

    @Test
    void registrantsOfAnotherEventAreNotListed() throws Exception {
        String eventId = createPublishedEvent(mockMvc);
        String otherEventId = createPublishedEvent(mockMvc);
        registerWithCompleteProfile(mockMvc, user("ana"), eventId);
        registerWithCompleteProfile(mockMvc, user("bruno"), otherEventId);

        Roster found = roster.rosterOf(UUID.fromString(eventId), STARTS_AT);

        assertThat(found).isEqualTo(new Roster.Underway(List.of(accountOf("ana"))));
    }

    @Test
    void underwayEventWithoutRegistrantsHasAnEmptyList() throws Exception {
        String eventId = createPublishedEvent(mockMvc);

        assertThat(roster.rosterOf(UUID.fromString(eventId), STARTS_AT))
                .isEqualTo(new Roster.Underway(List.of()));
    }

    @Test
    void eventThatHasNotStartedIsNotUnderway() throws Exception {
        String eventId = createPublishedEvent(mockMvc);

        assertThat(roster.rosterOf(UUID.fromString(eventId), STARTS_AT.minusNanos(1000)))
                .isEqualTo(new Roster.NotUnderway());
    }

    @Test
    void eventThatHasEndedIsNotUnderway() throws Exception {
        String eventId = createPublishedEvent(mockMvc);

        assertThat(roster.rosterOf(UUID.fromString(eventId), ENDS_AT)).isEqualTo(new Roster.NotUnderway());
    }

    @Test
    void cancelledEventIsNotUnderway() throws Exception {
        String eventId = createPublishedEvent(mockMvc);
        mockMvc.perform(post(adminEventPath(eventId) + ":cancel").with(admin())).andExpect(status().isOk());

        assertThat(roster.rosterOf(UUID.fromString(eventId), STARTS_AT)).isEqualTo(new Roster.NotUnderway());
    }

    @Test
    void draftIsNotUnderway() throws Exception {
        String eventId = createDraft(mockMvc, eventJson());

        assertThat(roster.rosterOf(UUID.fromString(eventId), STARTS_AT)).isEqualTo(new Roster.NotUnderway());
    }

    @Test
    void unknownEventIsReportedAsSuch() {
        assertThat(roster.rosterOf(UUID.randomUUID(), STARTS_AT)).isEqualTo(new Roster.UnknownEvent());
    }

    private AccountId accountOf(String name) {
        return new AccountId(jdbcClient.sql("select id from account where subject = :subject")
                .param("subject", "oid-" + name)
                .query(UUID.class).single());
    }

}
