package bipo.tech.duoraapi;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.ResultActions;

/**
 * O que os testes de limite por conta repetem (docs/adr/0006): as fichas ficam na tabela
 * {@code rate_limit_bucket}, uma linha por chave {@code limite:conta}; ao passar do limite a resposta é 429 com
 * Retry-After; e, sem conseguir contar, a operação falha fechada com 503.
 */
public final class RateLimitTestSupport {

    private static final String BUCKET_TABLE = "rate_limit_bucket";
    private static final String UNAVAILABLE_TABLE = "rate_limit_bucket_unavailable";

    private RateLimitTestSupport() {
    }

    /** Uma operação do teste que pode lançar, como uma chamada ao MockMvc. */
    @FunctionalInterface
    public interface Action {

        void run() throws Exception;

    }

    public static void clearBuckets(JdbcClient jdbcClient) {
        jdbcClient.sql("delete from " + BUCKET_TABLE).update();
    }

    /** As chaves das fichas de um limite, como {@code decision:<id da conta>}. */
    public static List<String> bucketKeysOf(JdbcClient jdbcClient, String limit) {
        return jdbcClient.sql("select id from " + BUCKET_TABLE + " where id like :prefix")
                .param("prefix", limit + ":%")
                .query(String.class).list();
    }

    /**
     * Roda a ação com a tabela das fichas fora do ar, para provar a falha fechada. É DDL numa tabela que todos
     * os testes compartilham: só é seguro porque a suíte roda uma classe de cada vez (nada de paralelismo no
     * JUnit nem no Failsafe), e o {@code finally} devolve o nome mesmo se a ação falhar.
     */
    public static void whileTheLimitCannotBeCounted(JdbcClient jdbcClient, Action action) throws Exception {
        jdbcClient.sql("alter table " + BUCKET_TABLE + " rename to " + UNAVAILABLE_TABLE).update();
        try {
            action.run();
        } finally {
            jdbcClient.sql("alter table " + UNAVAILABLE_TABLE + " rename to " + BUCKET_TABLE).update();
        }
    }

    /** Passou do limite: 429, o prazo até a próxima ficha em Retry-After, e só os campos do problema. */
    public static void expectRejectedByTheLimit(ResultActions response, String secondsToNextCall, String instance)
            throws Exception {
        response.andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, secondsToNextCall))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().json("""
                        {"title": "Too Many Requests", "status": 429, "instance": "%s"}
                        """.formatted(instance), ProblemJson.strictIgnoringDetail()));
    }

    /** Sem contar o limite: 503 que manda tentar de novo em 1 s, sem detail: não diz por que a contagem falhou. */
    public static void expectUnavailableBecauseTheLimitCannotBeCounted(ResultActions response, String instance)
            throws Exception {
        response.andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().json("""
                        {"title": "Service Unavailable", "status": 503, "instance": "%s"}
                        """.formatted(instance), JsonCompareMode.STRICT));
    }

}
