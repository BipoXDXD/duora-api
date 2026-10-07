package bipo.tech.duoraapi.waitlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import bipo.tech.duoraapi.TestcontainersConfiguration;

/**
 * O 400 do corpo do {@code POST /api/waitlist} diz qual campo falhou e por quê em {@code errors}
 * (docs/adr/0018), sem repetir o valor recebido.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class WaitlistFieldErrorsIT {

    private static final String WAITLIST_PATH = "/api/waitlist";
    /** Um IP só destes testes: o limite por IP dos outros testes não interfere. */
    private static final String CLIENT_IP = "198.51.100.40";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void cleanDatabase() {
        jdbcClient.sql("delete from waitlist_entry").update();
        jdbcClient.sql("delete from rate_limit_bucket").update();
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    void invalidBodyNamesTheFieldAndTheReason(String body, String detail, String errors) throws Exception {
        join(body)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().json("""
                        {"title": "Bad Request", "status": 400, "detail": "%s", "instance": "%s", "errors": %s}
                        """.formatted(detail, WAITLIST_PATH, errors), JsonCompareMode.STRICT));

        assertThat(entries()).isZero();
    }

    static Stream<Arguments> invalidBodies() {
        return Stream.of(
                Arguments.of(Named.of("sem e-mail", "{}"), "email is required",
                        "[{\"field\": \"email\", \"code\": \"REQUIRED\"}]"),
                Arguments.of(Named.of("e-mail null", "{\"email\": null}"), "email is required",
                        "[{\"field\": \"email\", \"code\": \"REQUIRED\"}]"),
                Arguments.of(Named.of("e-mail sem arroba", "{\"email\": \"ana.example.com\"}"),
                        "email is not a valid address", "[{\"field\": \"email\", \"code\": \"INVALID_FORMAT\"}]"),
                Arguments.of(Named.of("e-mail como objeto", "{\"email\": {}}"), "Failed to read request",
                        "[{\"field\": \"email\", \"code\": \"INVALID_FORMAT\"}]"),
                Arguments.of(Named.of("campo desconhecido", "{\"email\": \"ana@example.com\", \"nickname\": \"Ana\"}"),
                        "Failed to read request", "[{\"field\": \"nickname\", \"code\": \"UNKNOWN_FIELD\"}]"),
                Arguments.of(Named.of("JSON quebrado", "{\"email\": "), "Failed to read request",
                        "[{\"code\": \"MALFORMED_BODY\"}]"),
                Arguments.of(Named.of("null no lugar do objeto", "null"), "Failed to read request",
                        "[{\"code\": \"MALFORMED_BODY\"}]"),
                Arguments.of(Named.of("lista no lugar do objeto", "[]"), "Failed to read request",
                        "[{\"code\": \"MALFORMED_BODY\"}]"));
    }

    /** O valor recusado e a chave desconhecida podem ser dado pessoal: nenhum dos dois volta. */
    @ParameterizedTest
    @ValueSource(strings = {"{\"email\": \"%s\"}", "{\"email\": \"ana@example.com\", \"%s\": 1}",
            "{\"email\": \"%s"})
    void rejectedValueIsNotEchoed(String bodyTemplate) throws Exception {
        var canary = "CANARY-" + UUID.randomUUID();

        var response = join(bodyTemplate.formatted(canary))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(response).contains("\"errors\"").doesNotContain(canary);
    }

    private ResultActions join(String body) throws Exception {
        return mockMvc.perform(post(WAITLIST_PATH)
                .with(request -> {
                    request.setRemoteAddr(CLIENT_IP);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private long entries() {
        return jdbcClient.sql("select count(*) from waitlist_entry").query(Long.class).single();
    }

}
