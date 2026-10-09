package bipo.tech.duoraapi.trustsafety;

import static bipo.tech.duoraapi.TestIdentities.ISSUER;
import static bipo.tech.duoraapi.TestIdentities.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
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
import org.junit.jupiter.params.ParameterizedTest;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import bipo.tech.duoraapi.AccountFixtures;
import bipo.tech.duoraapi.AccountTables;
import bipo.tech.duoraapi.TestcontainersConfiguration;
import bipo.tech.duoraapi.identity.AccountId;

/**
 * Bloqueio entre contas (docs/adr/0015): bloquear e desbloquear são idempotentes, ninguém bloqueia a
 * si mesmo, a lista mostra só os bloqueios de quem pede, e os outros módulos perguntam pelo
 * {@link Blocking}. O jwt() e o oidcLogin() pulam a validação do token, o que basta aqui: ela está em
 * BearerTokenValidationIT e WebLoginIT.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class BlockIT {

    private static final String BLOCKED_ACCOUNTS_PATH = "/api/me/blocked-accounts";
    /** UUIDv7 bem formado que não é de conta nenhuma. */
    private static final String UNKNOWN_ACCOUNT_ID = "01966c4e-7d1a-7c3e-9b5f-3f2a1c0d9e8b";
    private static final int CONCURRENT_BLOCKS = 4;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private Blocking blocking;

    @BeforeEach
    void cleanDatabase() {
        AccountTables.deleteAccountsAndTheirData(jdbcClient);
    }

    @Test
    void blockingAnotherAccountRecordsTheBlockWithoutBody() throws Exception {
        var bruno = accountIdOf("oid-bruno");

        block(ana(), bruno)
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        assertThat(blockRows()).containsExactly(accountIdOf("oid-ana") + " -> " + bruno);
    }

    /** Idempotente: o segundo bloqueio é sucesso e mantém a data do primeiro. */
    @Test
    void blockingTwiceKeepsASingleBlockFromTheFirstTime() throws Exception {
        var bruno = accountIdOf("oid-bruno");
        block(ana(), bruno).andExpect(status().isNoContent());
        var firstBlockedAt = blockedAtOf(bruno);

        block(ana(), bruno).andExpect(status().isNoContent());

        assertThat(blockRows()).hasSize(1);
        assertThat(blockedAtOf(bruno)).isEqualTo(firstBlockedAt);
    }

    /** Toques repetidos no botão: todos respondem sucesso, e o par continua com uma linha só. */
    @RepeatedTest(3)
    void concurrentBlocksOfTheSamePairKeepASingleBlock() throws Exception {
        accountIdOf("oid-ana");
        var bruno = accountIdOf("oid-bruno");
        var start = new CountDownLatch(1);
        var statuses = new ArrayList<Future<Integer>>();

        try (var executor = Executors.newFixedThreadPool(CONCURRENT_BLOCKS)) {
            for (int i = 0; i < CONCURRENT_BLOCKS; i++) {
                statuses.add(executor.submit(blockAfter(start, bruno)));
            }
            start.countDown();
            var results = new ArrayList<Integer>();
            for (var status : statuses) {
                results.add(status.get(30, TimeUnit.SECONDS));
            }
            assertThat(results).containsOnly(204);
        }

        assertThat(blockRows()).hasSize(1);
    }

    @Test
    void blockingYourselfIsRejectedWithoutWriting() throws Exception {
        var ana = accountIdOf("oid-ana");

        block(ana(), ana)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().json("""
                        {"title": "Bad Request", "status": 400, "detail": "an account cannot block itself",
                         "instance": "/api/accounts/%s:block"}
                        """.formatted(ana), JsonCompareMode.STRICT));

        assertThat(blockRows()).isEmpty();
    }

    /** O id é um UUIDv7 que ninguém adivinha; o 404 só confirma o que o próprio cliente já tinha. */
    @Test
    void blockingAnUnknownAccountIsNotFoundWithoutWriting() throws Exception {
        accountIdOf("oid-ana");

        block(ana(), UNKNOWN_ACCOUNT_ID)
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().json("""
                        {"title": "Not Found", "status": 404, "detail": "account not found",
                         "instance": "/api/accounts/%s:block"}
                        """.formatted(UNKNOWN_ACCOUNT_ID), JsonCompareMode.STRICT));

        assertThat(blockRows()).isEmpty();
    }

    /** Quem foi bloqueado não descobre isso tentando bloquear de volta: a resposta é a mesma. */
    @Test
    void blockingSomeoneWhoBlockedYouLooksLikeAnyOtherBlock() throws Exception {
        var ana = accountIdOf("oid-ana");
        var bruno = accountIdOf("oid-bruno");
        block(bruno(), ana).andExpect(status().isNoContent());

        block(ana(), bruno)
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        assertThat(blockRows()).containsExactlyInAnyOrder(ana + " -> " + bruno, bruno + " -> " + ana);
    }

    /** Desbloquear um par não mexe nos outros bloqueios de quem chama, nem nos que outras contas fizeram. */
    @Test
    void unblockingOnePairKeepsEveryOtherBlock() throws Exception {
        var ana = accountIdOf("oid-ana");
        var bruno = accountIdOf("oid-bruno");
        var carla = accountIdOf("oid-carla");
        block(ana(), bruno).andExpect(status().isNoContent());
        block(ana(), carla).andExpect(status().isNoContent());
        block(bearer("oid-carla"), bruno).andExpect(status().isNoContent());

        unblock(ana(), bruno).andExpect(status().isNoContent());

        assertThat(blockRows()).containsExactlyInAnyOrder(ana + " -> " + carla, carla + " -> " + bruno);
    }

    @Test
    void unblockingRemovesOnlyTheCallersBlock() throws Exception {
        var ana = accountIdOf("oid-ana");
        var bruno = accountIdOf("oid-bruno");
        block(ana(), bruno).andExpect(status().isNoContent());
        block(bruno(), ana).andExpect(status().isNoContent());

        unblock(ana(), bruno)
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        assertThat(blockRows()).containsExactly(bruno + " -> " + ana);
    }

    /** Desbloquear quem não está bloqueado, ou conta que não existe, já deixa o estado pedido: sucesso. */
    @Test
    void unblockingWhenThereIsNoBlockSucceeds() throws Exception {
        var bruno = accountIdOf("oid-bruno");

        unblock(ana(), bruno).andExpect(status().isNoContent());
        unblock(ana(), UNKNOWN_ACCOUNT_ID).andExpect(status().isNoContent());

        assertThat(blockRows()).isEmpty();
    }

    /** Mais recentes primeiro; o fim da lista é o token nulo, e não a página incompleta. */
    @Test
    void blockedAccountsAreListedNewestFirstAcrossPages() throws Exception {
        var bruno = accountIdOf("oid-bruno");
        var carla = accountIdOf("oid-carla");
        var davi = accountIdOf("oid-davi");
        block(ana(), bruno).andExpect(status().isNoContent());
        block(ana(), carla).andExpect(status().isNoContent());
        block(ana(), davi).andExpect(status().isNoContent());

        var firstPage = listBlocked(ana(), "?maxPageSize=2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].accountId").value(davi))
                .andExpect(jsonPath("$.items[0].blockedAt").value(blockedAtOf(davi).toString()))
                .andExpect(jsonPath("$.items[1].accountId").value(carla))
                .andExpect(jsonPath("$.nextPageToken").isString())
                .andReturn().getResponse().getContentAsString();
        String nextPageToken = JsonPath.read(firstPage, "$.nextPageToken");

        listBlocked(ana(), "?maxPageSize=2&pageToken=" + nextPageToken)
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"items": [{"accountId": "%s", "blockedAt": "%s"}], "nextPageToken": null}
                        """.formatted(bruno, blockedAtOf(bruno)), JsonCompareMode.STRICT));
    }

    /** A página que traz o último bloqueio fecha a lista, mesmo quando está cheia. */
    @Test
    void fullPageThatHoldsTheLastBlockHasNoNextPageToken() throws Exception {
        var bruno = accountIdOf("oid-bruno");
        var carla = accountIdOf("oid-carla");
        block(ana(), bruno).andExpect(status().isNoContent());
        block(ana(), carla).andExpect(status().isNoContent());

        listBlocked(ana(), "?maxPageSize=2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.nextPageToken").isEmpty());
    }

    /** Sem maxPageSize, a página é de 20; o vigésimo primeiro bloqueio vai para a seguinte. */
    @Test
    void listWithoutMaxPageSizeHasTwentyBlocksPerPage() throws Exception {
        var ana = accountIdOf("oid-ana");
        insertAccountsAndBlockThem(ana, 21);

        var firstPage = listBlocked(ana(), "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(20))
                .andExpect(jsonPath("$.nextPageToken").isString())
                .andReturn().getResponse().getContentAsString();
        String nextPageToken = JsonPath.read(firstPage, "$.nextPageToken");

        listBlocked(ana(), "?pageToken=" + nextPageToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.nextPageToken").isEmpty());
    }

    /** Bloqueios do mesmo instante: a conta bloqueada desempata, e nenhum é pulado nem repetido entre páginas. */
    @Test
    void blocksOfTheSameInstantAreListedOnceEachAcrossPages() throws Exception {
        var ana = accountIdOf("oid-ana");
        var blocked = List.of(accountIdOf("oid-bruno"), accountIdOf("oid-carla"), accountIdOf("oid-davi"));
        var sameInstant = OffsetDateTime.parse("2026-10-05T12:00:00Z");
        blocked.forEach(account -> insertBlock(ana, account, sameInstant));
        var listed = new ArrayList<String>();

        String query = "?maxPageSize=1";
        while (query != null) {
            var page = listBlocked(ana(), query).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            List<String> accountIds = JsonPath.read(page, "$.items[*].accountId");
            listed.addAll(accountIds);
            String nextPageToken = JsonPath.read(page, "$.nextPageToken");
            query = nextPageToken == null ? null : "?maxPageSize=1&pageToken=" + nextPageToken;
        }

        assertThat(listed).containsExactlyElementsOf(blocked.stream().sorted(Comparator.reverseOrder()).toList());
    }

    @Test
    void listShowsOnlyTheCallersOwnBlocks() throws Exception {
        accountIdOf("oid-ana");
        var carla = accountIdOf("oid-carla");
        block(bruno(), carla).andExpect(status().isNoContent());

        listBlocked(ana(), "")
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"items": [], "nextPageToken": null}
                        """, JsonCompareMode.STRICT));
    }

    /** O token só marca a posição: a consulta continua filtrando por quem pede. */
    @Test
    void pageTokenFromAnotherAccountOnlyPagesTheCallersOwnList() throws Exception {
        var carla = accountIdOf("oid-carla");
        var davi = accountIdOf("oid-davi");
        block(ana(), carla).andExpect(status().isNoContent());
        block(ana(), davi).andExpect(status().isNoContent());
        var anaFirstPage = listBlocked(ana(), "?maxPageSize=1").andReturn().getResponse().getContentAsString();
        String anaToken = JsonPath.read(anaFirstPage, "$.nextPageToken");

        listBlocked(bruno(), "?pageToken=" + anaToken)
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"items": [], "nextPageToken": null}
                        """, JsonCompareMode.STRICT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "100"})
    void pageSizeWithinTheLimitsIsAccepted(String maxPageSize) throws Exception {
        listBlocked(ana(), "?maxPageSize=" + maxPageSize).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "101", "abc", "99999999999", "", " "})
    void pageSizeOutsideTheLimitsIsRejected(String maxPageSize) throws Exception {
        listBlocked(ana(), "?maxPageSize=" + maxPageSize)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    /** O mesmo ProblemDetail das outras listas paginadas, com o teto desta lista e sem o valor recebido. */
    @Test
    void pageSizeOutsideTheLimitsNamesTheLimitOfThisList() throws Exception {
        listBlocked(ana(), "?maxPageSize=101")
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                        {"title": "Bad Request", "status": 400,
                         "detail": "maxPageSize must be between 1 and 100", "instance": "/api/me/blocked-accounts"}
                        """, JsonCompareMode.STRICT));
    }

    @ParameterizedTest
    @MethodSource("malformedPageTokens")
    void malformedPageTokenIsRejected(String pageToken) throws Exception {
        listBlocked(ana(), "?pageToken=" + pageToken)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    /** Os tokens em Base64 saem do texto aqui mesmo: legíveis no teste, e sem cara de chave para o gitleaks. */
    static Stream<Named<String>> malformedPageTokens() {
        return Stream.of(
                Named.of("não é Base64", "not-a-token"),
                Named.of("Base64 sem a posição", base64Url("not-a-token")),
                Named.of("Base64 com conta que não é UUID", base64Url("2026-10-05T12:00:00Z|not-a-uuid")),
                Named.of("Base64 com data inválida", base64Url("yesterday|01966c4e-7d1a-7c3e-9b5f-3f2a1c0d9e8b")),
                Named.of("Base64 com ano que o timestamptz não guarda",
                        base64Url("+200000-01-01T00:00:00Z|01966c4e-7d1a-7c3e-9b5f-3f2a1c0d9e8b")),
                Named.of("NUL", "%00"),
                Named.of("acima do teto de tamanho", "A".repeat(201)));
    }

    private static String base64Url(String text) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void webSessionBlockWithoutCsrfTokenIsRejectedWithoutWriting() throws Exception {
        var bruno = accountIdOf("oid-bruno");

        mockMvc.perform(post("/api/accounts/{id}:block", bruno).with(anaWebSession()))
                .andExpect(status().isForbidden());

        assertThat(blockRows()).isEmpty();
    }

    @Test
    void webSessionBlockWithCsrfTokenIsAccepted() throws Exception {
        var bruno = accountIdOf("oid-bruno");

        mockMvc.perform(post("/api/accounts/{id}:block", bruno).with(anaWebSession()).with(csrf()))
                .andExpect(status().isNoContent());

        assertThat(blockRows()).hasSize(1);
    }

    @Test
    void anonymousCannotBlock() throws Exception {
        var bruno = accountIdOf("oid-bruno");

        mockMvc.perform(post("/api/accounts/{id}:block", bruno).with(csrf()))
                .andExpect(status().isUnauthorized());

        assertThat(blockRows()).isEmpty();
    }

    /** A pergunta que chat e pareamento vão fazer: vale nas duas direções, e só enquanto o bloqueio existir. */
    @Test
    void blockingIsSeenFromBothSidesUntilUnblocked() throws Exception {
        var ana = new AccountId(UUID.fromString(accountIdOf("oid-ana")));
        var bruno = new AccountId(UUID.fromString(accountIdOf("oid-bruno")));
        var carla = new AccountId(UUID.fromString(accountIdOf("oid-carla")));
        assertThat(blocking.existsBetween(ana, bruno)).isFalse();

        block(ana(), bruno.value().toString()).andExpect(status().isNoContent());

        assertThat(blocking.existsBetween(ana, bruno)).isTrue();
        assertThat(blocking.existsBetween(bruno, ana)).isTrue();
        assertThat(blocking.existsBetween(ana, carla)).isFalse();
        assertThat(blocking.existsBetween(carla, bruno)).isFalse();

        unblock(ana(), bruno.value().toString()).andExpect(status().isNoContent());

        assertThat(blocking.existsBetween(ana, bruno)).isFalse();
        assertThat(blocking.existsBetween(bruno, ana)).isFalse();
    }

    /** O pareamento pergunta por todos os pares de um grupo numa consulta, sem saber quem bloqueou quem. */
    @Test
    void blockedPairsAmongAGroupComeInEitherDirectionAndOnlyInsideTheGroup() throws Exception {
        var ana = new AccountId(UUID.fromString(accountIdOf("oid-ana")));
        var bruno = new AccountId(UUID.fromString(accountIdOf("oid-bruno")));
        var carla = new AccountId(UUID.fromString(accountIdOf("oid-carla")));
        var davi = new AccountId(UUID.fromString(accountIdOf("oid-davi")));
        block(ana(), bruno.value().toString()).andExpect(status().isNoContent());
        block(bruno(), ana.value().toString()).andExpect(status().isNoContent());
        block(carla(), ana.value().toString()).andExpect(status().isNoContent());
        block(davi(), bruno.value().toString()).andExpect(status().isNoContent());
        block(carla(), davi.value().toString()).andExpect(status().isNoContent());

        var pairs = blocking.blockedPairsAmong(List.of(ana, bruno, carla));

        assertThat(pairs).containsExactlyInAnyOrder(BlockedPair.of(ana, bruno), BlockedPair.of(carla, ana));
    }

    @Test
    void anEmptyGroupHasNoBlockedPairs() {
        assertThat(blocking.blockedPairsAmong(List.of())).isEmpty();
    }

    private Callable<Integer> blockAfter(CountDownLatch start, String blocked) {
        return () -> {
            start.await();
            return block(ana(), blocked).andReturn().getResponse().getStatus();
        };
    }

    private ResultActions block(RequestPostProcessor user, String accountId) throws Exception {
        return mockMvc.perform(post("/api/accounts/{id}:block", accountId).with(user));
    }

    private ResultActions unblock(RequestPostProcessor user, String accountId) throws Exception {
        return mockMvc.perform(post("/api/accounts/{id}:unblock", accountId).with(user));
    }

    private ResultActions listBlocked(RequestPostProcessor user, String query) throws Exception {
        return mockMvc.perform(get(BLOCKED_ACCOUNTS_PATH + query).with(user));
    }

    /** Abre a conta pelo primeiro acesso, como acontece em produção, e devolve o id dela. */
    private String accountIdOf(String objectId) throws Exception {
        return AccountFixtures.openAccount(mockMvc, jdbcClient, objectId);
    }

    private void insertBlock(String blocker, String blocked, OffsetDateTime createdAt) {
        jdbcClient.sql("""
                        insert into account_block (blocker_account_id, blocked_account_id, created_at)
                        values (:blocker, :blocked, :createdAt)
                        """)
                .param("blocker", UUID.fromString(blocker))
                .param("blocked", UUID.fromString(blocked))
                .param("createdAt", createdAt)
                .update();
    }

    /** Abre contas direto no banco, só para ter volume: o que importa aqui é a lista, não o primeiro acesso. */
    private void insertAccountsAndBlockThem(String blocker, int count) {
        jdbcClient.sql("""
                        insert into account (issuer, subject, created_at)
                        select :issuer, 'oid-blocked-' || n, now() from generate_series(1, :count) as n
                        """)
                .param("issuer", ISSUER)
                .param("count", count)
                .update();
        jdbcClient.sql("""
                        insert into account_block (blocker_account_id, blocked_account_id, created_at)
                        select :blocker, id, now() from account where subject like 'oid-blocked-%'
                        """)
                .param("blocker", UUID.fromString(blocker))
                .update();
    }

    private List<String> blockRows() {
        return jdbcClient.sql("select blocker_account_id, blocked_account_id from account_block")
                .query((row, number) -> row.getString("blocker_account_id") + " -> " + row.getString("blocked_account_id"))
                .list();
    }

    private Instant blockedAtOf(String blocked) {
        return jdbcClient.sql("select created_at from account_block where blocked_account_id = :blocked")
                .param("blocked", UUID.fromString(blocked))
                .query(OffsetDateTime.class).single().toInstant();
    }

    private static RequestPostProcessor ana() {
        return bearer("oid-ana");
    }

    private static RequestPostProcessor bruno() {
        return bearer("oid-bruno");
    }

    private static RequestPostProcessor carla() {
        return bearer("oid-carla");
    }

    private static RequestPostProcessor davi() {
        return bearer("oid-davi");
    }

    private static RequestPostProcessor anaWebSession() {
        return oidcLogin().idToken(token -> token.issuer(ISSUER).claim("oid", "oid-ana"));
    }

}
