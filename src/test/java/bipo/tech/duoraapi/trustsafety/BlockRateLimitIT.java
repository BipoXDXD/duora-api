package bipo.tech.duoraapi.trustsafety;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
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

import bipo.tech.duoraapi.AccountTables;
import bipo.tech.duoraapi.TestcontainersConfiguration;

/**
 * Limite por conta de {@code :block} e {@code :unblock} (docs/adr/0015, "Rate limit"): os dois gastam o mesmo
 * saldo, e a conta inexistente gasta como a existente, porque o 404 do {@code :block} revela se a conta existe.
 * A capacidade é reduzida a 3 por hora para chegar logo ao fim; o valor de produção (60 por hora) vem de
 * application.properties.
 */
@SpringBootTest(properties = {
        "duora.trustsafety.block-rate-limit.capacity=" + BlockRateLimitIT.CAPACITY,
        "duora.trustsafety.block-rate-limit.period=PT1H"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class BlockRateLimitIT {

    static final int CAPACITY = 3;

    /** Uma ficha de volta a cada 1 h / 3 = 1200 s. */
    private static final String SECONDS_TO_NEXT_CALL = "1200";
    private static final String ISSUER = "https://tenant-id.ciamlogin.example/tenant-id/v2.0";
    /** UUIDv7 bem formado que não é de conta nenhuma. */
    private static final String UNKNOWN_ACCOUNT_ID = "01966c4e-7d1a-7c3e-9b5f-3f2a1c0d9e8b";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void cleanDatabase() {
        AccountTables.deleteAccountsAndTheirData(jdbcClient);
        jdbcClient.sql("delete from rate_limit_bucket").update();
    }

    @Test
    void callsAboveTheLimitAreRejectedWithRetryAfterAndBlockNothing() throws Exception {
        var bruno = accountIdOf("oid-bruno");
        var carla = accountIdOf("oid-carla");
        for (int i = 0; i < CAPACITY; i++) {
            block(ana(), bruno).andExpect(status().isNoContent());
        }

        block(ana(), carla)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, SECONDS_TO_NEXT_CALL))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().json("""
                        {"title": "Too Many Requests", "status": 429,
                         "detail": "rate limit exceeded; try again later", "instance": "/api/accounts/%s:block"}
                        """.formatted(carla), JsonCompareMode.STRICT));

        assertThat(blockedAccounts()).containsExactly(bruno);
    }

    /** Quem alterna bloquear e desbloquear não escapa por usar a outra operação: o saldo é um só. */
    @Test
    void blockingAndUnblockingShareOneLimit() throws Exception {
        var bruno = accountIdOf("oid-bruno");
        block(ana(), bruno).andExpect(status().isNoContent());
        unblock(ana(), bruno).andExpect(status().isNoContent());
        block(ana(), bruno).andExpect(status().isNoContent());

        unblock(ana(), bruno)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, SECONDS_TO_NEXT_CALL));

        assertThat(blockedAccounts()).containsExactly(bruno);
        assertThat(keysOfTheLimit()).hasSize(1);
    }

    /** O 404 do bloqueio revela se a conta existe: quem testa ids em série acaba no 429. */
    @Test
    void callsOnAnUnknownAccountSpendTheLimitToo() throws Exception {
        accountIdOf("oid-ana");
        for (int i = 0; i < CAPACITY; i++) {
            block(ana(), UNKNOWN_ACCOUNT_ID).andExpect(status().isNotFound());
        }

        block(ana(), UNKNOWN_ACCOUNT_ID).andExpect(status().isTooManyRequests());
    }

    @Test
    void theLimitIsCountedForEachAccountSeparately() throws Exception {
        var carla = accountIdOf("oid-carla");
        for (int i = 0; i < CAPACITY; i++) {
            block(ana(), carla).andExpect(status().isNoContent());
        }
        block(ana(), carla).andExpect(status().isTooManyRequests());

        block(bruno(), carla).andExpect(status().isNoContent());
    }

    /** Um id que não é UUID é recusado antes do controller: não gasta nem abre bucket. */
    @Test
    void callsRefusedBeforeTheControllerDoNotSpendTheLimit() throws Exception {
        accountIdOf("oid-ana");
        for (int i = 0; i <= CAPACITY; i++) {
            block(ana(), "not-a-uuid").andExpect(status().isBadRequest());
        }

        assertThat(keysOfTheLimit()).isEmpty();
    }

    /** Listar os próprios bloqueios é uma consulta paginada pelo dono: não gasta o limite. */
    @Test
    void listingTheBlocksDoesNotSpendTheLimit() throws Exception {
        accountIdOf("oid-ana");
        for (int i = 0; i <= CAPACITY; i++) {
            mockMvc.perform(get("/api/me/blocked-accounts").with(ana())).andExpect(status().isOk());
        }

        assertThat(keysOfTheLimit()).isEmpty();
    }

    @Test
    void theBucketLivesUnderTheBlockKeyOfTheCaller() throws Exception {
        var ana = accountIdOf("oid-ana");
        block(ana(), accountIdOf("oid-bruno")).andExpect(status().isNoContent());

        assertThat(keysOfTheLimit()).containsExactly("block:" + ana);
    }

    /** Falha fechada (docs/adr/0006): sem contar o limite, a operação é recusada, e nada é gravado. */
    @Test
    void blockIsRefusedWithoutWritingWhenTheLimitCannotBeCounted() throws Exception {
        var bruno = accountIdOf("oid-bruno");
        accountIdOf("oid-ana");
        jdbcClient.sql("alter table rate_limit_bucket rename to rate_limit_bucket_unavailable").update();
        try {
            block(ana(), bruno)
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"))
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(content().json("""
                            {"title": "Service Unavailable", "status": 503, "instance": "/api/accounts/%s:block"}
                            """.formatted(bruno), JsonCompareMode.STRICT));
        } finally {
            jdbcClient.sql("alter table rate_limit_bucket_unavailable rename to rate_limit_bucket").update();
        }

        assertThat(blockedAccounts()).isEmpty();
    }

    @Test
    void unblockIsRefusedWithoutWritingWhenTheLimitCannotBeCounted() throws Exception {
        var bruno = accountIdOf("oid-bruno");
        block(ana(), bruno).andExpect(status().isNoContent());
        jdbcClient.sql("alter table rate_limit_bucket rename to rate_limit_bucket_unavailable").update();
        try {
            unblock(ana(), bruno)
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"));
        } finally {
            jdbcClient.sql("alter table rate_limit_bucket_unavailable rename to rate_limit_bucket").update();
        }

        assertThat(blockedAccounts()).containsExactly(bruno);
    }

    private ResultActions block(RequestPostProcessor user, String accountId) throws Exception {
        return mockMvc.perform(post("/api/accounts/{id}:block", accountId).with(user));
    }

    private ResultActions unblock(RequestPostProcessor user, String accountId) throws Exception {
        return mockMvc.perform(post("/api/accounts/{id}:unblock", accountId).with(user));
    }

    /** Abre a conta pelo primeiro acesso, como acontece em produção, e devolve o id dela. */
    private String accountIdOf(String objectId) throws Exception {
        mockMvc.perform(get("/api/me").with(user(objectId))).andExpect(status().isOk());
        return jdbcClient.sql("select id from account where subject = :subject")
                .param("subject", objectId)
                .query(UUID.class).single().toString();
    }

    private List<String> blockedAccounts() {
        return jdbcClient.sql("select blocked_account_id::text from account_block")
                .query(String.class).list();
    }

    private List<String> keysOfTheLimit() {
        return jdbcClient.sql("select id from rate_limit_bucket where id like 'block:%'")
                .query(String.class).list();
    }

    private static RequestPostProcessor ana() {
        return user("oid-ana");
    }

    private static RequestPostProcessor bruno() {
        return user("oid-bruno");
    }

    private static RequestPostProcessor user(String objectId) {
        return jwt().jwt(token -> token.issuer(ISSUER).claim("oid", objectId));
    }

}
