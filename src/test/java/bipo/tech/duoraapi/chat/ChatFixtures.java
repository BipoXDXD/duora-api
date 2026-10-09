package bipo.tech.duoraapi.chat;

import static bipo.tech.duoraapi.events.EventFixtures.admin;
import static bipo.tech.duoraapi.events.EventFixtures.createPublishedEvent;
import static bipo.tech.duoraapi.events.EventFixtures.registerWithCompleteProfile;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import bipo.tech.duoraapi.TestClockConfiguration;
import bipo.tech.duoraapi.TestClockConfiguration.TestClock;
import bipo.tech.duoraapi.events.EventFixtures;

/** O que os testes do chat repetem: formar o par pela API, enviar, ler o banco por fora (docs/adr/0021). */
public final class ChatFixtures {

    public static final Instant STARTS_AT = Instant.parse(EventFixtures.STARTS_AT);
    public static final Instant ENDS_AT = Instant.parse(EventFixtures.ENDS_AT);
    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private ChatFixtures() {
    }

    /**
     * Publica um evento, inscreve as pessoas com o perfil completo (só na primeira vez de cada uma), leva o
     * relógio ao início e sorteia a rodada 1.
     */
    public static String pairedInRoundOne(MockMvc mockMvc, JdbcClient jdbcClient, TestClock clock, String... names)
            throws Exception {
        clock.setTo(TestClockConfiguration.NOW);
        String eventId = createPublishedEvent(mockMvc);
        for (String name : names) {
            registerOnce(mockMvc, jdbcClient, name, eventId);
        }
        clock.setTo(STARTS_AT);
        startRound(mockMvc, eventId, 1);
        return eventId;
    }

    public static void startRound(MockMvc mockMvc, String eventId, int number) throws Exception {
        mockMvc.perform(put("/api/admin/events/" + eventId + "/rounds/" + number).with(admin()))
                .andExpect(status().isCreated());
    }

    public static ResultActions send(MockMvc mockMvc, String eventId, RequestPostProcessor person, String key,
            String text) throws Exception {
        return mockMvc.perform(post(messagesPath(eventId, 1)).with(person)
                .header(IDEMPOTENCY_KEY, key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(textBody(text)));
    }

    /** O texto vai escapado como string JSON: aspas, barras e controles viram sequências de escape. */
    public static String textBody(String text) {
        var escaped = new StringBuilder();
        text.codePoints().forEach(codePoint -> {
            switch (codePoint) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                default -> {
                    if (codePoint < 0x20) {
                        escaped.append("\\u%04x".formatted(codePoint));
                    } else {
                        escaped.appendCodePoint(codePoint);
                    }
                }
            }
        });
        return "{\"text\": \"" + escaped + "\"}";
    }

    public static String chatPath(String eventId, int number) {
        return "/api/events/" + eventId + "/rounds/" + number + "/chat";
    }

    public static String messagesPath(String eventId, int number) {
        return chatPath(eventId, number) + "/messages";
    }

    public static String messagePath(String eventId, int number, int seq) {
        return messagesPath(eventId, number) + "/" + seq;
    }

    public static String newKey() {
        return UUID.randomUUID().toString();
    }

    public static String accountOf(JdbcClient jdbcClient, String name) {
        return jdbcClient.sql("select id from account where subject = :subject")
                .param("subject", "oid-" + name)
                .query(UUID.class).single().toString();
    }

    public static long messageRows(JdbcClient jdbcClient) {
        return jdbcClient.sql("select count(*) from chat_message").query(Long.class).single();
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
            mockMvc.perform(put(EventFixtures.registrationPath(eventId)).with(EventFixtures.user(name)))
                    .andExpect(status().isCreated());
        } else {
            registerWithCompleteProfile(mockMvc, EventFixtures.user(name), eventId);
        }
    }

}
