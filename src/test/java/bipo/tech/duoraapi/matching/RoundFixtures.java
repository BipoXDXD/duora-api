package bipo.tech.duoraapi.matching;

import static bipo.tech.duoraapi.events.EventFixtures.admin;
import static bipo.tech.duoraapi.events.EventFixtures.createPublishedEvent;
import static bipo.tech.duoraapi.events.EventFixtures.registerWithCompleteProfile;
import static bipo.tech.duoraapi.events.EventFixtures.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import bipo.tech.duoraapi.TestClockConfiguration;
import bipo.tech.duoraapi.TestClockConfiguration.TestClock;
import bipo.tech.duoraapi.events.EventFixtures;

/**
 * O caminho até uma rodada, pela própria API, que os testes de matching, conexões e chat repetem: publicar o
 * evento, inscrever as pessoas com o perfil completo, levar o relógio ao início e sortear. Cada passo é o do
 * usuário de verdade, então o teste que usa isto também exercita a inscrição e o sorteio.
 */
public final class RoundFixtures {

    public static final Instant STARTS_AT = Instant.parse(EventFixtures.STARTS_AT);

    private RoundFixtures() {
    }

    /**
     * Publica um evento, inscreve as pessoas com o perfil completo e leva o relógio ao início, sem sortear.
     * Uma pessoa que já tem perfil (de um evento anterior do mesmo teste) só se inscreve.
     */
    public static String underwayEventWith(MockMvc mockMvc, JdbcClient jdbcClient, TestClock clock, String... names)
            throws Exception {
        clock.setTo(TestClockConfiguration.NOW);
        String eventId = createPublishedEvent(mockMvc);
        for (String name : names) {
            registerOnce(mockMvc, jdbcClient, name, eventId);
        }
        clock.setTo(STARTS_AT);
        return eventId;
    }

    /** O mesmo evento em andamento, já com a rodada 1 sorteada. */
    public static String pairedInRoundOne(MockMvc mockMvc, JdbcClient jdbcClient, TestClock clock, String... names)
            throws Exception {
        String eventId = underwayEventWith(mockMvc, jdbcClient, clock, names);
        startRound(mockMvc, eventId, 1);
        return eventId;
    }

    /** O ADMIN sorteia a rodada, e a resposta é a da primeira vez (201). */
    public static void startRound(MockMvc mockMvc, String eventId, int number) throws Exception {
        mockMvc.perform(put("/api/admin/events/" + eventId + "/rounds/" + number).with(admin()))
                .andExpect(status().isCreated());
    }

    /** O perfil completo só se cria uma vez por pessoa; depois basta se inscrever. */
    private static void registerOnce(MockMvc mockMvc, JdbcClient jdbcClient, String name, String eventId)
            throws Exception {
        boolean hasProfile = jdbcClient.sql("""
                        select exists (select 1 from profile p join account a on a.id = p.account_id
                                        where a.subject = :subject)
                        """)
                .param("subject", "oid-" + name).query(Boolean.class).single();
        if (hasProfile) {
            mockMvc.perform(put(EventFixtures.registrationPath(eventId)).with(user(name)))
                    .andExpect(status().isCreated());
        } else {
            registerWithCompleteProfile(mockMvc, user(name), eventId);
        }
    }

}
