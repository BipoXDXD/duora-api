package bipo.tech.duoraapi;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import bipo.tech.duoraapi.identity.AccountId;

/**
 * Como os testes de integração abrem uma conta e leem o id que o banco gerou. A conta nasce no primeiro acesso
 * autenticado, como em produção (docs/adr/0011); o subject dela é o oid do token. Por convenção dos testes de
 * eventos, o nome "ana" tem o oid "oid-ana".
 */
public final class AccountFixtures {

    private static final String NAME_PREFIX = "oid-";

    private AccountFixtures() {
    }

    /** O primeiro acesso cria a conta de quem chama. */
    public static void firstAccess(MockMvc mockMvc, RequestPostProcessor user) throws Exception {
        mockMvc.perform(get("/api/me").with(user)).andExpect(status().isOk());
    }

    /** Abre a conta pelo primeiro acesso e devolve o id dela. */
    public static String openAccount(MockMvc mockMvc, JdbcClient jdbcClient, RequestPostProcessor user,
            String subject) throws Exception {
        firstAccess(mockMvc, user);
        return accountIdOfSubject(jdbcClient, subject);
    }

    /** Abre a conta de quem chama com bearer token pelo oid, que é também o subject dela. */
    public static String openAccount(MockMvc mockMvc, JdbcClient jdbcClient, String objectId) throws Exception {
        return openAccount(mockMvc, jdbcClient, TestIdentities.bearer(objectId), objectId);
    }

    /** O id da conta que já existe, pelo subject. */
    public static String accountIdOfSubject(JdbcClient jdbcClient, String subject) {
        return jdbcClient.sql("select id from account where subject = :subject")
                .param("subject", subject)
                .query(UUID.class).single().toString();
    }

    /** O id da conta que já existe, pelo nome ("ana" para o subject "oid-ana"). */
    public static String accountIdOf(JdbcClient jdbcClient, String name) {
        return accountIdOfSubject(jdbcClient, NAME_PREFIX + name);
    }

    /** O mesmo id de {@link #accountIdOf}, no tipo que os módulos usam entre si. */
    public static AccountId accountOf(JdbcClient jdbcClient, String name) {
        return new AccountId(UUID.fromString(accountIdOf(jdbcClient, name)));
    }

}
