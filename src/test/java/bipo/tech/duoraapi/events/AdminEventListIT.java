package bipo.tech.duoraapi.events;

import static bipo.tech.duoraapi.events.EventFixtures.ADMIN_EVENTS_PATH;
import static bipo.tech.duoraapi.events.EventFixtures.admin;
import static bipo.tech.duoraapi.events.EventFixtures.adminEventPath;
import static bipo.tech.duoraapi.events.EventFixtures.createDraft;
import static bipo.tech.duoraapi.events.EventFixtures.createPublishedEvent;
import static bipo.tech.duoraapi.events.EventFixtures.eventJson;
import static bipo.tech.duoraapi.events.EventFixtures.registerWithCompleteProfile;
import static bipo.tech.duoraapi.events.EventFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import bipo.tech.duoraapi.TestClockConfiguration;
import bipo.tech.duoraapi.TestClockConfiguration.TestClock;
import bipo.tech.duoraapi.TestcontainersConfiguration;

/**
 * A lista de eventos do ADMIN (docs/adr/0016): todos os estados, rascunho incluído, do início mais distante
 * ao mais antigo, paginada por keyset, com filtro por estado e a contagem de inscritos de cada evento.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestClockConfiguration.class})
class AdminEventListIT {

    private static final String PAGE_SIZE_DETAIL = "maxPageSize must be between 1 and 50";
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;

    /** Mais páginas que isso nos testes de paginação só aconteceria com o cursor repetindo itens. */
    private static final int MAX_PAGES_TO_FOLLOW = 10;

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
    void listsEveryStatusFromTheLatestStartToTheEarliestWithTheirRegistrationCounts() throws Exception {
        String draft = createDraft(mockMvc, eventJson("2026-11-01T22:00:00Z", "2026-11-02T01:00:00Z", 10));
        String cancelled = createPublishedEvent(mockMvc, eventJson("2026-11-02T22:00:00Z", "2026-11-03T01:00:00Z", 10));
        String published = createPublishedEvent(mockMvc, eventJson("2026-11-03T22:00:00Z", "2026-11-04T01:00:00Z", 10));
        registerWithCompleteProfile(mockMvc, user("ana"), published);
        registerWithCompleteProfile(mockMvc, user("bia"), published);
        registerWithCompleteProfile(mockMvc, user("cris"), cancelled);
        mockMvc.perform(post(adminEventPath(cancelled) + ":cancel").with(admin())).andExpect(status().isOk());

        mockMvc.perform(get(ADMIN_EVENTS_PATH).with(admin()))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {"items": [%s, %s, %s], "nextPageToken": null}
                        """.formatted(
                        item(published, "2026-11-03T22:00:00Z", "2026-11-04T01:00:00Z", "PUBLISHED", 2),
                        item(cancelled, "2026-11-02T22:00:00Z", "2026-11-03T01:00:00Z", "CANCELLED", 1),
                        item(draft, "2026-11-01T22:00:00Z", "2026-11-02T01:00:00Z", "DRAFT", 0)),
                        JsonCompareMode.STRICT));
    }

    /** Eventos já encerrados continuam na lista: o ADMIN confere o histórico, e só o filtro os tira. */
    @Test
    void listsEventsThatAlreadyEnded() throws Exception {
        String id = createPublishedEvent(mockMvc);
        clock.setTo(Instant.parse("2027-01-01T00:00:00Z"));

        mockMvc.perform(get(ADMIN_EVENTS_PATH).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(id))
                .andExpect(jsonPath("$.items[0].status").value("PUBLISHED"));
    }

    @Test
    void emptyListHasNoNextPage() throws Exception {
        mockMvc.perform(get(ADMIN_EVENTS_PATH).with(admin()))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"items": [], "nextPageToken": null}
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void pagesThroughEveryEventOnceFromTheLatestStart() throws Exception {
        List<String> created = new ArrayList<>();
        for (int day = 1; day <= 5; day++) {
            created.add(createDraft(mockMvc,
                    eventJson("2026-11-0%dT22:00:00Z".formatted(day), "2026-11-0%dT23:00:00Z".formatted(day), 10)));
        }

        List<String> seen = pageThrough(2, null);

        assertThat(seen).containsExactlyElementsOf(created.reversed());
    }

    /** Mesmo início: o id desempata, e nenhum evento se repete nem some entre as páginas. */
    @Test
    void eventsStartingAtTheSameTimeAreNeitherRepeatedNorSkipped() throws Exception {
        List<String> created = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            created.add(createDraft(mockMvc, eventJson()));
        }

        List<String> seen = pageThrough(1, null);

        assertThat(seen).containsExactlyInAnyOrderElementsOf(created).doesNotHaveDuplicates();
    }

    /** Publicar um evento no meio da leitura não o faz pular nem repetir: a ordem não depende do estado. */
    @Test
    void changingAnEventBetweenPagesDoesNotMakeItJumpOrRepeat() throws Exception {
        List<String> created = new ArrayList<>();
        for (int day = 1; day <= 4; day++) {
            created.add(createDraft(mockMvc,
                    eventJson("2026-11-0%dT22:00:00Z".formatted(day), "2026-11-0%dT23:00:00Z".formatted(day), 10)));
        }
        String body = page(2, null, null).andReturn().getResponse().getContentAsString();
        mockMvc.perform(post(adminEventPath(created.get(0)) + ":publish").with(admin())).andExpect(status().isOk());
        mockMvc.perform(post(adminEventPath(created.get(2)) + ":cancel").with(admin())).andExpect(status().isOk());

        String token = JsonPath.read(body, "$.nextPageToken");

        var next = mockMvc.perform(get(ADMIN_EVENTS_PATH).param("maxPageSize", "2")
                        .param("pageToken", token).with(admin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<String> ids = JsonPath.read(next, "$.items[*].id");
        assertThat(ids).containsExactly(created.get(1), created.get(0));
    }

    /** A página cheia só aponta para outra quando ainda há eventos: sem página vazia no fim. */
    @Test
    void exactlyFullLastPageHasNoNextPage() throws Exception {
        insertEvents(2, "DRAFT");

        mockMvc.perform(get(ADMIN_EVENTS_PATH).param("maxPageSize", "2").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.nextPageToken").value(nullValue()));
    }

    @Test
    void oneMoreEventThanThePageHoldsGivesANextPageWithJustThatEvent() throws Exception {
        insertEvents(3, "DRAFT");

        mockMvc.perform(get(ADMIN_EVENTS_PATH).param("maxPageSize", "2").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.nextPageToken").isString());
    }

    @Test
    void defaultPageHasTwentyEvents() throws Exception {
        insertEvents(DEFAULT_PAGE_SIZE + 1, "DRAFT");

        var firstPage = mockMvc.perform(get(ADMIN_EVENTS_PATH).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(DEFAULT_PAGE_SIZE))
                .andExpect(jsonPath("$.nextPageToken").isString())
                .andReturn().getResponse().getContentAsString();

        String token = JsonPath.read(firstPage, "$.nextPageToken");

        mockMvc.perform(get(ADMIN_EVENTS_PATH).param("pageToken", token).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.nextPageToken").value(nullValue()));
    }

    @Test
    void largestPageHasFiftyEvents() throws Exception {
        insertEvents(MAX_PAGE_SIZE + 1, "DRAFT");

        mockMvc.perform(get(ADMIN_EVENTS_PATH).param("maxPageSize", "" + MAX_PAGE_SIZE).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(MAX_PAGE_SIZE))
                .andExpect(jsonPath("$.nextPageToken").isString());
    }

    @Test
    void smallestPageHasOneEvent() throws Exception {
        insertEvents(2, "DRAFT");

        mockMvc.perform(get(ADMIN_EVENTS_PATH).param("maxPageSize", "1").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.nextPageToken").isString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "51", "abc", "1.5", "99999999999", "", " "})
    void invalidPageSizeIsABadRequest(String maxPageSize) throws Exception {
        mockMvc.perform(get(ADMIN_EVENTS_PATH).param("maxPageSize", maxPageSize).with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().json("""
                        {"detail": "%s"}
                        """.formatted(PAGE_SIZE_DETAIL)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "não-é-token", "AAAA", "' OR '1'='1",
            "Kzk5OTk5OTk5OS0xMi0zMVQyMzo1OTo1OVogMDE5NjZjNGUtN2QxYS03YzNlLTliNWYtM2YyYTFjMGQ5ZThi"})
    void invalidPageTokenIsABadRequest(String pageToken) throws Exception {
        mockMvc.perform(get(ADMIN_EVENTS_PATH).param("pageToken", pageToken).with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().json("""
                        {"detail": "pageToken is invalid"}
                        """));
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "PUBLISHED", "CANCELLED"})
    void statusFilterKeepsOnlyThatStatus(String wanted) throws Exception {
        createDraft(mockMvc, eventJson());
        createPublishedEvent(mockMvc);
        String cancelled = createPublishedEvent(mockMvc);
        mockMvc.perform(post(adminEventPath(cancelled) + ":cancel").with(admin())).andExpect(status().isOk());

        mockMvc.perform(get(ADMIN_EVENTS_PATH).param("status", wanted).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].status").value(wanted))
                .andExpect(jsonPath("$.nextPageToken").value(nullValue()));
    }

    @Test
    void statusFilterWithNoMatchIsAnEmptyList() throws Exception {
        createDraft(mockMvc, eventJson());

        mockMvc.perform(get(ADMIN_EVENTS_PATH).param("status", "CANCELLED").with(admin()))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"items": [], "nextPageToken": null}
                        """, JsonCompareMode.STRICT));
    }

    /** O filtro vale em todas as páginas, e a paginação conta só os eventos que passam por ele. */
    @Test
    void statusFilterAppliesToEveryPage() throws Exception {
        List<String> drafts = new ArrayList<>();
        for (int day = 1; day <= 3; day++) {
            drafts.add(createDraft(mockMvc,
                    eventJson("2026-11-0%dT22:00:00Z".formatted(day), "2026-11-0%dT23:00:00Z".formatted(day), 10)));
            createPublishedEvent(mockMvc,
                    eventJson("2026-12-0%dT22:00:00Z".formatted(day), "2026-12-0%dT23:00:00Z".formatted(day), 10));
        }

        List<String> seen = pageThrough(2, "DRAFT");

        assertThat(seen).containsExactlyElementsOf(drafts.reversed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "draft", "Draft", "ENDED", "UNDERWAY", "DRAFT,PUBLISHED", "DRAFT PUBLISHED",
            "' OR '1'='1", "DRAFT\u0000"})
    void invalidStatusIsABadRequest(String statusFilter) throws Exception {
        createDraft(mockMvc, eventJson());

        mockMvc.perform(get(ADMIN_EVENTS_PATH).param("status", statusFilter).with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().json("""
                        {"detail": "status must be one of DRAFT, PUBLISHED, CANCELLED"}
                        """));
    }

    @Test
    void repeatedStatusIsABadRequest() throws Exception {
        mockMvc.perform(get(ADMIN_EVENTS_PATH).param("status", "DRAFT", "PUBLISHED").with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                        {"detail": "status must be one of DRAFT, PUBLISHED, CANCELLED"}
                        """));
    }

    /** Usuário comum e outro papel qualquer recebem 403 sem nenhum dado de evento, rascunho inclusive. */
    @ParameterizedTest
    @ValueSource(strings = {"user", "moderator"})
    void withoutTheAdminRoleTheListIsForbiddenAndShowsNothing(String who) throws Exception {
        createDraft(mockMvc, eventJson());

        var caller = "user".equals(who)
                ? user("ana")
                : jwt().jwt(token -> token.claim("oid", "oid-moderator"))
                        .authorities(new SimpleGrantedAuthority("ROLE_MODERATOR"));
        var body = mockMvc.perform(get(ADMIN_EVENTS_PATH).with(caller))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("Noite de jogos", "registrationCount", "items");
    }

    @Test
    void anonymousIsUnauthorized() throws Exception {
        mockMvc.perform(get(ADMIN_EVENTS_PATH)).andExpect(status().isUnauthorized());
    }

    /** O filtro e a página não são caminho para o usuário comum: a rota inteira exige o papel. */
    @Test
    void userWithFilterAndPageParametersIsStillForbidden() throws Exception {
        mockMvc.perform(get(ADMIN_EVENTS_PATH).param("status", "DRAFT").param("maxPageSize", "1").with(user("ana")))
                .andExpect(status().isForbidden());
    }

    private List<String> pageThrough(int pageSize, String statusFilter) throws Exception {
        List<String> seen = new ArrayList<>();
        String token = null;
        int pages = 0;
        do {
            pages++;
            assertThat(pages).as("pages fetched; a keyset that repeats items would loop forever")
                    .isLessThanOrEqualTo(MAX_PAGES_TO_FOLLOW);
            String body = page(pageSize, statusFilter, token).andReturn().getResponse().getContentAsString();
            List<String> ids = JsonPath.read(body, "$.items[*].id");
            assertThat(ids).hasSizeLessThanOrEqualTo(pageSize);
            seen.addAll(ids);
            token = JsonPath.read(body, "$.nextPageToken");
        } while (token != null);
        return seen;
    }

    private ResultActions page(int pageSize, String statusFilter, String token) throws Exception {
        MockHttpServletRequestBuilder request = get(ADMIN_EVENTS_PATH)
                .param("maxPageSize", Integer.toString(pageSize))
                .with(admin());
        if (statusFilter != null) {
            request.param("status", statusFilter);
        }
        if (token != null) {
            request.param("pageToken", token);
        }
        return mockMvc.perform(request).andExpect(status().isOk());
    }

    /** Eventos em lote direto no banco, para os limites de página: cada um começa uma hora depois do anterior. */
    private void insertEvents(int count, String statusOfAll) {
        jdbcClient.sql("""
                insert into event (title, description, starts_at, ends_at, capacity, status, created_at)
                select 'Noite ' || n, 'Jogos de tabuleiro em dupla.',
                       timestamptz '2026-11-01 00:00:00+00' + n * interval '1 hour',
                       timestamptz '2026-11-01 00:30:00+00' + n * interval '1 hour',
                       10, ?, timestamptz '2026-10-06 12:00:00+00'
                  from generate_series(1, ?) as n
                """)
                .param(statusOfAll)
                .param(count)
                .update();
    }

    private static String item(String id, String startsAt, String endsAt, String status, int registrations) {
        return """
                {"id": "%s", "title": "Noite de jogos", "description": "Jogos de tabuleiro em dupla.",
                 "startsAt": "%s", "endsAt": "%s", "capacity": 10, "status": "%s", "registrationCount": %d}
                """.formatted(id, startsAt, endsAt, status, registrations);
    }

}
