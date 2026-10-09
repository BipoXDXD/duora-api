package bipo.tech.duoraapi.events;

import static bipo.tech.duoraapi.events.EventFixtures.ADMIN_EVENTS_PATH;
import static bipo.tech.duoraapi.events.EventFixtures.adminEventPath;
import static bipo.tech.duoraapi.events.EventFixtures.admin;
import static bipo.tech.duoraapi.events.EventFixtures.createDraft;
import static bipo.tech.duoraapi.events.EventFixtures.eventJson;
import static bipo.tech.duoraapi.events.EventFixtures.randomId;
import static bipo.tech.duoraapi.events.EventFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import bipo.tech.duoraapi.TestClockConfiguration;
import bipo.tech.duoraapi.TestClockConfiguration.TestClock;
import bipo.tech.duoraapi.TestcontainersConfiguration;

/**
 * Administração de eventos (docs/adr/0016): o ADMIN cria um rascunho, publica e cancela. Só o papel
 * ADMIN chega às rotas; entrada estrita e limites na borda; estado inválido para a ação → 409.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestClockConfiguration.class})
class AdminEventIT {

    private static final int CONCURRENT_ACTIONS = 4;

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

    @Test
    void createsADraftAndAnswersWithItAndItsLocation() throws Exception {
        var response = create(eventJson())
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andReturn().getResponse();

        String id = jdbcClient.sql("select id::text from event").query(String.class).single();
        assertThat(response.getHeader(HttpHeaders.LOCATION)).isEqualTo(adminEventPath(id));
        JSONAssert.assertEquals(adminEvent(id, "DRAFT", 0), response.getContentAsString(), JSONCompareMode.STRICT);
    }

    @Test
    void adminReadsTheDraft() throws Exception {
        String id = createDraft(mockMvc, eventJson());

        mockMvc.perform(get(adminEventPath(id)).with(admin()))
                .andExpect(status().isOk())
                .andExpect(content().json(adminEvent(id, "DRAFT", 0), JsonCompareMode.STRICT));
    }

    @Test
    void publishingADraftMakesItPublished() throws Exception {
        String id = createDraft(mockMvc, eventJson());

        mockMvc.perform(post(adminEventPath(id) + ":publish").with(admin()))
                .andExpect(status().isOk())
                .andExpect(content().json(adminEvent(id, "PUBLISHED", 0), JsonCompareMode.STRICT));

        assertThat(statusOf(id)).isEqualTo("PUBLISHED");
    }

    @Test
    void publishingTwiceIsAConflict() throws Exception {
        String id = createDraft(mockMvc, eventJson());
        mockMvc.perform(post(adminEventPath(id) + ":publish").with(admin())).andExpect(status().isOk());

        mockMvc.perform(post(adminEventPath(id) + ":publish").with(admin()))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().json(conflict("only a draft can be published", id, ":publish",
                        "EVENT_ALREADY_PUBLISHED"), JsonCompareMode.STRICT));
    }

    @Test
    void draftWhoseStartHasPassedCannotBePublished() throws Exception {
        String id = createDraft(mockMvc, eventJson());
        clock.setTo(Instant.parse(EventFixtures.STARTS_AT));

        mockMvc.perform(post(adminEventPath(id) + ":publish").with(admin()))
                .andExpect(status().isConflict())
                .andExpect(content().json(conflict("the event has already started", id, ":publish", "EVENT_STARTED"),
                        JsonCompareMode.STRICT));

        assertThat(statusOf(id)).isEqualTo("DRAFT");
    }

    @Test
    void draftWhoseEndHasPassedCannotBePublished() throws Exception {
        String id = createDraft(mockMvc, eventJson());
        clock.setTo(Instant.parse(EventFixtures.ENDS_AT));

        mockMvc.perform(post(adminEventPath(id) + ":publish").with(admin()))
                .andExpect(status().isConflict())
                .andExpect(content().json(conflict("the event has already ended", id, ":publish", "EVENT_ENDED"),
                        JsonCompareMode.STRICT));

        assertThat(statusOf(id)).isEqualTo("DRAFT");
    }

    @Test
    void cancellingAPublishedEvent() throws Exception {
        String id = EventFixtures.createPublishedEvent(mockMvc);

        mockMvc.perform(post(adminEventPath(id) + ":cancel").with(admin()))
                .andExpect(status().isOk())
                .andExpect(content().json(adminEvent(id, "CANCELLED", 0), JsonCompareMode.STRICT));

        assertThat(statusOf(id)).isEqualTo("CANCELLED");
    }

    @Test
    void cancellingTwiceIsAConflict() throws Exception {
        String id = createDraft(mockMvc, eventJson());
        mockMvc.perform(post(adminEventPath(id) + ":cancel").with(admin())).andExpect(status().isOk());

        mockMvc.perform(post(adminEventPath(id) + ":cancel").with(admin()))
                .andExpect(status().isConflict())
                .andExpect(content().json(conflict("the event is already cancelled", id, ":cancel",
                        "EVENT_CANCELLED"), JsonCompareMode.STRICT));
    }

    @Test
    void endedEventCannotBeCancelled() throws Exception {
        String id = EventFixtures.createPublishedEvent(mockMvc);
        clock.setTo(Instant.parse(EventFixtures.ENDS_AT));

        mockMvc.perform(post(adminEventPath(id) + ":cancel").with(admin()))
                .andExpect(status().isConflict())
                .andExpect(content().json(conflict("the event has already ended", id, ":cancel", "EVENT_ENDED"),
                        JsonCompareMode.STRICT));

        assertThat(statusOf(id)).isEqualTo("PUBLISHED");
    }

    @Test
    void cancelledEventCannotBePublished() throws Exception {
        String id = createDraft(mockMvc, eventJson());
        mockMvc.perform(post(adminEventPath(id) + ":cancel").with(admin())).andExpect(status().isOk());

        mockMvc.perform(post(adminEventPath(id) + ":publish").with(admin()))
                .andExpect(status().isConflict())
                .andExpect(content().json(conflict("only a draft can be published", id, ":publish",
                        "EVENT_CANCELLED"), JsonCompareMode.STRICT));

        assertThat(statusOf(id)).isEqualTo("CANCELLED");
    }

    /**
     * Duas abas do ADMIN publicam ao mesmo tempo: uma publica, as outras recebem 409 (pela versão
     * otimista ou pelo estado já publicado), nunca 500.
     */
    @RepeatedTest(3)
    void concurrentPublishesPublishOnce() throws Exception {
        String id = createDraft(mockMvc, eventJson());
        var start = new CountDownLatch(1);
        var futures = new ArrayList<Future<Integer>>();

        try (var executor = Executors.newFixedThreadPool(CONCURRENT_ACTIONS)) {
            for (int i = 0; i < CONCURRENT_ACTIONS; i++) {
                futures.add(executor.submit(actionAfter(start, id, ":publish")));
            }
            start.countDown();
            var statuses = new ArrayList<Integer>();
            for (var future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
            assertThat(statuses).containsOnly(200, 409).containsOnlyOnce(200);
        }
        assertThat(statusOf(id)).isEqualTo("PUBLISHED");
    }

    /**
     * Publicar e cancelar o mesmo rascunho ao mesmo tempo: nenhuma resposta é 500, e o estado final é o
     * da última ação que respondeu 200. Cancelar depois de publicar é válido, então os dois 200 também
     * podem acontecer, e aí o evento termina cancelado.
     */
    @RepeatedTest(3)
    void concurrentPublishAndCancelLeaveTheStateOfTheActionsThatSucceeded() throws Exception {
        String id = createDraft(mockMvc, eventJson());
        var start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> publish = executor.submit(actionAfter(start, id, ":publish"));
            Future<Integer> cancel = executor.submit(actionAfter(start, id, ":cancel"));
            start.countDown();
            int published = publish.get(30, TimeUnit.SECONDS);
            int cancelled = cancel.get(30, TimeUnit.SECONDS);

            assertThat(List.of(published, cancelled)).isSubsetOf(200, 409).contains(200);
            assertThat(statusOf(id)).isEqualTo(cancelled == 200 ? "CANCELLED" : "PUBLISHED");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET ", "POST :publish", "POST :cancel"})
    void unknownEventIsNotFound(String route) throws Exception {
        mockMvc.perform(adminRequest(route, randomId()).with(admin()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().json("""
                        {"title": "Not Found", "status": 404, "detail": "event not found"}
                        """));
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET ", "POST :publish", "POST :cancel"})
    void invalidEventIdIsABadRequest(String route) throws Exception {
        mockMvc.perform(adminRequest(route, "not-a-uuid").with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    /**
     * A ação é outra rota, não um id: GET em {@code {id}:publish} não pode cair na leitura do evento com
     * o id "uuid:publish". O fuzzing achou o OPTIONS anunciando GET nessas rotas.
     */
    @ParameterizedTest
    @ValueSource(strings = {":publish", ":cancel"})
    void actionRouteAnswersOnlyToPost(String action) throws Exception {
        String path = adminEventPath(randomId()) + action;

        mockMvc.perform(get(path).with(admin()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string(HttpHeaders.ALLOW, not(containsString("GET"))));
        mockMvc.perform(options(path).with(admin()))
                .andExpect(header().string(HttpHeaders.ALLOW, not(containsString("GET"))));
    }

    @Test
    void userWithoutTheAdminRoleCannotCreate() throws Exception {
        mockMvc.perform(post(ADMIN_EVENTS_PATH).with(user("ana"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson()))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));

        assertThat(eventRows()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET ", "POST :publish", "POST :cancel"})
    void userWithoutTheAdminRoleIsForbiddenAndChangesNothing(String route) throws Exception {
        String id = createDraft(mockMvc, eventJson());

        var body = mockMvc.perform(adminRequest(route, id).with(user("ana")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("Noite de jogos", id);
        assertThat(statusOf(id)).isEqualTo("DRAFT");
    }

    /** Outro papel qualquer não serve: só ADMIN. */
    @Test
    void anotherRoleIsForbidden() throws Exception {
        mockMvc.perform(post(ADMIN_EVENTS_PATH)
                        .with(jwt().jwt(token -> token.claim("oid", "oid-moderator"))
                                .authorities(new SimpleGrantedAuthority("ROLE_MODERATOR")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson()))
                .andExpect(status().isForbidden());

        assertThat(eventRows()).isZero();
    }

    @Test
    void anonymousCannotCreate() throws Exception {
        mockMvc.perform(post(ADMIN_EVENTS_PATH).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson()))
                .andExpect(status().isUnauthorized());

        assertThat(eventRows()).isZero();
    }

    @Test
    void adminWebSessionWithoutCsrfTokenCannotCreate() throws Exception {
        mockMvc.perform(post(ADMIN_EVENTS_PATH).with(adminWebSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson()))
                .andExpect(status().isForbidden());

        assertThat(eventRows()).isZero();
    }

    @Test
    void adminWebSessionWithCsrfTokenCreates() throws Exception {
        mockMvc.perform(post(ADMIN_EVENTS_PATH).with(adminWebSession()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson()))
                .andExpect(status().isCreated());

        assertThat(eventRows()).isEqualTo(1);
    }

    /** id, estado, contagem e datas de controle são do servidor (mass assignment). */
    @ParameterizedTest
    @ValueSource(strings = {"status", "id", "registrationCount", "createdAt", "version"})
    void serverOwnedFieldIsRejectedWithoutWriting(String field) throws Exception {
        String body = eventJson().replaceFirst("\\{", "{\"" + field + "\": \"PUBLISHED\", ");

        create(body)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));

        assertThat(eventRows()).isZero();
    }

    @ParameterizedTest
    @MethodSource("validBordersAndLimits")
    void acceptsValuesOnTheBorder(String body) throws Exception {
        create(body).andExpect(status().isCreated());

        assertThat(eventRows()).isEqualTo(1);
    }

    static Stream<Named<String>> validBordersAndLimits() {
        return Stream.of(
                Named.of("título com 80 caracteres", withField("title", "a".repeat(80))),
                Named.of("descrição com 500 caracteres", withField("description", "a".repeat(500))),
                Named.of("capacidade 2", eventJson(EventFixtures.STARTS_AT, EventFixtures.ENDS_AT, 2)),
                Named.of("capacidade 200", eventJson(EventFixtures.STARTS_AT, EventFixtures.ENDS_AT, 200)),
                Named.of("duração de 12 horas", eventJson("2026-11-01T10:00:00Z", "2026-11-01T22:00:00Z", 10)),
                Named.of("início logo depois de agora", eventJson("2026-10-06T12:00:01Z", "2026-10-06T13:00:00Z", 10)),
                Named.of("início daqui a 365 dias", eventJson("2027-10-06T12:00:00Z", "2027-10-06T13:00:00Z", 10)),
                Named.of("horário com fuso de Brasília", eventJson("2026-11-01T19:00:00-03:00", "2026-11-01T22:00:00-03:00", 10)));
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    void invalidInputIsRejectedWithoutWriting(String body) throws Exception {
        create(body)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));

        assertThat(eventRows()).isZero();
    }

    static Stream<Named<String>> invalidBodies() {
        return Stream.of(
                Named.of("título com 81 caracteres", withField("title", "a".repeat(81))),
                Named.of("título em branco", withField("title", "   ")),
                Named.of("título com NUL", withField("title", "Noite\\u0000")),
                Named.of("título com controle de direção", withField("title", "Noite\\u202e")),
                Named.of("título com quebra de linha", withField("title", "Noite\\nde jogos")),
                Named.of("descrição com 501 caracteres", withField("description", "a".repeat(501))),
                Named.of("descrição com NUL", withField("description", "Jogos\\u0000")),
                Named.of("descrição com controle de direção", withField("description", "Jogos\\u202e")),
                Named.of("descrição com zero-width space", withField("description", "Jogos\\u200b")),
                Named.of("capacidade 1", eventJson(EventFixtures.STARTS_AT, EventFixtures.ENDS_AT, 1)),
                Named.of("capacidade 0", eventJson(EventFixtures.STARTS_AT, EventFixtures.ENDS_AT, 0)),
                Named.of("capacidade negativa", eventJson(EventFixtures.STARTS_AT, EventFixtures.ENDS_AT, -1)),
                Named.of("capacidade 201", eventJson(EventFixtures.STARTS_AT, EventFixtures.ENDS_AT, 201)),
                Named.of("capacidade fracionária", eventJson().replace("\"capacity\": 10", "\"capacity\": 10.5")),
                Named.of("capacidade enorme", eventJson().replace("\"capacity\": 10", "\"capacity\": 99999999999")),
                Named.of("duração acima de 12 horas", eventJson("2026-11-01T10:00:00Z", "2026-11-01T22:00:01Z", 10)),
                Named.of("fim igual ao início", eventJson(EventFixtures.STARTS_AT, EventFixtures.STARTS_AT, 10)),
                Named.of("fim antes do início", eventJson(EventFixtures.ENDS_AT, EventFixtures.STARTS_AT, 10)),
                Named.of("início agora", eventJson("2026-10-06T12:00:00Z", "2026-10-06T13:00:00Z", 10)),
                Named.of("início no passado", eventJson("2026-10-01T12:00:00Z", "2026-10-01T13:00:00Z", 10)),
                Named.of("início a mais de 365 dias", eventJson("2027-10-06T12:00:01Z", "2027-10-06T13:00:00Z", 10)),
                Named.of("horário sem fuso", eventJson("2026-11-01T22:00:00", "2026-11-02T01:00:00", 10)),
                Named.of("data sem hora", eventJson("2026-11-01", "2026-11-02", 10)),
                Named.of("horário como número", eventJson().replace("\"" + EventFixtures.STARTS_AT + "\"", "1793570400")),
                Named.of("horário com injeção de SQL", withField("startsAt", "2026-11-01T22:00:00Z' OR '1'='1")),
                Named.of("sem título", """
                        {"description": "Jogos.", "startsAt": "2026-11-01T22:00:00Z",
                         "endsAt": "2026-11-02T01:00:00Z", "capacity": 10}
                        """),
                Named.of("sem descrição", """
                        {"title": "Noite", "startsAt": "2026-11-01T22:00:00Z",
                         "endsAt": "2026-11-02T01:00:00Z", "capacity": 10}
                        """),
                Named.of("sem início", """
                        {"title": "Noite", "description": "Jogos.", "endsAt": "2026-11-02T01:00:00Z", "capacity": 10}
                        """),
                Named.of("sem fim", """
                        {"title": "Noite", "description": "Jogos.", "startsAt": "2026-11-01T22:00:00Z", "capacity": 10}
                        """),
                Named.of("sem capacidade", """
                        {"title": "Noite", "description": "Jogos.", "startsAt": "2026-11-01T22:00:00Z",
                         "endsAt": "2026-11-02T01:00:00Z"}
                        """),
                Named.of("título nulo", eventJson().replace("\"Noite de jogos\"", "null")),
                Named.of("título como objeto", eventJson().replace("\"Noite de jogos\"", "{\"pt\": \"Noite\"}")),
                Named.of("JSON quebrado", "{\"title\": "),
                Named.of("lista no lugar do objeto", "[]"),
                Named.of("null no lugar do objeto", "null"));
    }

    /** Texto livre vai por parâmetro: chega ao banco e volta como texto, sem virar SQL. */
    @Test
    void sqlInTheTitleIsStoredAsPlainText() throws Exception {
        String title = "Noite'); drop table event;--";

        String id = createDraft(mockMvc, withField("title", title));

        mockMvc.perform(get(adminEventPath(id)).with(admin()))
                .andExpect(content().json("""
                        {"title": "%s"}
                        """.formatted(title)));
    }

    private Callable<Integer> actionAfter(CountDownLatch start, String id, String action) {
        return () -> {
            start.await();
            return mockMvc.perform(post(adminEventPath(id) + action).with(admin())).andReturn().getResponse().getStatus();
        };
    }

    private ResultActions create(String body) throws Exception {
        return mockMvc.perform(post(ADMIN_EVENTS_PATH).with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private long eventRows() {
        return jdbcClient.sql("select count(*) from event").query(Long.class).single();
    }

    private String statusOf(String id) {
        return jdbcClient.sql("select status from event where id = cast(:id as uuid)")
                .param("id", id)
                .query(String.class).single();
    }

    private static MockHttpServletRequestBuilder adminRequest(String route, String id) {
        String[] methodAndSuffix = route.split(" ", 2);
        String path = adminEventPath(id) + methodAndSuffix[1];
        return "GET".equals(methodAndSuffix[0]) ? get(path) : post(path);
    }

    private static String adminEvent(String id, String status, int registrations) {
        return """
                {"id": "%s", "title": "Noite de jogos", "description": "Jogos de tabuleiro em dupla.",
                 "startsAt": "2026-11-01T22:00:00Z", "endsAt": "2026-11-02T01:00:00Z", "capacity": 10,
                 "status": "%s", "registrationCount": %d}
                """.formatted(id, status, registrations);
    }

    /** Troca o valor de um campo de texto do evento válido; o valor vai como está, já escapado para JSON. */
    private static String withField(String field, String value) {
        return eventJson().replaceFirst("\"" + field + "\": \"[^\"]*\"",
                Matcher.quoteReplacement("\"" + field + "\": \"" + value + "\""));
    }

    private static RequestPostProcessor adminWebSession() {
        return oidcLogin().idToken(token -> token.issuer("https://tenant-id.ciamlogin.example/tenant-id/v2.0")
                        .claim("oid", "oid-admin"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    /** O 409 inteiro de uma ação do ADMIN, com o motivo em reason (docs/adr/0020). */
    private static String conflict(String detail, String id, String action, String reason) {
        return """
                {"title": "Conflict", "status": 409, "detail": "%s", "instance": "%s%s", "reason": "%s"}
                """.formatted(detail, adminEventPath(id), action, reason);
    }

}
