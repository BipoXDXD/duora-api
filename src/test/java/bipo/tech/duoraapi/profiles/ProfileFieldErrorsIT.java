package bipo.tech.duoraapi.profiles;

import static bipo.tech.duoraapi.ProblemJson.strictIgnoringDetail;
import static bipo.tech.duoraapi.TestIdentities.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import bipo.tech.duoraapi.AccountTables;
import bipo.tech.duoraapi.TestClockConfiguration;
import bipo.tech.duoraapi.TestClockConfiguration.TestClock;
import bipo.tech.duoraapi.TestcontainersConfiguration;

/**
 * O 400 do corpo do {@code PATCH /api/me/profile} diz qual campo falhou e por quê em {@code errors}
 * (docs/adr/0018), sem repetir o valor recebido. O jwt() pula a validação do token, o que basta aqui.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestClockConfiguration.class})
class ProfileFieldErrorsIT {

    private static final String PROFILE_PATH = "/api/me/profile";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private TestClock clock;

    @BeforeEach
    void resetState() {
        clock.setTo(TestClockConfiguration.NOW);
        AccountTables.deleteAccountsAndTheirData(jdbcClient);
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    void invalidBodyNamesTheFieldAndTheReason(String body, String errors) throws Exception {
        edit(body)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().json("""
                        {"title": "Bad Request", "status": 400, "instance": "%s", "errors": %s}
                        """.formatted(PROFILE_PATH, errors), strictIgnoringDetail()));

        assertThat(profileRows()).isZero();
    }

    static Stream<Arguments> invalidBodies() {
        return Stream.of(
                invalid("nome com 51 caracteres", "{\"displayName\": \"" + "a".repeat(51) + "\"}",
                        "displayName", "TOO_LONG"),
                invalid("nome em branco", "{\"displayName\": \"   \"}", "displayName", "TOO_SHORT"),
                invalid("nome com zero-width space", "{\"displayName\": \"Ana\\u200B\"}",
                        "displayName", "FORBIDDEN_CHARACTER"),
                invalid("nome apagado", "{\"displayName\": null}", "displayName", "REQUIRED"),
                invalid("nome como objeto", "{\"displayName\": {}}", "displayName", "INVALID_FORMAT"),
                invalid("bio com 301 caracteres", "{\"bio\": \"" + "a".repeat(301) + "\"}", "bio", "TOO_LONG"),
                invalid("bio com NUL", "{\"bio\": \"oi\\u0000\"}", "bio", "FORBIDDEN_CHARACTER"),
                invalid("data fora do ISO 8601", "{\"birthDate\": \"10/05/1990\"}", "birthDate", "INVALID_FORMAT"),
                invalid("menor de idade", "{\"birthDate\": \"2020-01-01\"}", "birthDate", "ABOVE_MAXIMUM"),
                invalid("idade implausível", "{\"birthDate\": \"1890-01-01\"}", "birthDate", "BELOW_MINIMUM"),
                invalid("região fora da lista", "{\"region\": \"SP\"}", "region", "UNSUPPORTED_VALUE"),
                invalid("região apagada", "{\"region\": null}", "region", "REQUIRED"),
                invalid("campo desconhecido", "{\"nickname\": \"Ana\"}", "nickname", "UNKNOWN_FIELD"),
                Arguments.of(Named.of("JSON quebrado", "{\"displayName\": "),
                        "[{\"code\": \"MALFORMED_BODY\"}]"),
                Arguments.of(Named.of("lista no lugar do objeto", "[]"),
                        "[{\"code\": \"MALFORMED_BODY\"}]"));
    }

    /** O valor recusado pode ser dado pessoal: não volta, nem a chave desconhecida fora do formato de nome. */
    @ParameterizedTest
    @ValueSource(strings = {"{\"displayName\": \"%s\\u200B\"}", "{\"bio\": \"%s\\u0000\"}",
            "{\"birthDate\": \"%s\"}", "{\"region\": \"%s\"}", "{\"%s\": \"x\"}", "{\"displayName\": \"%s"})
    void rejectedValueIsNotEchoed(String bodyTemplate) throws Exception {
        var canary = "CANARY-" + UUID.randomUUID();

        var response = edit(bodyTemplate.formatted(canary))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(response).contains("\"errors\"").doesNotContain(canary);
    }

    private static Arguments invalid(String name, String body, String field, String code) {
        return Arguments.of(Named.of(name, body), """
                [{"field": "%s", "code": "%s"}]""".formatted(field, code));
    }

    private ResultActions edit(String body) throws Exception {
        return mockMvc.perform(patch(PROFILE_PATH).with(ana())
                .header(HttpHeaders.IF_MATCH, "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private long profileRows() {
        return jdbcClient.sql("select count(*) from profile").query(Long.class).single();
    }

    private static RequestPostProcessor ana() {
        return bearer("oid-ana-field-errors");
    }

}
