package bipo.tech.duoraapi.profiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
 * Perfil do próprio usuário (docs/adr/0011): leitura, edição parcial com If-Match, entrada estrita,
 * isolamento entre usuários e CSRF na sessão web. O jwt() e o oidcLogin() pulam a validação do token,
 * o que basta aqui: ela está em BearerTokenValidationIT e WebLoginIT.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class ProfileIT {

    private static final String PROFILE_PATH = "/api/me/profile";
    private static final String CURRENT_USER_PATH = "/api/me";
    private static final String ISSUER = "https://tenant-id.ciamlogin.example/tenant-id/v2.0";
    private static final String EMPTY_PROFILE = """
            {"displayName": null, "birthDate": null, "bio": null, "region": null, "complete": false}
            """;
    private static final String ANA_PROFILE = """
            {"displayName": "Ana Souza", "birthDate": "1990-05-10", "bio": "Gosto de trilhas", "region": "BR-SP",
             "complete": true}
            """;
    private static final int CONCURRENT_EDITS = 4;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void cleanDatabase() {
        AccountTables.deleteAccountsAndTheirData(jdbcClient);
    }

    @Test
    void newAccountHasAnEmptyProfileAtVersionZero() throws Exception {
        mockMvc.perform(get(PROFILE_PATH).with(ana()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"0\""))
                .andExpect(content().json(EMPTY_PROFILE, JsonCompareMode.STRICT));
    }

    @Test
    void editingFillsTheProfileAndAdvancesTheETag() throws Exception {
        edit(ana(), "\"0\"", anaProfileJson())
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
                .andExpect(content().json(ANA_PROFILE, JsonCompareMode.STRICT));

        mockMvc.perform(get(PROFILE_PATH).with(ana()))
                .andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
                .andExpect(content().json(ANA_PROFILE, JsonCompareMode.STRICT));
    }

    /** PATCH: campo ausente não muda; null apaga. */
    @Test
    void absentFieldsStayAndNullClearsTheBio() throws Exception {
        edit(ana(), "\"0\"", anaProfileJson()).andExpect(status().isOk());

        edit(ana(), "\"1\"", """
                {"bio": null}
                """)
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"2\""))
                .andExpect(content().json("""
                        {"displayName": "Ana Souza", "birthDate": "1990-05-10", "bio": null, "region": "BR-SP",
                         "complete": true}
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void editWithoutIfMatchIsRejectedWithoutWriting() throws Exception {
        mockMvc.perform(patch(PROFILE_PATH).with(ana())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(anaProfileJson()))
                .andExpect(status().isPreconditionRequired())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));

        assertThat(profileRows()).isZero();
    }

    /** Duas abas editam a partir da mesma leitura: a segunda não apaga a primeira em silêncio. */
    @Test
    void editBasedOnAnOutdatedReadIsRejectedAndKeepsTheNewerEdit() throws Exception {
        edit(ana(), "\"0\"", """
                {"displayName": "Ana"}
                """).andExpect(status().isOk());

        edit(ana(), "\"0\"", """
                {"displayName": "Bia"}
                """)
                .andExpect(status().isPreconditionFailed())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));

        mockMvc.perform(get(PROFILE_PATH).with(ana()))
                .andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
                .andExpect(content().json("""
                        {"displayName": "Ana"}
                        """));
    }

    /**
     * If-Match compara ETags fortes, um só, caractere a caractere: fraco, lista, curinga, sem aspas ou com zero
     * à esquerda ("00" não é o ETag "0") não casam com a versão.
     */
    @ParameterizedTest
    @ValueSource(strings = {"W/\"0\"", "*", "0", "\"0\", \"1\"", "\"abc\"", "\"-1\"", "\"00\"", "\"01\"", "\"999999999999999999\"",
            "\"9999999999999999999\"", "\"99999999999999999999\"", ""})
    void ifMatchThatIsNotExactlyTheCurrentETagIsRejected(String ifMatch) throws Exception {
        edit(ana(), ifMatch, anaProfileJson())
                .andExpect(status().isPreconditionFailed())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));

        assertThat(profileRows()).isZero();
    }

    /** Edições simultâneas a partir da mesma versão: só uma grava, as outras recebem 412, nunca 500. */
    @RepeatedTest(3)
    void concurrentEditsFromTheSameVersionKeepOnlyOne() throws Exception {
        edit(ana(), "\"0\"", anaProfileJson()).andExpect(status().isOk());
        var start = new CountDownLatch(1);
        var statuses = new ArrayList<Future<Integer>>();

        try (var executor = Executors.newFixedThreadPool(CONCURRENT_EDITS)) {
            for (int i = 0; i < CONCURRENT_EDITS; i++) {
                statuses.add(executor.submit(editNameAfter(start, "Nome " + i)));
            }
            start.countDown();
            var results = new ArrayList<Integer>();
            for (var status : statuses) {
                results.add(status.get(30, TimeUnit.SECONDS));
            }
            assertThat(results).containsOnly(200, 412).containsOnlyOnce(200);
        }

        var version = jdbcClient.sql("select version from profile").query(Long.class).single();
        assertThat(version).isEqualTo(2);
    }

    /** Sem "type": o Spring o omite quando é about:blank, o que a RFC 9457 trata como equivalente. */
    @ParameterizedTest
    @ValueSource(strings = {"displayName", "birthDate", "region"})
    void requiredFieldCannotBeRemoved(String field) throws Exception {
        edit(ana(), "\"0\"", anaProfileJson()).andExpect(status().isOk());

        edit(ana(), "\"1\"", """
                {"%s": null}
                """.formatted(field))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().json("""
                        {"title": "Bad Request", "status": 400, "detail": "%s cannot be removed",
                         "instance": "/api/me/profile"}
                        """.formatted(field), JsonCompareMode.STRICT));

        mockMvc.perform(get(PROFILE_PATH).with(ana())).andExpect(content().json(ANA_PROFILE, JsonCompareMode.STRICT));
    }

    /** Apagar o texto no formulário envia "": é o mesmo que null, e apaga a bio. */
    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\\n"})
    void blankBioClearsIt(String blank) throws Exception {
        edit(ana(), "\"0\"", anaProfileJson()).andExpect(status().isOk());

        edit(ana(), "\"1\"", """
                {"bio": "%s"}
                """.formatted(blank))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"2\""))
                .andExpect(content().json("""
                        {"displayName": "Ana Souza", "birthDate": "1990-05-10", "bio": null, "region": "BR-SP",
                         "complete": true}
                        """, JsonCompareMode.STRICT));
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    void invalidInputIsRejectedWithoutWriting(String body) throws Exception {
        edit(ana(), "\"0\"", body)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));

        assertThat(profileRows()).isZero();
    }

    static Stream<Named<String>> invalidBodies() {
        return Stream.of(
                Named.of("nome com 51 caracteres", "{\"displayName\": \"%s\"}".formatted("a".repeat(51))),
                Named.of("nome em branco", "{\"displayName\": \"   \"}"),
                Named.of("nome com NUL", "{\"displayName\": \"Ana\\u0000\"}"),
                Named.of("nome com controle de direção", "{\"displayName\": \"Ana\\u202e\"}"),
                Named.of("nome como objeto", "{\"displayName\": {\"first\": \"Ana\"}}"),
                Named.of("nome como lista", "{\"displayName\": [\"Ana\"]}"),
                Named.of("data em outro formato", "{\"birthDate\": \"10/05/1990\"}"),
                Named.of("data inexistente", "{\"birthDate\": \"1990-02-30\"}"),
                Named.of("data como número (dia da época)", "{\"birthDate\": 7000}"),
                Named.of("data de menor de idade", "{\"birthDate\": \"2020-01-01\"}"),
                Named.of("data de mais de 120 anos", "{\"birthDate\": \"1800-01-01\"}"),
                Named.of("bio com 301 caracteres", "{\"bio\": \"%s\"}".formatted("a".repeat(301))),
                Named.of("bio com NUL", "{\"bio\": \"oi\\u0000\"}"),
                Named.of("região sem o país", "{\"region\": \"SP\"}"),
                Named.of("região com injeção de SQL", "{\"region\": \"BR-SP' OR '1'='1\"}"),
                Named.of("região como endereço", "{\"region\": \"Avenida Paulista, 1000\"}"),
                Named.of("JSON quebrado", "{\"displayName\": "),
                Named.of("lista no lugar do objeto", "[]"),
                Named.of("null no lugar do objeto", "null"));
    }

    /** O valor recusado pode ser dado pessoal: não volta na resposta nem vai para o log. */
    @ParameterizedTest
    @ValueSource(strings = {"{\"displayName\": \"%s%s\"}", "{\"bio\": \"%s\\u0000%s\"}",
            "{\"birthDate\": \"%s%s\"}", "{\"region\": \"%s%s\"}", "{\"nickname\": \"%s%s\"}",
            "{\"displayName\": \"%s%s"})
    void rejectedValueIsNotEchoedInTheResponseOrTheLog(String bodyTemplate, CapturedOutput output) throws Exception {
        var canary = "CANARY-" + UUID.randomUUID();
        var body = bodyTemplate.formatted(canary, "x".repeat(50));

        var response = edit(ana(), "\"0\"", body)
                .andExpect(status().isBadRequest())
                .andReturn().getResponse();

        assertThat(response.getContentAsString()).doesNotContain(canary);
        assertThat(response.getHeaderNames()).allSatisfy(
                name -> assertThat(String.join(",", response.getHeaders(name))).doesNotContain(canary));
        assertThat(output.getAll()).doesNotContain(canary);
    }

    /** id, versão, dono e estado calculado são do servidor (mass assignment). */
    @ParameterizedTest
    @ValueSource(strings = {"{\"displayName\": \"Ana\", \"complete\": true}",
            "{\"displayName\": \"Ana\", \"version\": 7}",
            "{\"displayName\": \"Ana\", \"accountId\": \"01966c4e-7d1a-7c3e-9b5f-3f2a1c0d9e8b\"}",
            "{\"displayName\": \"Ana\", \"adult\": true}"})
    void unknownFieldIsRejectedWithoutWriting(String body) throws Exception {
        edit(ana(), "\"0\"", body)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));

        assertThat(profileRows()).isZero();
    }

    /** Texto livre vai por parâmetro: chega ao banco e volta como texto, sem virar SQL. */
    @Test
    void sqlInTheNameIsStoredAsPlainText() throws Exception {
        var name = "Robert'); drop table profile;--";

        edit(ana(), "\"0\"", """
                {"displayName": "%s"}
                """.formatted(name)).andExpect(status().isOk());

        mockMvc.perform(get(PROFILE_PATH).with(ana()))
                .andExpect(content().json("""
                        {"displayName": "%s"}
                        """.formatted(name)));
    }

    @Test
    void changingTheBirthDateIsAConflict() throws Exception {
        edit(ana(), "\"0\"", anaProfileJson()).andExpect(status().isOk());

        edit(ana(), "\"1\"", """
                {"birthDate": "1991-05-10"}
                """)
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));

        mockMvc.perform(get(PROFILE_PATH).with(ana())).andExpect(content().json(ANA_PROFILE, JsonCompareMode.STRICT));
    }

    /** Não há rota com o id de outro perfil: cada um só alcança o próprio, e o ETag alheio não serve. */
    @Test
    void anotherUserNeitherSeesNorChangesTheProfile() throws Exception {
        edit(ana(), "\"0\"", anaProfileJson()).andExpect(status().isOk());

        var brunoView = mockMvc.perform(get(PROFILE_PATH).with(bruno()))
                .andExpect(status().isOk())
                .andExpect(content().json(EMPTY_PROFILE, JsonCompareMode.STRICT))
                .andReturn().getResponse().getContentAsString();
        edit(bruno(), "\"1\"", """
                {"displayName": "Bruno"}
                """).andExpect(status().isPreconditionFailed());

        assertThat(brunoView).doesNotContain("Ana", "1990-05-10", "trilhas", "BR-SP");
        mockMvc.perform(get(PROFILE_PATH).with(ana())).andExpect(content().json(ANA_PROFILE, JsonCompareMode.STRICT));
    }

    @Test
    void editByAnotherUserChangesOnlyTheirOwnProfile() throws Exception {
        edit(ana(), "\"0\"", anaProfileJson()).andExpect(status().isOk());

        edit(bruno(), "\"0\"", """
                {"displayName": "Bruno"}
                """).andExpect(status().isOk());

        mockMvc.perform(get(PROFILE_PATH).with(ana())).andExpect(content().json(ANA_PROFILE, JsonCompareMode.STRICT));
    }

    @Test
    void webSessionEditWithoutCsrfTokenIsRejectedWithoutWriting() throws Exception {
        mockMvc.perform(patch(PROFILE_PATH).with(anaWebSession())
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(anaProfileJson()))
                .andExpect(status().isForbidden());

        assertThat(profileRows()).isZero();
    }

    @Test
    void webSessionEditWithCsrfTokenIsAccepted() throws Exception {
        mockMvc.perform(patch(PROFILE_PATH).with(anaWebSession()).with(csrf())
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(anaProfileJson()))
                .andExpect(status().isOk())
                .andExpect(content().json(ANA_PROFILE, JsonCompareMode.STRICT));
    }

    @Test
    void anonymousCannotReadOrEditProfiles() throws Exception {
        mockMvc.perform(get(PROFILE_PATH)).andExpect(status().isUnauthorized());
        mockMvc.perform(patch(PROFILE_PATH).with(csrf())
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(anaProfileJson()))
                .andExpect(status().isUnauthorized());

        assertThat(profileRows()).isZero();
    }

    @Test
    void currentUserWithEmptyProfileIsNotComplete() throws Exception {
        mockMvc.perform(get(CURRENT_USER_PATH).with(ana()))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"displayName": "Ana do Entra", "profileComplete": false}
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void currentUserWithFilledProfileIsComplete() throws Exception {
        edit(ana(), "\"0\"", anaProfileJson()).andExpect(status().isOk());

        mockMvc.perform(get(CURRENT_USER_PATH).with(ana()))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"displayName": "Ana do Entra", "profileComplete": true}
                        """, JsonCompareMode.STRICT));
    }

    private Callable<Integer> editNameAfter(CountDownLatch start, String name) {
        return () -> {
            start.await();
            return edit(ana(), "\"1\"", """
                    {"displayName": "%s"}
                    """.formatted(name)).andReturn().getResponse().getStatus();
        };
    }

    private ResultActions edit(RequestPostProcessor user, String ifMatch, String body) throws Exception {
        return mockMvc.perform(patch(PROFILE_PATH).with(user)
                .header(HttpHeaders.IF_MATCH, ifMatch)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private long profileRows() {
        return jdbcClient.sql("select count(*) from profile").query(Long.class).single();
    }

    private static String anaProfileJson() {
        return """
                {"displayName": "Ana Souza", "birthDate": "1990-05-10", "bio": "Gosto de trilhas", "region": "BR-SP"}
                """;
    }

    private static RequestPostProcessor ana() {
        return jwt().jwt(token -> token.issuer(ISSUER).claim("oid", "oid-ana").claim("name", "Ana do Entra"));
    }

    private static RequestPostProcessor bruno() {
        return jwt().jwt(token -> token.issuer(ISSUER).claim("oid", "oid-bruno").claim("name", "Bruno do Entra"));
    }

    private static RequestPostProcessor anaWebSession() {
        return oidcLogin().idToken(token -> token.issuer(ISSUER).claim("oid", "oid-ana"));
    }

}
