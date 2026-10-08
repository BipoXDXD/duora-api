package bipo.tech.duoraapi.events;

import static bipo.tech.duoraapi.events.EventFixtures.admin;
import static bipo.tech.duoraapi.events.EventFixtures.adminEventPath;
import static bipo.tech.duoraapi.events.EventFixtures.createDraft;
import static bipo.tech.duoraapi.events.EventFixtures.createPublishedEvent;
import static bipo.tech.duoraapi.events.EventFixtures.eventJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

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

/** A API publicada que diz ao chat se o evento está em andamento e quando ele termina (docs/adr/0021). */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestClockConfiguration.class})
class EventCalendarIT {

    private static final Instant STARTS_AT = Instant.parse(EventFixtures.STARTS_AT);
    private static final Instant ENDS_AT = Instant.parse(EventFixtures.ENDS_AT);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private TestClock clock;

    @Autowired
    private EventCalendar calendar;

    @BeforeEach
    void resetState() {
        clock.setTo(TestClockConfiguration.NOW);
        EventFixtures.cleanDatabase(jdbcClient);
    }

    @Test
    void aPublishedEventIsUnderwayFromTheStartUntilJustBeforeTheEnd() throws Exception {
        UUID eventId = UUID.fromString(createPublishedEvent(mockMvc));

        assertThat(calendar.periodOf(eventId, STARTS_AT.minusNanos(1000))).hasValue(new EventPeriod(ENDS_AT, false));
        assertThat(calendar.periodOf(eventId, STARTS_AT)).hasValue(new EventPeriod(ENDS_AT, true));
        assertThat(calendar.periodOf(eventId, ENDS_AT.minusNanos(1000))).hasValue(new EventPeriod(ENDS_AT, true));
        assertThat(calendar.periodOf(eventId, ENDS_AT)).hasValue(new EventPeriod(ENDS_AT, false));
    }

    @Test
    void aCancelledEventIsNeverUnderwayButKeepsItsScheduledEnd() throws Exception {
        String eventId = createPublishedEvent(mockMvc);
        mockMvc.perform(post(adminEventPath(eventId) + ":cancel").with(admin())).andExpect(status().isOk());

        assertThat(calendar.periodOf(UUID.fromString(eventId), STARTS_AT)).hasValue(new EventPeriod(ENDS_AT, false));
    }

    @Test
    void aDraftIsNeverUnderway() throws Exception {
        String eventId = createDraft(mockMvc, eventJson());

        assertThat(calendar.periodOf(UUID.fromString(eventId), STARTS_AT)).hasValue(new EventPeriod(ENDS_AT, false));
    }

    @Test
    void anUnknownEventHasNoPeriod() {
        assertThat(calendar.periodOf(UUID.randomUUID(), STARTS_AT)).isEmpty();
    }

}
