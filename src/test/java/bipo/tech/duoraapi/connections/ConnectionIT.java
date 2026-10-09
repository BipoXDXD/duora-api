package bipo.tech.duoraapi.connections;

import static bipo.tech.duoraapi.AccountFixtures.accountIdOf;
import static bipo.tech.duoraapi.AccountFixtures.firstAccess;
import static bipo.tech.duoraapi.ConcurrentCalls.sameCallTogether;
import static bipo.tech.duoraapi.ConcurrentCalls.statusCodeOf;
import static bipo.tech.duoraapi.ConcurrentCalls.together;
import static bipo.tech.duoraapi.events.EventFixtures.createPublishedEvent;
import static bipo.tech.duoraapi.events.EventFixtures.registerWithCompleteProfile;
import static bipo.tech.duoraapi.events.EventFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

import com.jayway.jsonpath.JsonPath;

import bipo.tech.duoraapi.HeldLock;
import bipo.tech.duoraapi.TestClockConfiguration;
import bipo.tech.duoraapi.TestClockConfiguration.TestClock;
import bipo.tech.duoraapi.TestIdentities;
import bipo.tech.duoraapi.TestcontainersConfiguration;
import bipo.tech.duoraapi.events.EventFixtures;
import bipo.tech.duoraapi.matching.RoundFixtures;

/**
 * Decisão privada depois da rodada e conexão por interesse mútuo (docs/adr/0019): só decide quem formou o
 * par, a decisão é final, a resposta nunca revela a decisão do outro, dois "sim" simultâneos criam uma
 * conexão só e o bloqueio impede a conexão.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestClockConfiguration.class})
class ConnectionIT {

    private static final Instant STARTS_AT = Instant.parse(EventFixtures.STARTS_AT);
    private static final String DECIDED_AT = "2026-11-01T22:00:00Z";
    private static final String CONNECTIONS_PATH = "/api/me/connections";
    private static final String YES = "{\"interested\": true}";
    private static final String NO = "{\"interested\": false}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private TestClock clock;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void resetState() {
        clock.setTo(TestClockConfiguration.NOW);
        EventFixtures.cleanDatabase(jdbcClient);
    }

    @Test
    void aDecisionIsRecordedAndOnlyTellsAboutTheCaller() throws Exception {
        String eventId = pairedInRoundOne("ana", "bruno");

        decide(eventId, "ana", YES)
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, decisionPath(eventId, 1)))
                .andExpect(content().json("""
                        {"eventId": "%s", "roundNumber": 1, "interested": true, "decidedAt": "%s"}
                        """.formatted(eventId, DECIDED_AT), JsonCompareMode.STRICT));

        mockMvc.perform(get(decisionPath(eventId, 1)).with(user("ana")))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"eventId": "%s", "roundNumber": 1, "interested": true, "decidedAt": "%s"}
                        """.formatted(eventId, DECIDED_AT), JsonCompareMode.STRICT));
        assertThat(decisionRows(eventId)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {YES, NO})
    void repeatingTheSameChoiceAnswersTheSameDecision(String choice) throws Exception {
        String eventId = pairedInRoundOne("ana", "bruno");
        decide(eventId, "ana", choice).andExpect(status().isCreated());
        clock.advance(Duration.ofMinutes(5));

        decide(eventId, "ana", choice)
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andExpect(jsonPath("$.decidedAt").value(DECIDED_AT));

        assertThat(decisionRows(eventId)).isEqualTo(1);
    }

    @Test
    void aDecisionCannotChange() throws Exception {
        String eventId = pairedInRoundOne("ana", "bruno");
        decide(eventId, "ana", NO).andExpect(status().isCreated());
        decide(eventId, "bruno", YES).andExpect(status().isCreated());

        decide(eventId, "ana", YES)
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.reason").value("DECISION_ALREADY_MADE"));

        assertThat(jdbcClient.sql("""
                        select interested from round_decision
                         where event_id = cast(:id as uuid) and account_id = cast(:account as uuid)
                        """)
                .param("id", eventId).param("account", accountOf("ana"))
                .query(Boolean.class).single()).isFalse();
        assertThat(connectionRows()).isZero();
    }

    @Test
    void twoYesesFormOneConnectionThatBothSee() throws Exception {
        String eventId = pairedInRoundOne("ana", "bruno");
        decide(eventId, "ana", YES).andExpect(status().isCreated());
        clock.advance(Duration.ofMinutes(2));

        decide(eventId, "bruno", YES).andExpect(status().isCreated());

        assertThat(connectionRows()).isEqualTo(1);
        mockMvc.perform(get(CONNECTIONS_PATH).with(user("ana")))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"items": [{"accountId": "%s", "connectedAt": "2026-11-01T22:02:00Z"}], "nextPageToken": null}
                        """.formatted(accountOf("bruno")), JsonCompareMode.STRICT));
        mockMvc.perform(get(CONNECTIONS_PATH).with(user("bruno")))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"items": [{"accountId": "%s", "connectedAt": "2026-11-01T22:02:00Z"}], "nextPageToken": null}
                        """.formatted(accountOf("ana")), JsonCompareMode.STRICT));
    }

    @ParameterizedTest
    @ValueSource(strings = {YES, NO})
    void aNoOnEitherSideFormsNothing(String firstChoice) throws Exception {
        String eventId = pairedInRoundOne("ana", "bruno");
        decide(eventId, "ana", firstChoice).andExpect(status().isCreated());

        decide(eventId, "bruno", firstChoice.equals(YES) ? NO : YES).andExpect(status().isCreated());

        assertThat(connectionRows()).isZero();
        assertThat(connectionsBodyOf("ana")).isEqualTo(connectionsBodyOf("bruno")).contains("\"items\":[]");
    }

    /**
     * O caso central da privacidade: o "sim" de quem decide depois recebe exatamente a mesma resposta (status,
     * headers relevantes e corpo) com o par tendo dito não e com o par ainda sem decidir.
     */
    @Test
    void theAnswerIsTheSameWhetherThePartnerSaidNoOrHasNotDecided() throws Exception {
        String saidNo = pairedInRoundOne("ana", "bruno");
        decide(saidNo, "bruno", NO).andExpect(status().isCreated());
        MvcResult afterNo = decide(saidNo, "ana", YES).andReturn();
        String getAfterNo = mockMvc.perform(get(decisionPath(saidNo, 1)).with(user("ana")))
                .andReturn().getResponse().getContentAsString();

        String undecided = pairedInRoundOne("carla", "davi");
        MvcResult beforeAnything = decide(undecided, "carla", YES).andReturn();
        String getUndecided = mockMvc.perform(get(decisionPath(undecided, 1)).with(user("carla")))
                .andReturn().getResponse().getContentAsString();

        assertThat(afterNo.getResponse().getStatus()).isEqualTo(beforeAnything.getResponse().getStatus())
                .isEqualTo(201);
        assertThat(withoutEventId(afterNo.getResponse().getContentAsString(), saidNo))
                .isEqualTo(withoutEventId(beforeAnything.getResponse().getContentAsString(), undecided));
        assertThat(withoutEventId(afterNo.getResponse().getHeader(HttpHeaders.LOCATION), saidNo))
                .isEqualTo(withoutEventId(beforeAnything.getResponse().getHeader(HttpHeaders.LOCATION), undecided));
        assertThat(withoutEventId(getAfterNo, saidNo)).isEqualTo(withoutEventId(getUndecided, undecided));
        assertThat(connectionsBodyOf("ana")).isEqualTo(connectionsBodyOf("carla"));
    }

    /** Ninguém lê a decisão do par: a rota só alcança a de quem chama. */
    @Test
    void thePartnerCannotReadTheDecision() throws Exception {
        String eventId = pairedInRoundOne("ana", "bruno");
        decide(eventId, "ana", YES).andExpect(status().isCreated());

        String body = mockMvc.perform(get(decisionPath(eventId, 1)).with(user("bruno")))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("interested", accountOf("ana"));
    }

    /** Quem não formou o par recebe 404 sem nada do par, e a decisão já gravada não muda. */
    @Test
    void anOutsiderLeavesTheDecisionOfThePairIntact() throws Exception {
        String otherEvent = createPublishedEvent(mockMvc);
        registerWithCompleteProfile(mockMvc, user("davi"), otherEvent);
        String eventId = pairedInRoundOne("ana", "bruno");
        decide(eventId, "ana", YES).andExpect(status().isCreated());

        String outsider = decide(eventId, "davi", NO).andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        assertThat(outsider).doesNotContain("interested", accountOf("ana"), accountOf("bruno"));
        mockMvc.perform(get(decisionPath(eventId, 1)).with(user("ana")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.interested").value(true));
        assertThat(decisionRows(eventId)).isEqualTo(1);
    }

    /** Dois cliques em "sim" ao mesmo tempo, um de cada lado: sempre uma conexão, nunca zero nem duas. */
    @RepeatedTest(5)
    void simultaneousYesesCreateExactlyOneConnection() throws Exception {
        String eventId = pairedInRoundOne("ana", "bruno");

        var statuses = together(List.of(
                statusCodeOf(() -> decide(eventId, "ana", YES)),
                statusCodeOf(() -> decide(eventId, "bruno", YES))));

        assertThat(statuses).containsExactly(201, 201);
        assertThat(connectionRows()).isEqualTo(1);
    }

    /** A mesma pessoa em duas abas: uma decisão só, e as duas respostas são de sucesso. */
    @RepeatedTest(3)
    void simultaneousRepeatsOfTheSameDecisionRecordItOnce() throws Exception {
        String eventId = pairedInRoundOne("ana", "bruno");

        var statuses = sameCallTogether(3, statusCodeOf(() -> decide(eventId, "ana", YES)));

        assertThat(statuses).containsOnly(201, 200).containsOnlyOnce(201);
        assertThat(decisionRows(eventId)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ana", "bruno"})
    void aBlockEitherWayPreventsTheConnection(String blocker) throws Exception {
        String eventId = pairedInRoundOne("ana", "bruno");
        String blocked = blocker.equals("ana") ? "bruno" : "ana";
        mockMvc.perform(post("/api/accounts/{id}:block", accountOf(blocked)).with(user(blocker)))
                .andExpect(status().isNoContent());

        decide(eventId, "ana", YES).andExpect(status().isCreated());
        decide(eventId, "bruno", YES).andExpect(status().isCreated());

        assertThat(connectionRows()).isZero();
    }

    @Test
    void anAlreadyConnectedPairKeepsTheFirstConnection() throws Exception {
        String first = pairedInRoundOne("ana", "bruno");
        decide(first, "ana", YES).andExpect(status().isCreated());
        decide(first, "bruno", YES).andExpect(status().isCreated());

        String second = pairedInRoundOne("ana", "bruno");
        clock.advance(Duration.ofDays(1));
        decide(second, "ana", YES).andExpect(status().isCreated());
        decide(second, "bruno", YES).andExpect(status().isCreated());

        assertThat(connectionRows()).isEqualTo(1);
        mockMvc.perform(get(CONNECTIONS_PATH).with(user("ana")))
                .andExpect(jsonPath("$.items[0].connectedAt").value(DECIDED_AT));
    }

    @Test
    void someoneSittingOutOfTheRoundIsToldTheyHaveNoPartner() throws Exception {
        String eventId = pairedInRoundOne("ana", "bruno", "carla");
        String satOut = sittingOutIn(eventId);

        expectNoPartnerAndNothingWritten(decide(eventId, satOut, YES), eventId);
    }

    @Test
    void someoneNotRegisteredInTheEventIsToldTheyHaveNoPartner() throws Exception {
        String otherEvent = createPublishedEvent(mockMvc);
        registerWithCompleteProfile(mockMvc, user("davi"), otherEvent);
        String eventId = pairedInRoundOne("ana", "bruno", "carla");

        expectNoPartnerAndNothingWritten(decide(eventId, "davi", YES), eventId);
    }

    @Test
    void aRoundThatDoesNotExistIsToldTheCallerHasNoPartner() throws Exception {
        String eventId = pairedInRoundOne("ana", "bruno", "carla");

        expectNoPartnerAndNothingWritten(mockMvc.perform(put(decisionPath(eventId, 2)).with(user("ana"))
                .contentType(MediaType.APPLICATION_JSON).content(YES)), eventId);
    }

    @Test
    void anUnknownEventIsToldTheCallerHasNoPartner() throws Exception {
        String eventId = pairedInRoundOne("ana", "bruno", "carla");

        expectNoPartnerAndNothingWritten(decide(UUID.randomUUID().toString(), "ana", YES), eventId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "101", "-1", "abc", "1.5", "99999999999"})
    void anInvalidRoundNumberIsABadRequestAndWritesNothing(String number) throws Exception {
        String eventId = pairedInRoundOne("ana", "bruno");
        String path = "/api/events/" + eventId + "/rounds/" + number + "/decision";

        mockMvc.perform(put(path).with(user("ana")).contentType(MediaType.APPLICATION_JSON).content(YES))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
        mockMvc.perform(get(path).with(user("ana"))).andExpect(status().isBadRequest());

        assertThat(decisionRows(eventId)).isZero();
    }

    @Test
    void anEventIdThatIsNotAUuidIsABadRequest() throws Exception {
        mockMvc.perform(put("/api/events/not-a-uuid/rounds/1/decision").with(user("ana"))
                        .contentType(MediaType.APPLICATION_JSON).content(YES))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aWebSessionWithoutCsrfTokenCannotDecide() throws Exception {
        String eventId = pairedInRoundOne("ana", "bruno");

        mockMvc.perform(put(decisionPath(eventId, 1)).with(webSession("ana"))
                        .contentType(MediaType.APPLICATION_JSON).content(YES))
                .andExpect(status().isForbidden());

        assertThat(decisionRows(eventId)).isZero();
    }

    @Test
    void aWebSessionWithCsrfTokenDecides() throws Exception {
        String eventId = pairedInRoundOne("ana", "bruno");

        mockMvc.perform(put(decisionPath(eventId, 1)).with(webSession("ana")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(YES))
                .andExpect(status().isCreated());
    }

    @Test
    void aDecisionIsRefusedWhenThePartnersTakesTooLong() throws Exception {
        String eventId = pairedInRoundOne("ana", "bruno");

        try (var _ = HeldLock.hold(transactionTemplate, () -> jdbcClient
                .sql("select 1 from (select pg_advisory_xact_lock(hashtextextended(:key, 0))) as locked")
                .param("key", lockKeyOf(eventId, "ana", "bruno"))
                .query(Integer.class).single())) {
            decide(eventId, "ana", YES)
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"))
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
        }

        assertThat(decisionRows(eventId)).isZero();
    }

    @Test
    void connectionsArePagedFromTheMostRecent() throws Exception {
        connectDirectly("ana", "bruno", "2026-11-01T22:00:00Z");
        connectDirectly("ana", "carla", "2026-11-02T22:00:00Z");
        connectDirectly("ana", "davi", "2026-11-03T22:00:00Z");
        connectDirectly("bruno", "carla", "2026-11-04T22:00:00Z");

        String firstPage = mockMvc.perform(get(CONNECTIONS_PATH).param("maxPageSize", "2").with(user("ana")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(firstPage, "$.nextPageToken");

        assertThat(JsonPath.<List<String>>read(firstPage, "$.items[*].accountId"))
                .containsExactly(accountOf("davi"), accountOf("carla"));

        mockMvc.perform(get(CONNECTIONS_PATH).param("maxPageSize", "2").param("pageToken", token).with(user("ana")))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"items": [{"accountId": "%s", "connectedAt": "2026-11-01T22:00:00Z"}], "nextPageToken": null}
                        """.formatted(accountOf("bruno")), JsonCompareMode.STRICT));
    }

    @Test
    void connectionsAtTheSameInstantAreAllListedAcrossPages() throws Exception {
        connectDirectly("ana", "bruno", DECIDED_AT);
        connectDirectly("ana", "carla", DECIDED_AT);
        connectDirectly("ana", "davi", DECIDED_AT);
        var seen = new ArrayList<String>();
        String token = null;

        do {
            var request = get(CONNECTIONS_PATH).param("maxPageSize", "1").with(user("ana"));
            if (token != null) {
                request.param("pageToken", token);
            }
            String body = mockMvc.perform(request).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            seen.addAll(JsonPath.read(body, "$.items[*].accountId"));
            token = JsonPath.read(body, "$.nextPageToken");
        } while (token != null);

        assertThat(seen).containsExactlyInAnyOrder(accountOf("bruno"), accountOf("carla"), accountOf("davi"));
    }

    @Test
    void nobodySeesTheConnectionsOfOthers() throws Exception {
        connectDirectly("ana", "bruno", DECIDED_AT);
        firstAccess(mockMvc, user("carla"));

        String body = connectionsBodyOf("carla");

        assertThat(body).contains("\"items\":[]").doesNotContain(accountOf("ana"), accountOf("bruno"));
    }

    /** O token só marca a posição: a consulta continua filtrando por quem pede. */
    @Test
    void aPageTokenFromAnotherAccountOnlyPagesTheCallersOwnConnections() throws Exception {
        connectDirectly("ana", "bruno", "2026-11-01T22:02:00Z");
        connectDirectly("ana", "carla", "2026-11-01T22:01:00Z");
        firstAccess(mockMvc, user("davi"));
        String anaFirstPage = mockMvc.perform(get(CONNECTIONS_PATH).param("maxPageSize", "1").with(user("ana")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String anaToken = JsonPath.read(anaFirstPage, "$.nextPageToken");

        mockMvc.perform(get(CONNECTIONS_PATH).param("pageToken", anaToken).with(user("davi")))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"items": [], "nextPageToken": null}
                        """, JsonCompareMode.STRICT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "101", "-1", "abc", ""})
    void anInvalidPageSizeIsABadRequest(String size) throws Exception {
        firstAccess(mockMvc, user("ana"));

        mockMvc.perform(get(CONNECTIONS_PATH).param("maxPageSize", size).with(user("ana")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @ParameterizedTest
    @ValueSource(strings = {"bm90LWEtdG9rZW4", "!!!", "", "MTk2OS0xMi0zMVQyMzo1OTo1OVogMDE5NjZjNGUtN2QxYS03YzNlLTliNWYtM2YyYTFjMGQ5ZThi",
            "KzEwMDAwMDAtMDEtMDFUMDA6MDA6MDBaIDAxOTY2YzRlLTdkMWEtN2MzZS05YjVmLTNmMmExYzBkOWU4Yg"})
    void aPageTokenTheApiDidNotIssueIsABadRequest(String token) throws Exception {
        firstAccess(mockMvc, user("ana"));

        mockMvc.perform(get(CONNECTIONS_PATH).param("pageToken", token).with(user("ana")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void anInvalidPageSizeNamesTheLimitOfThisList() throws Exception {
        firstAccess(mockMvc, user("ana"));

        mockMvc.perform(get(CONNECTIONS_PATH).param("maxPageSize", "101").with(user("ana")))
                .andExpect(content().json("""
                        {"title": "Bad Request", "status": 400,
                         "detail": "maxPageSize must be between 1 and 100", "instance": "/api/me/connections"}
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void aPageTokenTheApiDidNotIssueIsReportedWithoutEchoingIt() throws Exception {
        firstAccess(mockMvc, user("ana"));

        mockMvc.perform(get(CONNECTIONS_PATH).param("pageToken", "bm90LWEtdG9rZW4").with(user("ana")))
                .andExpect(content().json("""
                        {"title": "Bad Request", "status": 400,
                         "detail": "pageToken is invalid", "instance": "/api/me/connections"}
                        """, JsonCompareMode.STRICT));
    }

    /** Publica um evento, inscreve as pessoas, leva o relógio ao início e sorteia a rodada 1. */
    private String pairedInRoundOne(String... names) throws Exception {
        return RoundFixtures.pairedInRoundOne(mockMvc, jdbcClient, clock, names);
    }

    private ResultActions decide(String eventId, String name, String body) throws Exception {
        return mockMvc.perform(put(decisionPath(eventId, 1)).with(user(name))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String connectionsBodyOf(String name) throws Exception {
        return mockMvc.perform(get(CONNECTIONS_PATH).with(user(name)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static String decisionPath(String eventId, int number) {
        return "/api/events/" + eventId + "/rounds/" + number + "/decision";
    }

    private static String withoutEventId(String text, String eventId) {
        return text.replace(eventId, "<event>");
    }

    /** A mesma chave do JdbcDecisionRepository.lockPair: evento, rodada e o par normalizado. */
    private String lockKeyOf(String eventId, String one, String other) {
        String first = accountOf(one);
        String second = accountOf(other);
        if (second.compareTo(first) < 0) {
            String swap = first;
            first = second;
            second = swap;
        }
        return String.join(":", "connections.decision", eventId, "1", first, second);
    }

    private long decisionRows(String eventId) {
        return jdbcClient.sql("select count(*) from round_decision where event_id = cast(:id as uuid)")
                .param("id", eventId).query(Long.class).single();
    }

    private long connectionRows() {
        return jdbcClient.sql("select count(*) from connection").query(Long.class).single();
    }

    /**
     * Quem não formou par recebe o mesmo 404 em qualquer um dos casos, sem id de conta de ninguém, e nada é
     * gravado no evento do sorteio. O texto do detail é o que o cliente lê para distinguir esse 404 de um id
     * de evento mal formado.
     */
    private void expectNoPartnerAndNothingWritten(ResultActions response, String drawnEventId) throws Exception {
        String body = response.andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<String>read(body, "$.detail")).isEqualTo("the caller has no partner in this round");
        assertThat(body).doesNotContain(accountOf("ana"), accountOf("bruno"), accountOf("carla"));
        assertThat(decisionRows(drawnEventId)).isZero();
    }

    private String sittingOutIn(String eventId) {
        String account = jdbcClient.sql("""
                        select account_id from round_seat
                         where event_id = cast(:id as uuid) and round_number = 1 and partner_account_id is null
                        """)
                .param("id", eventId).query(String.class).single();
        return jdbcClient.sql("select subject from account where id = cast(:id as uuid)")
                .param("id", account).query(String.class).single().substring("oid-".length());
    }

    private void connectDirectly(String one, String other, String connectedAt) throws Exception {
        firstAccess(mockMvc, user(one));
        firstAccess(mockMvc, user(other));
        String first = accountOf(one);
        String second = accountOf(other);
        jdbcClient.sql("""
                        insert into connection (first_account_id, second_account_id, connected_at)
                        values (least(cast(:a as uuid), cast(:b as uuid)), greatest(cast(:a as uuid), cast(:b as uuid)),
                                cast(:at as timestamptz))
                        """)
                .params(Map.of("a", first, "b", second, "at", connectedAt))
                .update();
    }

    private String accountOf(String name) {
        return accountIdOf(jdbcClient, name);
    }

    private static RequestPostProcessor webSession(String name) {
        return TestIdentities.webSession("oid-" + name);
    }

}
