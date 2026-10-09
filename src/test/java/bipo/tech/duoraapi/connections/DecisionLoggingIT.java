package bipo.tech.duoraapi.connections;

import static bipo.tech.duoraapi.events.EventFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import bipo.tech.duoraapi.TestClockConfiguration;
import bipo.tech.duoraapi.TestClockConfiguration.TestClock;
import bipo.tech.duoraapi.TestcontainersConfiguration;
import bipo.tech.duoraapi.events.EventFixtures;
import bipo.tech.duoraapi.matching.RoundFixtures;

/**
 * A decisão é privada (docs/adr/0019): nem o par a lê, então ela também não vai para o log. Os loggers do
 * Spring MVC e da API ficam em DEBUG, o nível que alguém ligaria para investigar um incidente: é nele que o
 * MVC registra o corpo lido e o escrito. Um booleano não dá canário, então o log não pode ter o nome do campo.
 */
@SpringBootTest(properties = {
        "logging.level.org.springframework.web=DEBUG",
        "logging.level.bipo.tech=DEBUG"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestClockConfiguration.class})
@ExtendWith(OutputCaptureExtension.class)
class DecisionLoggingIT {

    private static final Instant STARTS_AT = Instant.parse(EventFixtures.STARTS_AT);
    private static final String DECISION_FIELD = "interested";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private TestClock clock;

    @BeforeEach
    void resetState() {
        clock.setTo(TestClockConfiguration.NOW);
        EventFixtures.cleanDatabase(jdbcClient);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void theDecisionNeverReachesTheLog(boolean interested, CapturedOutput output) throws Exception {
        String decisionPath = "/api/events/" + pairedInRoundOne() + "/rounds/1/decision";
        int logBeforeDeciding = output.getAll().length();

        mockMvc.perform(put(decisionPath).with(user("ana"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"interested\": " + interested + "}"))
                .andExpect(status().isCreated());
        mockMvc.perform(get(decisionPath).with(user("ana"))).andExpect(status().isOk());

        assertThat(output.getAll().substring(logBeforeDeciding)).doesNotContainIgnoringCase(DECISION_FIELD);
    }

    /** Ana e Bruno inscritos num evento publicado e pareados na rodada 1. */
    private String pairedInRoundOne() throws Exception {
        return RoundFixtures.pairedInRoundOne(mockMvc, jdbcClient, clock, "ana", "bruno");
    }

}
