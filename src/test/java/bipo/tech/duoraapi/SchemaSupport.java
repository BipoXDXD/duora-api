package bipo.tech.duoraapi;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * O que os testes que falam direto com o banco repetiam: abrir uma conta sem passar pelo primeiro acesso e
 * afirmar que uma constraint recusou a gravação. A defesa do schema vale mesmo para o caminho que não passa
 * pelo domínio (docs/adr/0019), então estes testes escrevem em SQL, como o banco os vê.
 */
public final class SchemaSupport {

    private static final String TEST_ISSUER = "https://issuer.example";

    private SchemaSupport() {
    }

    /** Abre a conta direto na tabela, sem o primeiro acesso, e devolve o id que o banco gerou. */
    public static UUID insertAccount(JdbcClient jdbcClient, String subject) {
        return jdbcClient.sql("""
                        insert into account (issuer, subject, created_at) values (:issuer, :subject, now())
                        returning id
                        """)
                .param("issuer", TEST_ISSUER)
                .param("subject", subject)
                .query(UUID.class).single();
    }

    /** A gravação falha porque a constraint de nome {@code constraint} a recusou, e não por outro motivo. */
    public static void assertViolates(String constraint, ThrowingCallable writing) {
        assertThatThrownBy(writing)
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }

}
