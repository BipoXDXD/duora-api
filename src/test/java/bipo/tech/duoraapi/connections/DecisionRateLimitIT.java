package bipo.tech.duoraapi.connections;

import static bipo.tech.duoraapi.events.EventFixtures.admin;
import static bipo.tech.duoraapi.events.EventFixtures.createPublishedEvent;
import static bipo.tech.duoraapi.events.EventFixtures.registerWithCompleteProfile;
import static bipo.tech.duoraapi.events.EventFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import bipo.tech.duoraapi.TestClockConfiguration;
import bipo.tech.duoraapi.TestClockConfiguration.TestClock;
import bipo.tech.duoraapi.TestcontainersConfiguration;
import bipo.tech.duoraapi.events.EventFixtures;

/**
 * Limite por conta da decisão (docs/adr/0019, "Rate limit"): cada PUT abre uma transação e disputa o advisory
 * lock do par, mesmo quando só devolve a decisão que já existe. A capacidade é reduzida a 3 por hora para
 * chegar logo ao fim; o valor de produção (120 por hora) vem de application.properties.
 */
@SpringBootTest(properties = {
        "duora.connections.decision-rate-limit.capacity=" + DecisionRateLimitIT.CAPACITY,
        "duora.connections.decision-rate-limit.period=PT1H"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestClockConfiguration.class})
class DecisionRateLimitIT {

    static final int CAPACITY = 3;

    /** Uma ficha de volta a cada 1 h / 3 = 1200 s. */
    private static final String SECONDS_TO_NEXT_CALL = "1200";
    private static final String YES = "{\"interested\": true}";
    private static final String NO = "{\"interested\": false}";

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

    /** A chamada recusada não chega nem a comparar a escolha: a contrária seria 409 se tivesse passado. */
    @Test
    void callsAboveTheLimitAreRejectedWithRetryAfterAndRecordNothing() throws Exception {
        String eventId = pairedInRoundOne("ana", "bruno");
        decide(eventId, "ana", YES).andExpect(status().isCreated());
        decide(eventId, "ana", YES).andExpect(status().isOk());
        decide(eventId, "ana", YES).andExpect(status().isOk());

        decide(eventId, "ana", NO)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, SECONDS_TO_NEXT_CALL))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().json("""
                        {"title": "Too Many Requests", "status": 429,
                         "detail": "rate limit exceeded; try again later", "instance": "%s"}
                        """.formatted(decisionPath(eventId, 1)), JsonCompareMode.STRICT));

        assertThat(decisionRows(eventId)).isEqualTo(1);
        assertThat(interestedOf(eventId, "ana")).isTrue();
    }

    /** O custo é o lock e a transação, e não o efeito: a repetição idempotente também gasta. */
    @Test
    void idempotentRepeatsSpendTheLimit() throws Exception {
        String eventId = pairedInRoundOne("ana", "bruno");
        decide(eventId, "ana", YES).andExpect(status().isCreated());
        decide(eventId, "ana", YES).andExpect(status().isOk());
        decide(eventId, "ana", YES).andExpect(status().isOk());

        decide(eventId, "ana", YES).andExpect(status().isTooManyRequests());
    }

    /** Quem não formou par também gasta: limita quem tenta adivinhar ids de evento e números de rodada. */
    @Test
    void callsFromWhoFormedNoPairSpendTheLimit() throws Exception {
        String eventId = pairedInRoundOne("ana", "bruno");
        createAccount("carla");
        for (int i = 0; i < CAPACITY; i++) {
            decide(eventId, "carla", YES).andExpect(status().isNotFound());
        }

        decide(eventId, "carla", YES).andExpect(status().isTooManyRequests());
    }

    @Test
    void theLimitIsCountedForEachAccountSeparately() throws Exception {
        String eventId = pairedInRoundOne("ana", "bruno");
        decide(eventId, "ana", YES).andExpect(status().isCreated());
        decide(eventId, "ana", YES).andExpect(status().isOk());
        decide(eventId, "ana", YES).andExpect(status().isOk());
        decide(eventId, "ana", YES).andExpect(status().isTooManyRequests());

        decide(eventId, "bruno", YES).andExpect(status().isCreated());
    }

    /** O número inválido e o corpo inválido são recusados antes de tocar o banco: não gastam nem abrem bucket. */
    @Test
    void callsRefusedBeforeTheDatabaseDoNotSpendTheLimit() throws Exception {
        String eventId = pairedInRoundOne("ana", "bruno");
        for (int i = 0; i <= CAPACITY; i++) {
            mockMvc.perform(put(decisionPath(eventId, 101)).with(user("ana"))
                            .contentType(MediaType.APPLICATION_JSON).content(YES))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(put(decisionPath(eventId, 1)).with(user("ana"))
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isBadRequest());
        }

        assertThat(keysOfTheLimit()).isEmpty();
        decide(eventId, "ana", YES).andExpect(status().isCreated());
    }

    /** Ler a própria decisão é uma consulta pela chave primária: não gasta o limite. */
    @Test
    void readingTheDecisionDoesNotSpendTheLimit() throws Exception {
        String eventId = pairedInRoundOne("ana", "bruno");
        decide(eventId, "ana", YES).andExpect(status().isCreated());
        for (int i = 0; i < CAPACITY * 2; i++) {
            mockMvc.perform(get(decisionPath(eventId, 1)).with(user("ana"))).andExpect(status().isOk());
        }

        decide(eventId, "ana", YES).andExpect(status().isOk());
    }

    @Test
    void theBucketLivesUnderTheDecisionKeyOfTheAccount() throws Exception {
        String eventId = pairedInRoundOne("ana", "bruno");
        decide(eventId, "ana", YES).andExpect(status().isCreated());

        String accountId = jdbcClient.sql("select id::text from account where subject = 'oid-ana'")
                .query(String.class).single();

        assertThat(keysOfTheLimit()).containsExactly("decision:" + accountId);
    }

    /** Falha fechada (docs/adr/0006): sem contar o limite, a decisão é recusada, e nada é gravado. */
    @Test
    void decisionIsRefusedWithoutWritingWhenTheLimitCannotBeCounted() throws Exception {
        String eventId = pairedInRoundOne("ana", "bruno");
        jdbcClient.sql("alter table rate_limit_bucket rename to rate_limit_bucket_unavailable").update();
        try {
            decide(eventId, "ana", YES)
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"))
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(content().json("""
                            {"title": "Service Unavailable", "status": 503, "instance": "%s"}
                            """.formatted(decisionPath(eventId, 1)), JsonCompareMode.STRICT));
        } finally {
            jdbcClient.sql("alter table rate_limit_bucket_unavailable rename to rate_limit_bucket").update();
        }

        assertThat(decisionRows(eventId)).isZero();
    }

    /** Publica um evento, inscreve as pessoas com o perfil completo, leva o relógio ao início e sorteia a rodada 1. */
    private String pairedInRoundOne(String... names) throws Exception {
        String eventId = createPublishedEvent(mockMvc);
        for (String name : names) {
            registerWithCompleteProfile(mockMvc, user(name), eventId);
        }
        clock.setTo(Instant.parse(EventFixtures.STARTS_AT));
        mockMvc.perform(put("/api/admin/events/" + eventId + "/rounds/1").with(admin()))
                .andExpect(status().isCreated());
        return eventId;
    }

    private ResultActions decide(String eventId, String name, String body) throws Exception {
        return mockMvc.perform(put(decisionPath(eventId, 1)).with(user(name))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** O primeiro acesso cria a conta. */
    private void createAccount(String name) throws Exception {
        mockMvc.perform(get("/api/me").with(user(name))).andExpect(status().isOk());
    }

    private static String decisionPath(String eventId, int number) {
        return "/api/events/" + eventId + "/rounds/" + number + "/decision";
    }

    private long decisionRows(String eventId) {
        return jdbcClient.sql("select count(*) from round_decision where event_id = cast(:id as uuid)")
                .param("id", eventId).query(Long.class).single();
    }

    private boolean interestedOf(String eventId, String name) {
        return jdbcClient.sql("""
                        select d.interested from round_decision d join account a on a.id = d.account_id
                         where d.event_id = cast(:id as uuid) and a.subject = :subject
                        """)
                .param("id", eventId).param("subject", "oid-" + name)
                .query(Boolean.class).single();
    }

    private List<String> keysOfTheLimit() {
        return jdbcClient.sql("select id from rate_limit_bucket where id like 'decision:%'")
                .query(String.class).list();
    }

}
