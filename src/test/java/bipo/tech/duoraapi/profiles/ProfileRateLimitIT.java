package bipo.tech.duoraapi.profiles;

import static bipo.tech.duoraapi.AccountFixtures.accountIdOf;
import static bipo.tech.duoraapi.TestIdentities.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

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
 * Limite por conta da edição do perfil (docs/adr/0011, "Rate limit"): cada PATCH abre uma transação e
 * grava uma versão nova. A capacidade é reduzida a 3 por hora para chegar logo ao fim; o valor de produção
 * (120 por hora) vem de application.properties.
 */
@SpringBootTest(properties = {
        "duora.profiles.edit-rate-limit.capacity=" + ProfileRateLimitIT.CAPACITY,
        "duora.profiles.edit-rate-limit.period=PT1H"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ProfileRateLimitIT {

    static final int CAPACITY = 3;

    /** Uma ficha de volta a cada 1 h / 3 = 1200 s. */
    private static final String SECONDS_TO_NEXT_CALL = "1200";
    private static final String PROFILE_PATH = "/api/me/profile";

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
    void callsAboveTheLimitAreRejectedWithRetryAfterAndChangeNothing() throws Exception {
        for (int version = 0; version < CAPACITY; version++) {
            editBio(ana(), version).andExpect(status().isOk());
        }

        editBio(ana(), CAPACITY)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, SECONDS_TO_NEXT_CALL))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().json("""
                        {"title": "Too Many Requests", "status": 429,
                         "detail": "rate limit exceeded; try again later", "instance": "/api/me/profile"}
                        """, JsonCompareMode.STRICT));

        assertThat(bioOfAna()).isEqualTo("bio " + (CAPACITY - 1));
    }

    /** O custo é a transação, e não o efeito: a edição que perde a corrida de versão também gasta. */
    @Test
    void editsWithAnOutdatedVersionSpendTheLimit() throws Exception {
        for (int i = 0; i < CAPACITY; i++) {
            editBio(ana(), 7).andExpect(status().isPreconditionFailed());
        }

        editBio(ana(), 0).andExpect(status().isTooManyRequests());
    }

    @Test
    void theLimitIsCountedForEachAccountSeparately() throws Exception {
        for (int version = 0; version < CAPACITY; version++) {
            editBio(ana(), version).andExpect(status().isOk());
        }
        editBio(ana(), CAPACITY).andExpect(status().isTooManyRequests());

        editBio(bruno(), 0).andExpect(status().isOk());
    }

    /** Sem If-Match, ou com um que não é ETag, a recusa vem antes do banco: não gasta nem abre bucket. */
    @Test
    void callsRefusedBeforeTheDatabaseDoNotSpendTheLimit() throws Exception {
        for (int i = 0; i <= CAPACITY; i++) {
            mockMvc.perform(patch(PROFILE_PATH).with(ana())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"bio\": \"x\"}"))
                    .andExpect(status().isPreconditionRequired());
            mockMvc.perform(patch(PROFILE_PATH).with(ana()).header(HttpHeaders.IF_MATCH, "*")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"bio\": \"x\"}"))
                    .andExpect(status().isPreconditionFailed());
        }

        assertThat(keysOfTheLimit()).isEmpty();
        editBio(ana(), 0).andExpect(status().isOk());
    }

    /** Ler o perfil é uma consulta pela chave primária: não gasta o limite. */
    @Test
    void readingTheProfileDoesNotSpendTheLimit() throws Exception {
        for (int i = 0; i <= CAPACITY; i++) {
            mockMvc.perform(get(PROFILE_PATH).with(ana())).andExpect(status().isOk());
        }

        assertThat(keysOfTheLimit()).isEmpty();
    }

    @Test
    void theBucketLivesUnderTheProfileKeyOfTheAccount() throws Exception {
        editBio(ana(), 0).andExpect(status().isOk());

        String accountId = accountIdOf(jdbcClient, "ana");

        assertThat(keysOfTheLimit()).containsExactly("profile:" + accountId);
    }

    /** Falha fechada (docs/adr/0006): sem contar o limite, a edição é recusada, e nada é gravado. */
    @Test
    void editIsRefusedWithoutWritingWhenTheLimitCannotBeCounted() throws Exception {
        editBio(ana(), 0).andExpect(status().isOk());
        jdbcClient.sql("alter table rate_limit_bucket rename to rate_limit_bucket_unavailable").update();
        try {
            editBio(ana(), 1)
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"))
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(content().json("""
                            {"title": "Service Unavailable", "status": 503, "instance": "/api/me/profile"}
                            """, JsonCompareMode.STRICT));
        } finally {
            jdbcClient.sql("alter table rate_limit_bucket_unavailable rename to rate_limit_bucket").update();
        }

        assertThat(bioOfAna()).isEqualTo("bio 0");
    }

    /** Edita a bio de acordo com a versão lida, que cresce de um em um a cada edição aceita. */
    private ResultActions editBio(RequestPostProcessor user, int readVersion) throws Exception {
        return mockMvc.perform(patch(PROFILE_PATH).with(user)
                .header(HttpHeaders.IF_MATCH, "\"" + readVersion + "\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"bio\": \"bio %d\"}".formatted(readVersion)));
    }

    private String bioOfAna() {
        return jdbcClient.sql("""
                        select p.bio from profile p join account a on a.id = p.account_id
                         where a.subject = 'oid-ana'
                        """)
                .query(String.class).single();
    }

    private List<String> keysOfTheLimit() {
        return jdbcClient.sql("select id from rate_limit_bucket where id like 'profile:%'")
                .query(String.class).list();
    }

    private static RequestPostProcessor ana() {
        return bearer("oid-ana");
    }

    private static RequestPostProcessor bruno() {
        return bearer("oid-bruno");
    }

}
