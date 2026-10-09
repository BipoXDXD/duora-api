package bipo.tech.duoraapi.matching;

import static bipo.tech.duoraapi.events.EventFixtures.admin;
import static bipo.tech.duoraapi.events.EventFixtures.createPublishedEvent;
import static bipo.tech.duoraapi.events.EventFixtures.registerWithCompleteProfile;
import static bipo.tech.duoraapi.events.EventFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import bipo.tech.duoraapi.AccountFixtures;
import bipo.tech.duoraapi.TestClockConfiguration;
import bipo.tech.duoraapi.TestClockConfiguration.TestClock;
import bipo.tech.duoraapi.TestcontainersConfiguration;
import bipo.tech.duoraapi.events.EventFixtures;
import bipo.tech.duoraapi.identity.AccountId;

/** A API publicada do matching para outros módulos: o par de uma pessoa numa rodada (docs/adr/0019). */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestClockConfiguration.class})
class PairingsIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private TestClock clock;

    @Autowired
    private Pairings pairings;

    @BeforeEach
    void resetState() {
        clock.setTo(TestClockConfiguration.NOW);
        EventFixtures.cleanDatabase(jdbcClient);
    }

    @Test
    void aPairedPersonGetsTheirPartner() throws Exception {
        UUID eventId = roundOneWith("ana", "bruno");

        assertThat(pairings.partnerOf(eventId, 1, accountOf("ana"))).contains(accountOf("bruno"));
        assertThat(pairings.partnerOf(eventId, 1, accountOf("bruno"))).contains(accountOf("ana"));
    }

    @Test
    void whoSatOutHasNoPartner() throws Exception {
        UUID eventId = roundOneWith("ana", "bruno", "carla");
        AccountId satOut = new AccountId(jdbcClient.sql("""
                        select account_id from round_seat
                         where event_id = :eventId and round_number = 1 and partner_account_id is null
                        """)
                .param("eventId", eventId).query(UUID.class).single());

        assertThat(pairings.partnerOf(eventId, 1, satOut)).isEmpty();
    }

    @Test
    void someoneOutsideTheRoundHasNoPartner() throws Exception {
        registerWithCompleteProfile(mockMvc, user("carla"), createPublishedEvent(mockMvc));
        UUID eventId = roundOneWith("ana", "bruno");

        assertThat(pairings.partnerOf(eventId, 1, accountOf("carla"))).isEmpty();
    }

    @Test
    void aRoundThatDoesNotExistHasNoPartner() throws Exception {
        UUID eventId = roundOneWith("ana", "bruno");

        assertThat(pairings.partnerOf(eventId, 2, accountOf("ana"))).isEmpty();
        assertThat(pairings.partnerOf(UUID.randomUUID(), 1, accountOf("ana"))).isEmpty();
    }

    /** O chat da rodada fecha quando a seguinte começa (docs/adr/0021): quem pergunta é o módulo chat. */
    @Test
    void theLatestRoundIsTheLastOneStarted() throws Exception {
        UUID eventId = roundOneWith("ana", "bruno");
        assertThat(pairings.latestRoundOf(eventId)).hasValue(1);

        mockMvc.perform(put("/api/admin/events/" + eventId + "/rounds/2").with(admin()))
                .andExpect(status().isCreated());

        assertThat(pairings.latestRoundOf(eventId)).hasValue(2);
    }

    @Test
    void anEventWithoutRoundsHasNoLatestRound() throws Exception {
        UUID withoutRounds = UUID.fromString(createPublishedEvent(mockMvc));

        assertThat(pairings.latestRoundOf(withoutRounds)).isEmpty();
        assertThat(pairings.latestRoundOf(UUID.randomUUID())).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {Pairings.FIRST_ROUND - 1, Pairings.LAST_ROUND + 1})
    void aRoundNumberOutOfRangeIsAProgrammingError(int number) {
        assertThatThrownBy(() -> pairings.partnerOf(UUID.randomUUID(), number, new AccountId(UUID.randomUUID())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theRoundNumbersGoFromOneToOneHundred() {
        assertThat(Pairings.FIRST_ROUND).isEqualTo(1);
        assertThat(Pairings.LAST_ROUND).isEqualTo(100);
    }

    private UUID roundOneWith(String... names) throws Exception {
        clock.setTo(TestClockConfiguration.NOW);
        String eventId = createPublishedEvent(mockMvc);
        for (String name : names) {
            registerWithCompleteProfile(mockMvc, user(name), eventId);
        }
        clock.setTo(Instant.parse(EventFixtures.STARTS_AT));
        mockMvc.perform(put("/api/admin/events/" + eventId + "/rounds/1").with(admin()))
                .andExpect(status().isCreated());
        return UUID.fromString(eventId);
    }

    private AccountId accountOf(String name) {
        return AccountFixtures.accountOf(jdbcClient, name);
    }

}
