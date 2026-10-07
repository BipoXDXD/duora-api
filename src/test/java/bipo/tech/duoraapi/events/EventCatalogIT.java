package bipo.tech.duoraapi.events;

import static bipo.tech.duoraapi.events.EventFixtures.EVENTS_PATH;
import static bipo.tech.duoraapi.events.EventFixtures.admin;
import static bipo.tech.duoraapi.events.EventFixtures.adminEventPath;
import static bipo.tech.duoraapi.events.EventFixtures.createDraft;
import static bipo.tech.duoraapi.events.EventFixtures.createPublishedEvent;
import static bipo.tech.duoraapi.events.EventFixtures.eventJson;
import static bipo.tech.duoraapi.events.EventFixtures.eventPath;
import static bipo.tech.duoraapi.events.EventFixtures.randomId;
import static bipo.tech.duoraapi.events.EventFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
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
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

import bipo.tech.duoraapi.TestClockConfiguration;
import bipo.tech.duoraapi.TestClockConfiguration.TestClock;
import bipo.tech.duoraapi.TestcontainersConfiguration;

/**
 * Eventos para quem está logado (docs/adr/0016): a lista dos publicados que ainda não começaram, por
 * keyset, e a leitura de um evento. Rascunho não existe para o usuário, e nenhuma resposta traz
 * capacidade, contagem ou inscritos.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestClockConfiguration.class})
class EventCatalogIT {

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
    void listsOnlyPublishedEventsThatHaveNotStartedInStartOrder() throws Exception {
        String later = createPublishedEvent(mockMvc, eventJson("2026-11-02T22:00:00Z", "2026-11-03T01:00:00Z", 10));
        String sooner = createPublishedEvent(mockMvc, eventJson("2026-11-01T22:00:00Z", "2026-11-02T01:00:00Z", 10));
        createPublishedEvent(mockMvc, eventJson("2026-10-06T13:00:00Z", "2026-10-06T15:00:00Z", 10));
        createDraft(mockMvc, eventJson());
        String cancelled = createPublishedEvent(mockMvc);
        mockMvc.perform(post(adminEventPath(cancelled) + ":cancel").with(admin())).andExpect(status().isOk());
        clock.setTo(Instant.parse("2026-10-06T13:00:00Z"));

        mockMvc.perform(get(EVENTS_PATH).with(user("ana")))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"items": [%s, %s], "nextPageToken": null}
                        """.formatted(
                        event(sooner, "2026-11-01T22:00:00Z", "2026-11-02T01:00:00Z", "PUBLISHED"),
                        event(later, "2026-11-02T22:00:00Z", "2026-11-03T01:00:00Z", "PUBLISHED")),
                        JsonCompareMode.STRICT));
    }

    @Test
    void emptyListHasNoNextPage() throws Exception {
        mockMvc.perform(get(EVENTS_PATH).with(user("ana")))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"items": [], "nextPageToken": null}
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void pagesThroughEveryEventOnceInOrder() throws Exception {
        List<String> created = new ArrayList<>();
        for (int day = 1; day <= 5; day++) {
            created.add(createPublishedEvent(mockMvc,
                    eventJson("2026-11-0%dT22:00:00Z".formatted(day), "2026-11-0%dT23:00:00Z".formatted(day), 10)));
        }

        List<String> seen = pageThrough(2);

        assertThat(seen).containsExactlyElementsOf(created);
    }

    /** Mesmo início: o id desempata, e nenhum evento se repete nem some entre as páginas. */
    @Test
    void eventsStartingAtTheSameTimeAreNeitherRepeatedNorSkipped() throws Exception {
        List<String> created = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            created.add(createPublishedEvent(mockMvc));
        }

        List<String> seen = pageThrough(1);

        assertThat(seen).containsExactlyInAnyOrderElementsOf(created).doesNotHaveDuplicates();
    }

    /** A página cheia só aponta para outra quando ainda há eventos: sem página vazia no fim. */
    @Test
    void exactlyFullLastPageHasNoNextPage() throws Exception {
        createPublishedEvent(mockMvc);
        createPublishedEvent(mockMvc);

        mockMvc.perform(get(EVENTS_PATH).param("maxPageSize", "2").with(user("ana")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.nextPageToken").value(nullValue()));
    }

    @Test
    void defaultPageHasTenEvents() throws Exception {
        for (int i = 0; i < 11; i++) {
            createPublishedEvent(mockMvc);
        }

        mockMvc.perform(get(EVENTS_PATH).with(user("ana")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(10))
                .andExpect(jsonPath("$.nextPageToken").isString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "51", "abc", "1.5", "99999999999", "", " "})
    void invalidPageSizeIsABadRequest(String maxPageSize) throws Exception {
        mockMvc.perform(get(EVENTS_PATH).param("maxPageSize", maxPageSize).with(user("ana")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().json("""
                        {"detail": "maxPageSize must be between 1 and 50"}
                        """));
    }

    /** Os dois últimos são cursores bem formados com anos que o timestamptz não guarda (+999999999, +200000). */
    @ParameterizedTest
    @ValueSource(strings = {"", "não-é-token", "AAAA", "' OR '1'='1",
            "Kzk5OTk5OTk5OS0xMi0zMVQyMzo1OTo1OVogMDE5NjZjNGUtN2QxYS03YzNlLTliNWYtM2YyYTFjMGQ5ZThi",
            "KzIwMDAwMC0wMS0wMVQwMDowMDowMFogMDE5NjZjNGUtN2QxYS03YzNlLTliNWYtM2YyYTFjMGQ5ZThi"})
    void invalidPageTokenIsABadRequest(String pageToken) throws Exception {
        mockMvc.perform(get(EVENTS_PATH).param("pageToken", pageToken).with(user("ana")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().json("""
                        {"detail": "pageToken is invalid"}
                        """));
    }

    @Test
    void readsAPublishedEvent() throws Exception {
        String id = createPublishedEvent(mockMvc);

        mockMvc.perform(get(eventPath(id)).with(user("ana")))
                .andExpect(status().isOk())
                .andExpect(content().json(event(id, EventFixtures.STARTS_AT, EventFixtures.ENDS_AT, "PUBLISHED"),
                        JsonCompareMode.STRICT));
    }

    /** Quem se inscreveu precisa saber que o evento foi cancelado. */
    @Test
    void readsACancelledEvent() throws Exception {
        String id = createPublishedEvent(mockMvc);
        mockMvc.perform(post(adminEventPath(id) + ":cancel").with(admin())).andExpect(status().isOk());

        mockMvc.perform(get(eventPath(id)).with(user("ana")))
                .andExpect(status().isOk())
                .andExpect(content().json(event(id, EventFixtures.STARTS_AT, EventFixtures.ENDS_AT, "CANCELLED"),
                        JsonCompareMode.STRICT));
    }

    @Test
    void startedEventCanStillBeRead() throws Exception {
        String id = createPublishedEvent(mockMvc);
        clock.setTo(Instant.parse(EventFixtures.STARTS_AT));

        mockMvc.perform(get(eventPath(id)).with(user("ana"))).andExpect(status().isOk());
    }

    /** Rascunho e evento inexistente respondem igual: o usuário não descobre que o rascunho existe. */
    @Test
    void draftLooksExactlyLikeAnEventThatDoesNotExist() throws Exception {
        String draft = createDraft(mockMvc, eventJson());

        String draftBody = mockMvc.perform(get(eventPath(draft)).with(user("ana")))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andReturn().getResponse().getContentAsString();
        String missing = randomId();
        String missingBody = mockMvc.perform(get(eventPath(missing)).with(user("ana")))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        assertThat(draftBody.replace(draft, "{id}")).isEqualTo(missingBody.replace(missing, "{id}"));
        assertThat(draftBody).doesNotContain("Noite de jogos");
    }

    @Test
    void invalidEventIdIsABadRequest() throws Exception {
        mockMvc.perform(get(EVENTS_PATH + "/not-a-uuid").with(user("ana")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void anonymousCannotListOrRead() throws Exception {
        String id = createPublishedEvent(mockMvc);

        mockMvc.perform(get(EVENTS_PATH)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(eventPath(id))).andExpect(status().isUnauthorized());
    }

    @Test
    void eventStaysListedUntilJustBeforeItStarts() throws Exception {
        String id = createPublishedEvent(mockMvc);
        clock.setTo(Instant.parse("2026-11-01T21:59:59.999999Z"));

        mockMvc.perform(get(EVENTS_PATH).with(user("ana")))
                .andExpect(jsonPath("$.items[0].id").value(id));

        clock.advance(Duration.ofNanos(1000));
        mockMvc.perform(get(EVENTS_PATH).with(user("ana")))
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    private List<String> pageThrough(int pageSize) throws Exception {
        List<String> seen = new ArrayList<>();
        String token = null;
        int pages = 0;
        do {
            pages++;
            assertThat(pages).as("pages fetched; a keyset that repeats items would loop forever").isLessThanOrEqualTo(
                    MAX_PAGES_TO_FOLLOW);
            var request = get(EVENTS_PATH).param("maxPageSize", Integer.toString(pageSize)).with(user("ana"));
            if (token != null) {
                request.param("pageToken", token);
            }
            String body = mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            List<String> ids = JsonPath.read(body, "$.items[*].id");
            assertThat(ids).hasSizeLessThanOrEqualTo(pageSize);
            seen.addAll(ids);
            token = JsonPath.read(body, "$.nextPageToken");
        } while (token != null);
        return seen;
    }

    private static String event(String id, String startsAt, String endsAt, String status) {
        return """
                {"id": "%s", "title": "Noite de jogos", "description": "Jogos de tabuleiro em dupla.",
                 "startsAt": "%s", "endsAt": "%s", "status": "%s"}
                """.formatted(id, startsAt, endsAt, status);
    }

}
