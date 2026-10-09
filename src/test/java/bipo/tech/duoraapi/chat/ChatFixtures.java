package bipo.tech.duoraapi.chat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Instant;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import bipo.tech.duoraapi.AccountFixtures;
import bipo.tech.duoraapi.TestClockConfiguration.TestClock;
import bipo.tech.duoraapi.events.EventFixtures;
import bipo.tech.duoraapi.matching.RoundFixtures;

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
        return RoundFixtures.pairedInRoundOne(mockMvc, jdbcClient, clock, names);
    }

    public static void startRound(MockMvc mockMvc, String eventId, int number) throws Exception {
        RoundFixtures.startRound(mockMvc, eventId, number);
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
        return AccountFixtures.accountIdOf(jdbcClient, name);
    }

    public static long messageRows(JdbcClient jdbcClient) {
        return jdbcClient.sql("select count(*) from chat_message").query(Long.class).single();
    }

}
