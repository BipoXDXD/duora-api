package bipo.tech.duoraapi.profiles;

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
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import bipo.tech.duoraapi.AccountTables;
import bipo.tech.duoraapi.TestcontainersConfiguration;

/**
 * O 400 do corpo do {@code PATCH /api/me/profile} diz qual campo falhou e por quê em {@code errors}
 * (docs/adr/0018), sem repetir o valor recebido. O jwt() pula a validação do token, o que basta aqui.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ProfileFieldErrorsIT {

    private static final String PROFILE_PATH = "/api/me/profile";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void cleanDatabase() {
        AccountTables.deleteAccountsAndTheirData(jdbcClient);
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    void invalidBodyNamesTheFieldAndTheReason(String body, String detail, String errors) throws Exception {
        edit(body)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().json("""
                        {"title": "Bad Request", "status": 400, "detail": "%s", "instance": "%s", "errors": %s}
                        """.formatted(detail, PROFILE_PATH, errors), JsonCompareMode.STRICT));

        assertThat(profileRows()).isZero();
    }

    static Stream<Arguments> invalidBodies() {
        return Stream.of(
                invalid("nome com 51 caracteres", "{\"displayName\": \"" + "a".repeat(51) + "\"}",
                        "displayName must have 1 to 50 characters", "displayName", "TOO_LONG"),
                invalid("nome em branco", "{\"displayName\": \"   \"}",
                        "displayName must have 1 to 50 characters", "displayName", "TOO_SHORT"),
                invalid("nome com zero-width space", "{\"displayName\": \"Ana\\u200B\"}",
                        "displayName contains a forbidden character", "displayName", "FORBIDDEN_CHARACTER"),
                invalid("nome apagado", "{\"displayName\": null}",
                        "displayName cannot be removed", "displayName", "REQUIRED"),
                invalid("nome como objeto", "{\"displayName\": {}}",
                        "Failed to read request", "displayName", "INVALID_FORMAT"),
                invalid("bio com 301 caracteres", "{\"bio\": \"" + "a".repeat(301) + "\"}",
                        "bio must have at most 300 characters", "bio", "TOO_LONG"),
                invalid("bio com NUL", "{\"bio\": \"oi\\u0000\"}",
                        "bio contains a forbidden character", "bio", "FORBIDDEN_CHARACTER"),
                invalid("data fora do ISO 8601", "{\"birthDate\": \"10/05/1990\"}",
                        "birthDate must be a date in the format 1990-05-10", "birthDate", "INVALID_FORMAT"),
                invalid("menor de idade", "{\"birthDate\": \"2020-01-01\"}",
                        "birthDate must be at least 18 years ago", "birthDate", "ABOVE_MAXIMUM"),
                invalid("idade implausível", "{\"birthDate\": \"1890-01-01\"}",
                        "birthDate must be at most 120 years ago", "birthDate", "BELOW_MINIMUM"),
                invalid("região fora da lista", "{\"region\": \"SP\"}",
                        "region must be the ISO 3166-2 code of a Brazilian state, like BR-SP", "region",
                        "UNSUPPORTED_VALUE"),
                invalid("região apagada", "{\"region\": null}", "region cannot be removed", "region", "REQUIRED"),
                invalid("campo desconhecido", "{\"nickname\": \"Ana\"}",
                        "Failed to read request", "nickname", "UNKNOWN_FIELD"),
                Arguments.of(Named.of("JSON quebrado", "{\"displayName\": "), "Failed to read request",
                        "[{\"code\": \"MALFORMED_BODY\"}]"),
                Arguments.of(Named.of("lista no lugar do objeto", "[]"), "Failed to read request",
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

    private static Arguments invalid(String name, String body, String detail, String field, String code) {
        return Arguments.of(Named.of(name, body), detail, """
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
