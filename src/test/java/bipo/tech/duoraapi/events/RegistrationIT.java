package bipo.tech.duoraapi.events;

import static bipo.tech.duoraapi.events.EventFixtures.MY_REGISTRATIONS_PATH;
import static bipo.tech.duoraapi.events.EventFixtures.admin;
import static bipo.tech.duoraapi.events.EventFixtures.adminEventPath;
import static bipo.tech.duoraapi.events.EventFixtures.completeProfile;
import static bipo.tech.duoraapi.events.EventFixtures.createDraft;
import static bipo.tech.duoraapi.events.EventFixtures.createPublishedEvent;
import static bipo.tech.duoraapi.events.EventFixtures.eventJson;
import static bipo.tech.duoraapi.events.EventFixtures.randomId;
import static bipo.tech.duoraapi.events.EventFixtures.registrationPath;
import static bipo.tech.duoraapi.events.EventFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

import com.jayway.jsonpath.JsonPath;

import bipo.tech.duoraapi.TestClockConfiguration;
import bipo.tech.duoraapi.TestClockConfiguration.TestClock;
import bipo.tech.duoraapi.TestcontainersConfiguration;

/**
 * Inscrição em eventos (docs/adr/0016): perfil completo e 18+, uma por pessoa e evento, idempotente,
 * capacidade respeitada sob concorrência, e cada pessoa só vê as próprias inscrições.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestClockConfiguration.class})
class RegistrationIT {

    private static final String NOW = "2026-10-06T12:00:00Z";
    private static final int CONCURRENT_PEOPLE = 10;
    private static final int SMALL_CAPACITY = 3;
    private static final int CONCURRENT_REPEATS = 5;

    /** Cursor bem formado com o ano +999999999, que o timestamptz não guarda. */
    private static final String YEAR_BEYOND_TIMESTAMPTZ_TOKEN =
            "Kzk5OTk5OTk5OS0xMi0zMVQyMzo1OTo1OVogMDE5NjZjNGUtN2QxYS03YzNlLTliNWYtM2YyYTFjMGQ5ZThi";

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
    void registeringCreatesTheRegistration() throws Exception {
        String eventId = createPublishedEvent(mockMvc);
        completeProfile(mockMvc, ana());

        register(ana(), eventId)
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, registrationPath(eventId)))
                .andExpect(content().json(registration(eventId, NOW), JsonCompareMode.STRICT));

        assertThat(registrationsOf(eventId)).isEqualTo(1);
    }

    /** Repetir a inscrição devolve a mesma, com a data da primeira: nada é gravado de novo. */
    @Test
    void registeringAgainAnswersTheSameRegistration() throws Exception {
        String eventId = createPublishedEvent(mockMvc);
        completeProfile(mockMvc, ana());
        register(ana(), eventId).andExpect(status().isCreated());
        clock.advance(Duration.ofHours(1));

        register(ana(), eventId)
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andExpect(content().json(registration(eventId, NOW), JsonCompareMode.STRICT));

        assertThat(registrationsOf(eventId)).isEqualTo(1);
    }

    @Test
    void readsTheOwnRegistration() throws Exception {
        String eventId = createPublishedEvent(mockMvc);
        completeProfile(mockMvc, ana());
        register(ana(), eventId).andExpect(status().isCreated());

        mockMvc.perform(get(registrationPath(eventId)).with(ana()))
                .andExpect(status().isOk())
                .andExpect(content().json(registration(eventId, NOW), JsonCompareMode.STRICT));
    }

    @Test
    void readingARegistrationThatDoesNotExistIsNotFound() throws Exception {
        String eventId = createPublishedEvent(mockMvc);

        mockMvc.perform(get(registrationPath(eventId)).with(ana()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().json("""
                        {"detail": "registration not found"}
                        """));
    }

    @Test
    void emptyProfileCannotRegister() throws Exception {
        String eventId = createPublishedEvent(mockMvc);

        register(ana(), eventId)
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().json("""
                        {"title": "Forbidden", "status": 403,
                         "detail": "complete your profile (name, birth date and region) before registering"}
                        """));

        assertThat(registrationsOf(eventId)).isZero();
    }

    @Test
    void profileWithoutRegionCannotRegister() throws Exception {
        String eventId = createPublishedEvent(mockMvc);
        mockMvc.perform(patch("/api/me/profile").with(ana())
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName": "Ana", "birthDate": "1990-05-10"}
                                """))
                .andExpect(status().isOk());

        register(ana(), eventId).andExpect(status().isForbidden());

        assertThat(registrationsOf(eventId)).isZero();
    }

    /**
     * O perfil não aceita data de menor, mas o evento confere a idade de novo no momento da inscrição:
     * a regra não depende de como o dado chegou ao banco.
     */
    @Test
    void minorCannotRegisterEvenWithAFilledProfile() throws Exception {
        String eventId = createPublishedEvent(mockMvc);
        mockMvc.perform(get("/api/me").with(ana())).andExpect(status().isOk());
        jdbcClient.sql("""
                        insert into profile (account_id, display_name, birth_date, region, version)
                        select id, 'Ana', date '2008-10-07', 'BR-SP', 1 from account
                        """)
                .update();

        register(ana(), eventId).andExpect(status().isForbidden());

        assertThat(registrationsOf(eventId)).isZero();
    }

    @Test
    void personTurningEighteenOnTheDayCanRegister() throws Exception {
        String eventId = createPublishedEvent(mockMvc);
        mockMvc.perform(get("/api/me").with(ana())).andExpect(status().isOk());
        jdbcClient.sql("""
                        insert into profile (account_id, display_name, birth_date, region, version)
                        select id, 'Ana', date '2008-10-06', 'BR-SP', 1 from account
                        """)
                .update();

        register(ana(), eventId).andExpect(status().isCreated());
    }

    @Test
    void draftLooksLikeAnEventThatDoesNotExist() throws Exception {
        String draft = createDraft(mockMvc, eventJson());
        completeProfile(mockMvc, ana());

        register(ana(), draft)
                .andExpect(status().isNotFound())
                .andExpect(content().json("""
                        {"detail": "event not found"}
                        """));
        register(ana(), randomId())
                .andExpect(status().isNotFound())
                .andExpect(content().json("""
                        {"detail": "event not found"}
                        """));

        assertThat(registrationsOf(draft)).isZero();
    }

    @Test
    void cancelledEventRefusesRegistration() throws Exception {
        String eventId = createPublishedEvent(mockMvc);
        mockMvc.perform(post(adminEventPath(eventId) + ":cancel").with(admin())).andExpect(status().isOk());
        completeProfile(mockMvc, ana());

        register(ana(), eventId)
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().json("""
                        {"detail": "the event was cancelled"}
                        """));

        assertThat(registrationsOf(eventId)).isZero();
    }

    @Test
    void startedEventRefusesRegistration() throws Exception {
        String eventId = createPublishedEvent(mockMvc);
        completeProfile(mockMvc, ana());
        clock.setTo(Instant.parse(EventFixtures.STARTS_AT));

        register(ana(), eventId)
                .andExpect(status().isConflict())
                .andExpect(content().json("""
                        {"detail": "the event has already started"}
                        """));

        assertThat(registrationsOf(eventId)).isZero();
    }

    @Test
    void lastPlaceIsTakenAndThenTheEventIsFull() throws Exception {
        String eventId = createPublishedEvent(mockMvc, eventJson(EventFixtures.STARTS_AT, EventFixtures.ENDS_AT, 2));
        for (String name : List.of("ana", "bruno", "carla")) {
            completeProfile(mockMvc, user(name));
        }
        register(user("ana"), eventId).andExpect(status().isCreated());

        register(user("bruno"), eventId).andExpect(status().isCreated());
        register(user("carla"), eventId)
                .andExpect(status().isConflict())
                .andExpect(content().json("""
                        {"detail": "the event is full"}
                        """));

        assertThat(registrationsOf(eventId)).isEqualTo(2);
    }

    /** Quem já está inscrito num evento lotado repete a inscrição e recebe a mesma, não o "lotado". */
    @Test
    void registeringAgainInAFullEventAnswersTheSameRegistration() throws Exception {
        String eventId = createPublishedEvent(mockMvc, eventJson(EventFixtures.STARTS_AT, EventFixtures.ENDS_AT, 2));
        completeProfile(mockMvc, user("ana"));
        completeProfile(mockMvc, user("bruno"));
        register(user("ana"), eventId).andExpect(status().isCreated());
        register(user("bruno"), eventId).andExpect(status().isCreated());

        register(user("ana"), eventId).andExpect(status().isOk());
    }

    /**
     * Pessoas diferentes ao mesmo tempo num evento com poucas vagas: exatamente a capacidade entra, e o
     * resto recebe 409. Sem o lock do evento, as contagens simultâneas deixariam passar mais gente.
     */
    @RepeatedTest(3)
    void concurrentRegistrationsNeverExceedTheCapacity() throws Exception {
        String eventId = createPublishedEvent(mockMvc,
                eventJson(EventFixtures.STARTS_AT, EventFixtures.ENDS_AT, SMALL_CAPACITY));
        List<RequestPostProcessor> people = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_PEOPLE; i++) {
            RequestPostProcessor person = user("pessoa-" + i);
            completeProfile(mockMvc, person);
            people.add(person);
        }

        List<Integer> statuses = registerAllAtOnce(eventId, people);

        assertThat(statuses).filteredOn(code -> code == 201).hasSize(SMALL_CAPACITY);
        assertThat(statuses).filteredOn(code -> code == 409).hasSize(CONCURRENT_PEOPLE - SMALL_CAPACITY);
        assertThat(registrationsOf(eventId)).isEqualTo(SMALL_CAPACITY);
    }

    /** A mesma pessoa várias vezes ao mesmo tempo (clique duplo, retry): uma inscrição, criada uma vez só. */
    @RepeatedTest(3)
    void concurrentRepeatedRegistrationsCreateOnlyOne() throws Exception {
        String eventId = createPublishedEvent(mockMvc);
        completeProfile(mockMvc, ana());
        List<RequestPostProcessor> samePerson = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_REPEATS; i++) {
            samePerson.add(ana());
        }

        List<Integer> statuses = registerAllAtOnce(eventId, samePerson);

        assertThat(statuses).containsOnly(200, 201).containsOnlyOnce(201);
        assertThat(registrationsOf(eventId)).isEqualTo(1);
    }

    /**
     * O ADMIN cancela enquanto alguém se inscreve: o cancelamento sempre grava, e a inscrição ou entrou
     * antes (201, e fica guardada) ou viu o evento cancelado (409, nada gravado). Nunca 500.
     */
    @RepeatedTest(3)
    void registrationRacingTheEventCancellationEndsConsistent() throws Exception {
        String eventId = createPublishedEvent(mockMvc);
        completeProfile(mockMvc, ana());
        var start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> registered = executor.submit(registerAfter(start, ana(), eventId));
            Future<Integer> cancelled = executor.submit(() -> {
                start.await();
                return mockMvc.perform(post(adminEventPath(eventId) + ":cancel").with(admin()))
                        .andReturn().getResponse().getStatus();
            });
            start.countDown();
            int registration = registered.get(30, TimeUnit.SECONDS);

            assertThat(cancelled.get(30, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(registration).isIn(201, 409);
            assertThat(registrationsOf(eventId)).isEqualTo(registration == 201 ? 1 : 0);
        }
    }

    /** Com o evento travado por outra transação além do teto, a inscrição desiste com 503, sem gravar. */
    @Test
    void registrationThatWaitsTooLongForTheEventLockIsRefused() throws Exception {
        String eventId = createPublishedEvent(mockMvc);
        completeProfile(mockMvc, ana());
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<?> holder = executor.submit(() -> transactionTemplate.executeWithoutResult(transaction -> {
                jdbcClient.sql("select id from event where id = cast(:id as uuid) for update")
                        .param("id", eventId)
                        .query(String.class)
                        .single();
                locked.countDown();
                awaitQuietly(release);
            }));
            assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();

            register(ana(), eventId)
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"))
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));

            release.countDown();
            holder.get(30, TimeUnit.SECONDS);
        }
        assertThat(registrationsOf(eventId)).isZero();
    }

    @Test
    void adminSeesHowManyPeopleRegisteredButNotWho() throws Exception {
        String eventId = createPublishedEvent(mockMvc);
        completeProfile(mockMvc, user("ana"));
        completeProfile(mockMvc, user("bruno"));
        register(user("ana"), eventId).andExpect(status().isCreated());
        register(user("bruno"), eventId).andExpect(status().isCreated());

        String body = mockMvc.perform(get(adminEventPath(eventId)).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registrationCount").value(2))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("ana", "bruno", accountIdOf("ana"), accountIdOf("bruno"));
    }

    @Test
    void cancellingTheRegistrationFreesThePlace() throws Exception {
        String eventId = createPublishedEvent(mockMvc);
        completeProfile(mockMvc, ana());
        register(ana(), eventId).andExpect(status().isCreated());

        unregister(ana(), eventId).andExpect(status().isNoContent());

        assertThat(registrationsOf(eventId)).isZero();
        mockMvc.perform(get(registrationPath(eventId)).with(ana())).andExpect(status().isNotFound());
    }

    /** Cancelar de novo, ou sem nunca ter se inscrito, também é 204: o resultado pedido já vale. */
    @Test
    void cancellingARegistrationThatDoesNotExistSucceeds() throws Exception {
        String eventId = createPublishedEvent(mockMvc);

        unregister(ana(), eventId).andExpect(status().isNoContent());
        unregister(ana(), eventId).andExpect(status().isNoContent());
    }

    @Test
    void registeringAgainAfterCancellingCreatesANewRegistration() throws Exception {
        String eventId = createPublishedEvent(mockMvc);
        completeProfile(mockMvc, ana());
        register(ana(), eventId).andExpect(status().isCreated());
        unregister(ana(), eventId).andExpect(status().isNoContent());
        clock.advance(Duration.ofHours(1));

        register(ana(), eventId)
                .andExpect(status().isCreated())
                .andExpect(content().json(registration(eventId, "2026-10-06T13:00:00Z"), JsonCompareMode.STRICT));
    }

    @Test
    void registrationCannotBeCancelledOnceTheEventStarted() throws Exception {
        String eventId = createPublishedEvent(mockMvc);
        completeProfile(mockMvc, ana());
        register(ana(), eventId).andExpect(status().isCreated());
        clock.setTo(Instant.parse(EventFixtures.STARTS_AT));

        unregister(ana(), eventId)
                .andExpect(status().isConflict())
                .andExpect(content().json("""
                        {"detail": "the event has already started"}
                        """));

        assertThat(registrationsOf(eventId)).isEqualTo(1);
    }

    @Test
    void cancellingARegistrationOfAnUnknownEventIsNotFound() throws Exception {
        String draft = createDraft(mockMvc, eventJson());

        unregister(ana(), draft).andExpect(status().isNotFound());
        unregister(ana(), randomId()).andExpect(status().isNotFound());
    }

    /** Bruno não lê nem apaga a inscrição de Ana: cada rota só alcança a do próprio usuário. */
    @Test
    void anotherUserNeitherSeesNorCancelsTheRegistration() throws Exception {
        String eventId = createPublishedEvent(mockMvc);
        completeProfile(mockMvc, ana());
        register(ana(), eventId).andExpect(status().isCreated());

        unregister(bruno(), eventId).andExpect(status().isNoContent());
        String brunoView = mockMvc.perform(get(registrationPath(eventId)).with(bruno()))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        String brunoList = mockMvc.perform(get(MY_REGISTRATIONS_PATH).with(bruno()))
                .andExpect(content().json("""
                        {"items": [], "nextPageToken": null}
                        """, JsonCompareMode.STRICT))
                .andReturn().getResponse().getContentAsString();

        assertThat(brunoView + brunoList).doesNotContain(accountIdOf("ana"), NOW);
        assertThat(registrationsOf(eventId)).isEqualTo(1);
    }

    @Test
    void listsOwnRegistrationsOfEventsThatHaveNotEndedInStartOrder() throws Exception {
        completeProfile(mockMvc, ana());
        completeProfile(mockMvc, bruno());
        String later = createPublishedEvent(mockMvc, eventJson("2026-11-02T22:00:00Z", "2026-11-03T01:00:00Z", 10));
        String sooner = createPublishedEvent(mockMvc, eventJson("2026-11-01T22:00:00Z", "2026-11-02T01:00:00Z", 10));
        String running = createPublishedEvent(mockMvc, eventJson("2026-10-06T13:00:00Z", "2026-10-06T16:00:00Z", 10));
        String ended = createPublishedEvent(mockMvc, eventJson("2026-10-06T12:30:00Z", "2026-10-06T13:30:00Z", 10));
        String onlyBruno = createPublishedEvent(mockMvc);
        for (String eventId : List.of(later, sooner, running, ended)) {
            register(ana(), eventId).andExpect(status().isCreated());
        }
        register(bruno(), onlyBruno).andExpect(status().isCreated());
        mockMvc.perform(post(adminEventPath(sooner) + ":cancel").with(admin())).andExpect(status().isOk());
        clock.setTo(Instant.parse("2026-10-06T14:00:00Z"));

        mockMvc.perform(get(MY_REGISTRATIONS_PATH).with(ana()))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"items": [%s, %s, %s], "nextPageToken": null}
                        """.formatted(
                        myRegistration(running, "2026-10-06T13:00:00Z", "2026-10-06T16:00:00Z", "PUBLISHED"),
                        myRegistration(sooner, "2026-11-01T22:00:00Z", "2026-11-02T01:00:00Z", "CANCELLED"),
                        myRegistration(later, "2026-11-02T22:00:00Z", "2026-11-03T01:00:00Z", "PUBLISHED")),
                        JsonCompareMode.STRICT));
    }

    @Test
    void pagesThroughOwnRegistrations() throws Exception {
        completeProfile(mockMvc, ana());
        List<String> created = new ArrayList<>();
        for (int day = 1; day <= 3; day++) {
            String eventId = createPublishedEvent(mockMvc,
                    eventJson("2026-11-0%dT22:00:00Z".formatted(day), "2026-11-0%dT23:00:00Z".formatted(day), 10));
            register(ana(), eventId).andExpect(status().isCreated());
            created.add(eventId);
        }

        String first = mockMvc.perform(get(MY_REGISTRATIONS_PATH).param("pageSize", "2").with(ana()))
                .andExpect(jsonPath("$.items[0].eventId").value(created.get(0)))
                .andExpect(jsonPath("$.items[1].eventId").value(created.get(1)))
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(first, "$.nextPageToken");

        mockMvc.perform(get(MY_REGISTRATIONS_PATH).param("pageSize", "2").param("pageToken", token).with(ana()))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].eventId").value(created.get(2)))
                .andExpect(jsonPath("$.nextPageToken").value(nullValue()));
    }

    @Test
    void invalidPageOfOwnRegistrationsIsABadRequest() throws Exception {
        mockMvc.perform(get(MY_REGISTRATIONS_PATH).param("pageSize", "51").with(ana()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(MY_REGISTRATIONS_PATH).param("pageToken", "AAAA").with(ana()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(MY_REGISTRATIONS_PATH).param("pageToken", YEAR_BEYOND_TIMESTAMPTZ_TOKEN).with(ana()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void webSessionWithoutCsrfTokenCannotRegisterNorCancel() throws Exception {
        String eventId = createPublishedEvent(mockMvc);
        completeProfile(mockMvc, ana());
        mockMvc.perform(put(registrationPath(eventId)).with(anaWebSession())).andExpect(status().isForbidden());
        assertThat(registrationsOf(eventId)).isZero();
        register(ana(), eventId).andExpect(status().isCreated());

        mockMvc.perform(delete(registrationPath(eventId)).with(anaWebSession())).andExpect(status().isForbidden());

        assertThat(registrationsOf(eventId)).isEqualTo(1);
    }

    @Test
    void webSessionWithCsrfTokenRegisters() throws Exception {
        String eventId = createPublishedEvent(mockMvc);
        completeProfile(mockMvc, ana());

        mockMvc.perform(put(registrationPath(eventId)).with(anaWebSession()).with(csrf()))
                .andExpect(status().isCreated());

        assertThat(registrationsOf(eventId)).isEqualTo(1);
    }

    @Test
    void anonymousCannotRegisterNorList() throws Exception {
        String eventId = createPublishedEvent(mockMvc);

        mockMvc.perform(put(registrationPath(eventId)).with(csrf())).andExpect(status().isUnauthorized());
        mockMvc.perform(delete(registrationPath(eventId)).with(csrf())).andExpect(status().isUnauthorized());
        mockMvc.perform(get(MY_REGISTRATIONS_PATH)).andExpect(status().isUnauthorized());

        assertThat(registrationsOf(eventId)).isZero();
    }

    @Test
    void invalidEventIdIsABadRequest() throws Exception {
        mockMvc.perform(put(EventFixtures.EVENTS_PATH + "/not-a-uuid/registration").with(ana()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    private List<Integer> registerAllAtOnce(String eventId, List<RequestPostProcessor> people) throws Exception {
        var start = new CountDownLatch(1);
        var futures = new ArrayList<Future<Integer>>();
        try (var executor = Executors.newFixedThreadPool(people.size())) {
            for (RequestPostProcessor person : people) {
                futures.add(executor.submit(registerAfter(start, person, eventId)));
            }
            start.countDown();
            var statuses = new ArrayList<Integer>();
            for (var future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
            return statuses;
        }
    }

    private Callable<Integer> registerAfter(CountDownLatch start, RequestPostProcessor person, String eventId) {
        return () -> {
            start.await();
            return register(person, eventId).andReturn().getResponse().getStatus();
        };
    }

    private ResultActions register(RequestPostProcessor person, String eventId) throws Exception {
        return mockMvc.perform(put(registrationPath(eventId)).with(person));
    }

    private ResultActions unregister(RequestPostProcessor person, String eventId) throws Exception {
        return mockMvc.perform(delete(registrationPath(eventId)).with(person));
    }

    private long registrationsOf(String eventId) {
        return jdbcClient.sql("select count(*) from registration where event_id = cast(:id as uuid)")
                .param("id", eventId)
                .query(Long.class).single();
    }

    private String accountIdOf(String name) {
        return jdbcClient.sql("select id::text from account where subject = :subject")
                .param("subject", "oid-" + name)
                .query(String.class).single();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String registration(String eventId, String registeredAt) {
        return """
                {"eventId": "%s", "registeredAt": "%s"}
                """.formatted(eventId, registeredAt);
    }

    private static String myRegistration(String eventId, String startsAt, String endsAt, String eventStatus) {
        return """
                {"eventId": "%s", "title": "Noite de jogos", "startsAt": "%s", "endsAt": "%s",
                 "eventStatus": "%s", "registeredAt": "%s"}
                """.formatted(eventId, startsAt, endsAt, eventStatus, NOW);
    }

    private static RequestPostProcessor ana() {
        return user("ana");
    }

    private static RequestPostProcessor bruno() {
        return user("bruno");
    }

    private static RequestPostProcessor anaWebSession() {
        return oidcLogin().idToken(token -> token.issuer("https://tenant-id.ciamlogin.example/tenant-id/v2.0")
                .claim("oid", "oid-ana"));
    }

}
