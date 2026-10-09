package bipo.tech.duoraapi.matching;

import static bipo.tech.duoraapi.AccountFixtures.accountIdOf;
import static bipo.tech.duoraapi.ConcurrentCalls.sameCallTogether;
import static bipo.tech.duoraapi.ConcurrentCalls.statusCodeOf;
import static bipo.tech.duoraapi.ConcurrentCalls.together;
import static bipo.tech.duoraapi.TestIdentities.ISSUER;
import static bipo.tech.duoraapi.events.EventFixtures.admin;
import static bipo.tech.duoraapi.events.EventFixtures.adminEventPath;
import static bipo.tech.duoraapi.events.EventFixtures.createDraft;
import static bipo.tech.duoraapi.events.EventFixtures.createPublishedEvent;
import static bipo.tech.duoraapi.events.EventFixtures.eventJson;
import static bipo.tech.duoraapi.events.EventFixtures.registerWithCompleteProfile;
import static bipo.tech.duoraapi.events.EventFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

import com.jayway.jsonpath.JsonPath;

import bipo.tech.duoraapi.HeldLock;
import bipo.tech.duoraapi.TestClockConfiguration;
import bipo.tech.duoraapi.TestClockConfiguration.TestClock;
import bipo.tech.duoraapi.TestcontainersConfiguration;
import bipo.tech.duoraapi.events.EventFixtures;

/**
 * Rodadas de pareamento (docs/adr/0017): o ADMIN inicia a rodada com o evento em andamento, uma rodada só
 * mesmo com pedidos simultâneos, pares sem bloqueio e sem repetição no evento, prioridade para quem ficou
 * de fora, e cada pessoa só vê o próprio par.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestClockConfiguration.class})
class RoundIT {

    private static final Instant STARTS_AT = Instant.parse(EventFixtures.STARTS_AT);
    private static final Instant ENDS_AT = Instant.parse(EventFixtures.ENDS_AT);
    private static final int CONCURRENT_REQUESTS = 5;

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
    void startingTheFirstRoundPairsEveryRegistrant() throws Exception {
        String eventId = underwayEventWith("ana", "bruno", "carla", "davi");

        startRound(eventId, 1)
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, "/api/admin/events/" + eventId + "/rounds/1"))
                .andExpect(content().json("""
                        {"eventId": "%s", "number": 1, "startedAt": "2026-11-01T22:00:00Z",
                         "pairCount": 2, "sittingOutCount": 0}
                        """.formatted(eventId), JsonCompareMode.STRICT));

        assertThat(roundRows(eventId)).isEqualTo(1);
        assertThat(partnersIn(eventId, 1)).hasSize(4).doesNotContainValue(null);
        assertThatPairsAreReciprocal(partnersIn(eventId, 1));
    }

    @Test
    void startingTheSameRoundAgainAnswersTheSameRound() throws Exception {
        String eventId = underwayEventWith("ana", "bruno");
        startRound(eventId, 1).andExpect(status().isCreated());
        Map<String, String> firstSeats = partnersIn(eventId, 1);
        long firstSeed = seedOf(eventId, 1);
        clock.advance(Duration.ofMinutes(5));

        startRound(eventId, 1)
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andExpect(jsonPath("$.startedAt").value("2026-11-01T22:00:00Z"))
                .andExpect(jsonPath("$.pairCount").value(1));

        assertThat(roundRows(eventId)).isEqualTo(1);
        assertThat(partnersIn(eventId, 1)).isEqualTo(firstSeats);
        assertThat(seedOf(eventId, 1)).isEqualTo(firstSeed);
    }

    /** Duas abas, ou um clique duplo: todos respondem sucesso, e há uma rodada e um sorteio só. */
    @RepeatedTest(3)
    void concurrentStartsOfTheSameRoundCreateASingleRound() throws Exception {
        String eventId = underwayEventWith("ana", "bruno", "carla", "davi", "eva");

        var statuses = sameCallTogether(CONCURRENT_REQUESTS, statusCodeOf(() -> startRound(eventId, 1)));

        assertThat(statuses).containsOnly(201, 200).containsOnlyOnce(201);
        assertThat(roundRows(eventId)).isEqualTo(1);
        assertThat(partnersIn(eventId, 1)).hasSize(5);
    }

    /** A rodada seguinte pedida junto com a anterior nunca existe sem ela. */
    @RepeatedTest(3)
    void aRoundStartedTogetherWithThePreviousOneNeverExistsWithoutIt() throws Exception {
        String eventId = underwayEventWith("ana", "bruno", "carla", "davi");

        var statuses = together(List.of(
                statusCodeOf(() -> startRound(eventId, 1)),
                statusCodeOf(() -> startRound(eventId, 2))));

        assertThat(statuses.getFirst()).isEqualTo(201);
        assertThat(statuses.getLast()).isIn(201, 409);
        assertThat(jdbcClient.sql("""
                        select count(*) from round r
                         where r.event_id = cast(:id as uuid) and r.number = 2
                           and not exists (select 1 from round p where p.event_id = r.event_id and p.number = 1)
                        """)
                .param("id", eventId).query(Long.class).single()).isZero();
    }

    @Test
    void aRoundNeedsThePreviousOneAndWritesNothingWithoutIt() throws Exception {
        String eventId = underwayEventWith("ana", "bruno");

        startRound(eventId, 2)
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().json("""
                        {"title": "Conflict", "status": 409, "detail": "the previous round has not started yet",
                         "instance": "%s", "reason": "ROUND_OUT_OF_SEQUENCE"}
                        """.formatted(roundPath(eventId, 2)), JsonCompareMode.STRICT));

        assertThat(roundRows(eventId)).isZero();
        assertThat(seatRows(eventId)).isZero();
    }

    @Test
    void peopleSeparatedByABlockAreNeverPaired() throws Exception {
        String eventId = underwayEventWith("ana", "bruno");
        mockMvc.perform(post("/api/accounts/{id}:block", accountOf("bruno")).with(user("ana")))
                .andExpect(status().isNoContent());

        startRound(eventId, 1)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pairCount").value(0))
                .andExpect(jsonPath("$.sittingOutCount").value(2));

        assertThat(partnersIn(eventId, 1)).containsOnlyKeys(accountOf("ana"), accountOf("bruno"))
                .containsValues((String) null);
    }

    /** Quatro pessoas formam três pareamentos completos diferentes; na quarta rodada, nenhum par é novo. */
    @Test
    void aPairIsNeverFormedTwiceInTheSameEvent() throws Exception {
        String eventId = underwayEventWith("ana", "bruno", "carla", "davi");
        Set<Set<String>> pairsSeen = new HashSet<>();

        for (int round = 1; round <= 3; round++) {
            startRound(eventId, round).andExpect(status().isCreated()).andExpect(jsonPath("$.pairCount").value(2));
            for (var seat : partnersIn(eventId, round).entrySet()) {
                pairsSeen.add(Set.of(seat.getKey(), seat.getValue()));
            }
        }
        startRound(eventId, 4)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pairCount").value(0))
                .andExpect(jsonPath("$.sittingOutCount").value(4));

        assertThat(pairsSeen).hasSize(6);
    }

    @Test
    void whoSatOutIsPairedInTheNextRound() throws Exception {
        String eventId = underwayEventWith("ana", "bruno", "carla");
        startRound(eventId, 1).andExpect(status().isCreated()).andExpect(jsonPath("$.sittingOutCount").value(1));
        String satOut = sittingOutIn(eventId, 1);

        startRound(eventId, 2).andExpect(status().isCreated());

        assertThat(partnersIn(eventId, 2).get(satOut)).isNotNull();
    }

    @Test
    void anEventWithoutRegistrantsGetsAnEmptyRound() throws Exception {
        String eventId = createPublishedEvent(mockMvc);
        clock.setTo(STARTS_AT);

        startRound(eventId, 1)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pairCount").value(0))
                .andExpect(jsonPath("$.sittingOutCount").value(0));
    }

    @Test
    void roundsDoNotStartBeforeTheEvent() throws Exception {
        String eventId = underwayEventWith("ana", "bruno");
        clock.setTo(STARTS_AT.minusNanos(1000));

        startRound(eventId, 1)
                .andExpect(status().isConflict())
                .andExpect(content().json(notUnderway(eventId), JsonCompareMode.STRICT));

        assertThat(roundRows(eventId)).isZero();
    }

    @Test
    void roundsDoNotStartAfterTheEvent() throws Exception {
        String eventId = underwayEventWith("ana", "bruno");
        clock.setTo(ENDS_AT);

        startRound(eventId, 1)
                .andExpect(status().isConflict())
                .andExpect(content().json(notUnderway(eventId), JsonCompareMode.STRICT));

        assertThat(roundRows(eventId)).isZero();
    }

    @Test
    void anExistingRoundIsStillAnsweredAfterTheEvent() throws Exception {
        String eventId = underwayEventWith("ana", "bruno");
        startRound(eventId, 1).andExpect(status().isCreated());
        clock.setTo(ENDS_AT);

        startRound(eventId, 1).andExpect(status().isOk()).andExpect(jsonPath("$.pairCount").value(1));
    }

    @Test
    void roundsDoNotStartInACancelledEvent() throws Exception {
        String eventId = underwayEventWith("ana", "bruno");
        mockMvc.perform(post(adminEventPath(eventId) + ":cancel").with(admin())).andExpect(status().isOk());

        startRound(eventId, 1)
                .andExpect(status().isConflict())
                .andExpect(content().json(notUnderway(eventId), JsonCompareMode.STRICT));

        assertThat(roundRows(eventId)).isZero();
    }

    @Test
    void roundsDoNotStartInADraft() throws Exception {
        String eventId = createDraft(mockMvc, eventJson());
        clock.setTo(STARTS_AT);

        startRound(eventId, 1)
                .andExpect(status().isConflict())
                .andExpect(content().json(notUnderway(eventId), JsonCompareMode.STRICT));

        assertThat(roundRows(eventId)).isZero();
    }

    @Test
    void anUnknownEventIsNotFound() throws Exception {
        clock.setTo(STARTS_AT);

        startRound(UUID.randomUUID().toString(), 1)
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "101", "-1", "abc", "1.5", "99999999999"})
    void anInvalidRoundNumberIsABadRequestAndWritesNothing(String number) throws Exception {
        String eventId = underwayEventWith("ana", "bruno");

        mockMvc.perform(put("/api/admin/events/" + eventId + "/rounds/" + number).with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
        mockMvc.perform(get("/api/events/" + eventId + "/rounds/" + number + "/pairing").with(user("ana")))
                .andExpect(status().isBadRequest());

        assertThat(roundRows(eventId)).isZero();
    }

    @Test
    void anEventIdThatIsNotAUuidIsABadRequest() throws Exception {
        clock.setTo(STARTS_AT);

        mockMvc.perform(put("/api/admin/events/not-a-uuid/rounds/1").with(admin()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aUserWithoutTheAdminRoleCannotStartNorReadARound() throws Exception {
        String eventId = underwayEventWith("ana", "bruno");

        startRound(eventId, 1, user("ana"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
        mockMvc.perform(get(roundPath(eventId, 1)).with(user("ana"))).andExpect(status().isForbidden());

        assertThat(roundRows(eventId)).isZero();
    }

    @Test
    void anAdminWebSessionWithoutCsrfTokenCannotStartARound() throws Exception {
        String eventId = underwayEventWith("ana", "bruno");

        startRound(eventId, 1, adminWebSession()).andExpect(status().isForbidden());

        assertThat(roundRows(eventId)).isZero();
    }

    @Test
    void anAdminWebSessionWithCsrfTokenStartsARound() throws Exception {
        String eventId = underwayEventWith("ana", "bruno");

        mockMvc.perform(put(roundPath(eventId, 1)).with(adminWebSession()).with(csrf()))
                .andExpect(status().isCreated());
    }

    /** O ADMIN vê quantos pares e quantas pessoas de fora, nunca quem: o conjunto de chaves é exato. */
    @Test
    void theAdminReadsTheCountsOfARoundButNotWhoIsInIt() throws Exception {
        String eventId = underwayEventWith("ana", "bruno", "carla");
        startRound(eventId, 1).andExpect(status().isCreated());

        String body = mockMvc.perform(get(roundPath(eventId, 1)).with(admin()))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"eventId": "%s", "number": 1, "startedAt": "2026-11-01T22:00:00Z",
                         "pairCount": 1, "sittingOutCount": 1}
                        """.formatted(eventId), JsonCompareMode.STRICT))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(accountOf("ana"), accountOf("bruno"), accountOf("carla"));
    }

    @Test
    void aRoundThatDoesNotExistIsNotFoundForTheAdmin() throws Exception {
        String eventId = underwayEventWith("ana", "bruno");

        mockMvc.perform(get(roundPath(eventId, 1)).with(admin()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void aPairedPersonSeesOnlyTheirPartner() throws Exception {
        String eventId = underwayEventWith("ana", "bruno");
        startRound(eventId, 1).andExpect(status().isCreated());

        mockMvc.perform(get(pairingPath(eventId, 1)).with(user("ana")))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"eventId": "%s", "roundNumber": 1, "partnerAccountId": "%s"}
                        """.formatted(eventId, accountOf("bruno")),
                        JsonCompareMode.STRICT));
    }

    @Test
    void whoSatOutSeesNoPartner() throws Exception {
        String eventId = underwayEventWith("ana", "bruno", "carla");
        startRound(eventId, 1).andExpect(status().isCreated());
        String satOut = sittingOutIn(eventId, 1);

        String body = mockMvc.perform(get(pairingPath(eventId, 1)).with(user(nameOf(satOut))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partnerAccountId").value((Object) null))
                .andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<Map<String, Object>>read(body, "$")).containsOnlyKeys(
                "eventId", "roundNumber", "partnerAccountId");
    }

    /** Quem não estava no sorteio recebe o mesmo 404 de uma rodada que não existe, sem dado de ninguém. */
    @Test
    void someoneOutsideTheRoundCannotSeeAnyPairOfIt() throws Exception {
        String otherEventId = createPublishedEvent(mockMvc);
        registerWithCompleteProfile(mockMvc, user("carla"), otherEventId);
        String eventId = underwayEventWith("ana", "bruno");
        startRound(eventId, 1).andExpect(status().isCreated());

        String outsider = mockMvc.perform(get(pairingPath(eventId, 1)).with(user("carla")))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        String missingRound = mockMvc.perform(get(pairingPath(eventId, 2)).with(user("ana")))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        assertThat(outsider).doesNotContain(accountOf("ana"), accountOf("bruno"));
        assertThat(JsonPath.<String>read(outsider, "$.detail")).isEqualTo(JsonPath.read(missingRound, "$.detail"));
    }

    @Test
    void aRoundStartIsRefusedWhenAnotherRequestHoldsItTooLong() throws Exception {
        String eventId = underwayEventWith("ana", "bruno");

        try (var _ = HeldLock.hold(transactionTemplate, () -> jdbcClient.sql("""
                        insert into round (event_id, number, previous_number, seed, started_at)
                        values (cast(:id as uuid), 1, null, 42, now())
                        """)
                .param("id", eventId)
                .update())) {
            startRound(eventId, 1)
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"))
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
        }

        assertThat(roundRows(eventId)).isZero();
        assertThat(seatRows(eventId)).isZero();
    }

    /**
     * Quem está logado vê o número da última rodada iniciada no próprio evento (docs/adr/0017), sem pares nem
     * inscritos: o mesmo conjunto de chaves de antes, mais currentRound. Vale também para quem não se inscreveu.
     */
    @Test
    void theEventTellsItsLatestStartedRound() throws Exception {
        String eventId = underwayEventWith("ana", "bruno", "carla");
        startRound(eventId, 1).andExpect(status().isCreated());
        startRound(eventId, 2).andExpect(status().isCreated());

        for (String name : List.of("ana", "davi")) {
            mockMvc.perform(get("/api/events/" + eventId).with(user(name)))
                    .andExpect(status().isOk())
                    .andExpect(content().json("""
                            {"id": "%s", "title": "Noite de jogos", "description": "Jogos de tabuleiro em dupla.",
                             "startsAt": "%s", "endsAt": "%s", "status": "PUBLISHED", "currentRound": 2}
                            """.formatted(eventId, EventFixtures.STARTS_AT, EventFixtures.ENDS_AT),
                            JsonCompareMode.STRICT));
        }
    }

    @Test
    void anUnderwayEventWithoutRoundsHasNoCurrentRound() throws Exception {
        String eventId = underwayEventWith("ana", "bruno");

        mockMvc.perform(get("/api/events/" + eventId).with(user("ana")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentRound").value(nullValue()));
    }

    /** As rodadas de um evento não aparecem em outro. */
    @Test
    void theCurrentRoundBelongsToItsOwnEvent() throws Exception {
        String withRounds = underwayEventWith("ana", "bruno");
        startRound(withRounds, 1).andExpect(status().isCreated());
        clock.setTo(TestClockConfiguration.NOW);
        String other = underwayEventWith();

        mockMvc.perform(get("/api/events/" + other).with(user("ana")))
                .andExpect(jsonPath("$.currentRound").value(nullValue()));
    }

    /** Publica um evento, inscreve as pessoas com o perfil completo e leva o relógio ao início. */
    private String underwayEventWith(String... names) throws Exception {
        return RoundFixtures.underwayEventWith(mockMvc, jdbcClient, clock, names);
    }

    private ResultActions startRound(String eventId, int number) throws Exception {
        return startRound(eventId, number, admin());
    }

    private ResultActions startRound(String eventId, int number, RequestPostProcessor caller) throws Exception {
        return mockMvc.perform(put(roundPath(eventId, number)).with(caller));
    }

    private static String roundPath(String eventId, int number) {
        return "/api/admin/events/" + eventId + "/rounds/" + number;
    }

    private static String pairingPath(String eventId, int number) {
        return "/api/events/" + eventId + "/rounds/" + number + "/pairing";
    }

    private long roundRows(String eventId) {
        return jdbcClient.sql("select count(*) from round where event_id = cast(:id as uuid)")
                .param("id", eventId).query(Long.class).single();
    }

    private long seatRows(String eventId) {
        return jdbcClient.sql("select count(*) from round_seat where event_id = cast(:id as uuid)")
                .param("id", eventId).query(Long.class).single();
    }

    private long seedOf(String eventId, int number) {
        return jdbcClient.sql("select seed from round where event_id = cast(:id as uuid) and number = :number")
                .param("id", eventId).param("number", number).query(Long.class).single();
    }

    /** Conta → parceiro (null para quem ficou de fora), relidos do banco. */
    private Map<String, String> partnersIn(String eventId, int number) {
        var partners = new HashMap<String, String>();
        jdbcClient.sql("""
                        select account_id, partner_account_id from round_seat
                         where event_id = cast(:id as uuid) and round_number = :number
                        """)
                .param("id", eventId)
                .param("number", number)
                .query((ResultSet row) -> {
                    partners.put(row.getString("account_id"), row.getString("partner_account_id"));
                });
        return partners;
    }

    private String sittingOutIn(String eventId, int number) {
        List<String> out = partnersIn(eventId, number).entrySet().stream()
                .filter(seat -> seat.getValue() == null)
                .map(Map.Entry::getKey)
                .toList();
        assertThat(out).hasSize(1);
        return out.getFirst();
    }

    private static void assertThatPairsAreReciprocal(Map<String, String> partners) {
        partners.forEach((account, partner) -> assertThat(partners.get(partner)).isEqualTo(account));
    }

    private String accountOf(String name) {
        return accountIdOf(jdbcClient, name);
    }

    private String nameOf(String accountId) {
        return jdbcClient.sql("select subject from account where id = cast(:id as uuid)")
                .param("id", accountId)
                .query(String.class).single().substring("oid-".length());
    }

    private static RequestPostProcessor adminWebSession() {
        return oidcLogin().idToken(token -> token.issuer(ISSUER).claim("oid", "oid-admin"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private static String notUnderway(String eventId) {
        return """
                {"title": "Conflict", "status": 409,
                 "detail": "rounds start only while the event is published and underway",
                 "instance": "%s", "reason": "EVENT_NOT_UNDERWAY"}
                """.formatted(roundPath(eventId, 1));
    }

}
