package bipo.tech.duoraapi.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import bipo.tech.duoraapi.TestcontainersConfiguration;

/**
 * A conta interna nasce no primeiro acesso autenticado, pelas duas portas de entrada, identificada
 * por emissor + oid e nunca pelo e-mail (docs/adr/0011). O jwt() e o oidcLogin() pulam a validação do
 * token, o que basta aqui: ela está em BearerTokenValidationIT e WebLoginIT.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AccountProvisioningIT {

    private static final String ISSUER = "https://tenant-id.ciamlogin.example/tenant-id/v2.0";
    private static final String OTHER_ISSUER = "https://other-tenant.ciamlogin.example/other-tenant/v2.0";
    private static final String CURRENT_USER_PATH = "/api/me";
    private static final int CONCURRENT_REQUESTS = 8;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void cleanDatabase() {
        jdbcClient.sql("delete from profile").update();
        jdbcClient.sql("delete from account").update();
    }

    @Test
    void firstAuthenticatedRequestOpensAnAccountForTheIssuerAndObjectId() throws Exception {
        mockMvc.perform(get(CURRENT_USER_PATH).with(bearer(ISSUER, "oid-ana", "ana@example.com")))
                .andExpect(status().isOk());

        var accounts = jdbcClient.sql("select issuer, subject from account")
                .query((row, number) -> row.getString("issuer") + " " + row.getString("subject"))
                .list();
        assertThat(accounts).containsExactly(ISSUER + " oid-ana");
    }

    @Test
    void laterRequestsKeepTheSameAccount() throws Exception {
        mockMvc.perform(get(CURRENT_USER_PATH).with(bearer(ISSUER, "oid-ana", "ana@example.com")));
        var firstId = accountIds();

        mockMvc.perform(get(CURRENT_USER_PATH).with(bearer(ISSUER, "oid-ana", "ana@example.com")))
                .andExpect(status().isOk());

        assertThat(accountIds()).hasSize(1).isEqualTo(firstId);
    }

    /** O e-mail muda no Entra; o oid não. Trocar de e-mail não abre outra conta. */
    @Test
    void newEmailForTheSameObjectIdKeepsTheAccount() throws Exception {
        mockMvc.perform(get(CURRENT_USER_PATH).with(bearer(ISSUER, "oid-ana", "ana@example.com")));

        mockMvc.perform(get(CURRENT_USER_PATH).with(bearer(ISSUER, "oid-ana", "ana.souza@example.com")))
                .andExpect(status().isOk());

        assertThat(accountIds()).hasSize(1);
    }

    /** O mesmo e-mail em outra identidade é outra pessoa: o e-mail não identifica a conta. */
    @Test
    void sameEmailForAnotherObjectIdOpensAnotherAccount() throws Exception {
        mockMvc.perform(get(CURRENT_USER_PATH).with(bearer(ISSUER, "oid-ana", "ana@example.com")));

        mockMvc.perform(get(CURRENT_USER_PATH).with(bearer(ISSUER, "oid-bruno", "ana@example.com")))
                .andExpect(status().isOk());

        assertThat(accountIds()).hasSize(2);
    }

    @Test
    void sameObjectIdFromAnotherIssuerOpensAnotherAccount() throws Exception {
        mockMvc.perform(get(CURRENT_USER_PATH).with(bearer(ISSUER, "oid-ana", "ana@example.com")));

        mockMvc.perform(get(CURRENT_USER_PATH).with(bearer(OTHER_ISSUER, "oid-ana", "ana@example.com")))
                .andExpect(status().isOk());

        assertThat(accountIds()).hasSize(2);
    }

    /** O front web (sessão) e o app (bearer) da mesma pessoa chegam à mesma conta. */
    @Test
    void webSessionAndBearerTokenOfTheSamePersonShareTheAccount() throws Exception {
        mockMvc.perform(get(CURRENT_USER_PATH).with(webSession(ISSUER, "oid-ana"))).andExpect(status().isOk());

        mockMvc.perform(get(CURRENT_USER_PATH).with(bearer(ISSUER, "oid-ana", "ana@example.com")))
                .andExpect(status().isOk());

        assertThat(accountIds()).hasSize(1);
    }

    @Test
    void anonymousRequestOpensNoAccount() throws Exception {
        mockMvc.perform(get(CURRENT_USER_PATH)).andExpect(status().isUnauthorized());

        assertThat(accountIds()).isEmpty();
    }

    /** A conta guarda só o que a identifica: nem e-mail, nem nome, nem papéis. */
    @Test
    void accountStoresOnlyTheExternalIdentity() {
        var columns = jdbcClient.sql("""
                        select column_name from information_schema.columns
                        where table_schema = current_schema() and table_name = 'account'
                        """)
                .query(String.class)
                .list();

        assertThat(columns).containsExactlyInAnyOrder("id", "issuer", "subject", "created_at");
    }

    /**
     * Várias abas abertas ao mesmo tempo no primeiro acesso: a constraint UNIQUE decide, e todas as
     * requisições recebem a mesma conta, sem erro.
     */
    @RepeatedTest(3)
    void concurrentFirstRequestsOpenASingleAccount() throws Exception {
        var start = new CountDownLatch(1);
        var statuses = new ArrayList<Future<Integer>>();
        try (var executor = Executors.newFixedThreadPool(CONCURRENT_REQUESTS)) {
            for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
                statuses.add(executor.submit(firstRequestAfter(start)));
            }
            start.countDown();
            for (var status : statuses) {
                assertThat(status.get(30, TimeUnit.SECONDS)).isEqualTo(200);
            }
        }

        assertThat(accountIds()).hasSize(1);
    }

    private Callable<Integer> firstRequestAfter(CountDownLatch start) {
        return () -> {
            start.await();
            return mockMvc.perform(get(CURRENT_USER_PATH).with(bearer(ISSUER, "oid-ana", "ana@example.com")))
                    .andReturn().getResponse().getStatus();
        };
    }

    private List<UUID> accountIds() {
        return jdbcClient.sql("select id from account order by id").query(UUID.class).list();
    }

    private static RequestPostProcessor bearer(String issuer, String objectId, String email) {
        return jwt().jwt(token -> token.issuer(issuer).claim("oid", objectId).claim("email", email));
    }

    private static RequestPostProcessor webSession(String issuer, String objectId) {
        return oidcLogin().idToken(token -> token.issuer(issuer).claim("oid", objectId));
    }

}
