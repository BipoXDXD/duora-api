package bipo.tech.duoraapi.events;

import static bipo.tech.duoraapi.AccountFixtures.accountIdOf;
import static bipo.tech.duoraapi.RateLimitTestSupport.bucketKeysOf;
import static bipo.tech.duoraapi.RateLimitTestSupport.expectRejectedByTheLimit;
import static bipo.tech.duoraapi.RateLimitTestSupport.expectUnavailableBecauseTheLimitCannotBeCounted;
import static bipo.tech.duoraapi.RateLimitTestSupport.whileTheLimitCannotBeCounted;
import static bipo.tech.duoraapi.events.EventFixtures.completeProfile;
import static bipo.tech.duoraapi.events.EventFixtures.createPublishedEvent;
import static bipo.tech.duoraapi.events.EventFixtures.randomId;
import static bipo.tech.duoraapi.events.EventFixtures.registrationPath;
import static bipo.tech.duoraapi.events.EventFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import bipo.tech.duoraapi.TestClockConfiguration;
import bipo.tech.duoraapi.TestcontainersConfiguration;

/**
 * Limite por conta das inscrições e dos cancelamentos (docs/adr/0016, "Rate limit"): cada chamada trava a
 * linha do evento, então repetir sem parar disputa o lock com todo mundo. A capacidade é reduzida a 3 por
 * hora para chegar logo ao fim; o valor de produção (60 por hora) vem de application.properties.
 */
@SpringBootTest(properties = {
        "duora.events.registration-rate-limit.capacity=" + RegistrationRateLimitIT.CAPACITY,
        "duora.events.registration-rate-limit.period=PT1H"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestClockConfiguration.class})
class RegistrationRateLimitIT {

    static final int CAPACITY = 3;

    /** Uma ficha de volta a cada 1 h / 3 = 1200 s. */
    private static final String SECONDS_TO_NEXT_CALL = "1200";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void resetState() {
        EventFixtures.cleanDatabase(jdbcClient);
    }

    @Test
    void callsAboveTheLimitAreRejectedWithRetryAfterAndChangeNothing() throws Exception {
        String eventId = createPublishedEvent(mockMvc);
        completeProfile(mockMvc, ana());
        for (int i = 0; i < CAPACITY; i++) {
            register(ana(), eventId).andExpect(status().is2xxSuccessful());
        }

        expectRejectedByTheLimit(register(ana(), eventId),
                SECONDS_TO_NEXT_CALL, registrationPath(eventId));
        unregister(ana(), eventId).andExpect(status().isTooManyRequests());

        assertThat(registrationsOf(eventId)).isEqualTo(1);
    }

    /** O custo é o lock do evento, e não o efeito: a repetição que só devolve a mesma inscrição também gasta. */
    @Test
    void idempotentRepeatsSpendTheLimit() throws Exception {
        String eventId = createPublishedEvent(mockMvc);
        completeProfile(mockMvc, ana());
        register(ana(), eventId).andExpect(status().isCreated());
        for (int i = 1; i < CAPACITY; i++) {
            register(ana(), eventId).andExpect(status().isOk());
        }

        register(ana(), eventId).andExpect(status().isTooManyRequests());
    }

    /** Quem alterna inscrever e cancelar não escapa por usar a outra operação: o saldo é um só. */
    @Test
    void registeringAndCancellingShareOneLimit() throws Exception {
        String eventId = createPublishedEvent(mockMvc);
        completeProfile(mockMvc, ana());
        register(ana(), eventId).andExpect(status().isCreated());
        unregister(ana(), eventId).andExpect(status().isNoContent());
        register(ana(), eventId).andExpect(status().isCreated());

        unregister(ana(), eventId).andExpect(status().isTooManyRequests());

        assertThat(keysOfTheLimit()).hasSize(1);
    }

    /** Evento inexistente também gasta: limita quem tenta adivinhar ids de evento. */
    @Test
    void callsForUnknownEventsSpendTheLimit() throws Exception {
        String unknown = randomId();
        for (int i = 0; i < CAPACITY; i++) {
            register(ana(), unknown).andExpect(status().isNotFound());
        }

        register(ana(), unknown).andExpect(status().isTooManyRequests());
        unregister(ana(), unknown).andExpect(status().isTooManyRequests());
    }

    /** Id que não é UUID falha antes do controller: não toca o banco, então não gasta o limite. */
    @Test
    void malformedIdsDoNotSpendTheLimit() throws Exception {
        for (int i = 0; i <= CAPACITY; i++) {
            mockMvc.perform(put("/api/events/not-a-uuid/registration").with(ana()))
                    .andExpect(status().isBadRequest());
        }

        register(ana(), randomId()).andExpect(status().isNotFound());
    }

    @Test
    void theLimitIsCountedForEachAccountSeparately() throws Exception {
        String eventId = createPublishedEvent(mockMvc);
        completeProfile(mockMvc, ana());
        completeProfile(mockMvc, bruno());
        for (int i = 0; i < CAPACITY; i++) {
            register(ana(), eventId).andExpect(status().is2xxSuccessful());
        }
        register(ana(), eventId).andExpect(status().isTooManyRequests());

        register(bruno(), eventId).andExpect(status().isCreated());
        unregister(bruno(), eventId).andExpect(status().isNoContent());
    }

    @Test
    void theBucketLivesUnderTheRegistrationKeyOfTheAccount() throws Exception {
        String eventId = createPublishedEvent(mockMvc);
        completeProfile(mockMvc, ana());
        register(ana(), eventId).andExpect(status().isCreated());

        String accountId = accountIdOf(jdbcClient, "ana");

        assertThat(keysOfTheLimit()).containsExactly("registration:" + accountId);
    }

    /** Falha fechada (docs/adr/0006): sem contar o limite, a inscrição é recusada, e nada é gravado. */
    @Test
    void registrationIsRefusedWithoutWritingWhenTheLimitCannotBeCounted() throws Exception {
        String eventId = createPublishedEvent(mockMvc);
        completeProfile(mockMvc, ana());
        whileTheLimitCannotBeCounted(jdbcClient, () -> expectUnavailableBecauseTheLimitCannotBeCounted(
                register(ana(), eventId),
                registrationPath(eventId)));

        assertThat(registrationsOf(eventId)).isZero();
    }

    @Test
    void cancellationIsRefusedWithoutChangingAnythingWhenTheLimitCannotBeCounted() throws Exception {
        String eventId = createPublishedEvent(mockMvc);
        completeProfile(mockMvc, ana());
        register(ana(), eventId).andExpect(status().isCreated());
        whileTheLimitCannotBeCounted(jdbcClient, () -> expectUnavailableBecauseTheLimitCannotBeCounted(
                unregister(ana(), eventId),
                registrationPath(eventId)));

        assertThat(registrationsOf(eventId)).isEqualTo(1);
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

    private List<String> keysOfTheLimit() {
        return bucketKeysOf(jdbcClient, "registration");
    }

    private static RequestPostProcessor ana() {
        return user("ana");
    }

    private static RequestPostProcessor bruno() {
        return user("bruno");
    }

}
