package bipo.tech.duoraapi.trustsafety;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import bipo.tech.duoraapi.AccountTables;
import bipo.tech.duoraapi.TestcontainersConfiguration;

/**
 * As invariantes do trustsafety também valem no banco, como última defesa contra um caminho que passe
 * por fora do domínio (docs/adr/0015).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TrustSafetySchemaIT {

    @Autowired
    private JdbcClient jdbcClient;

    private UUID ana;
    private UUID bruno;

    @BeforeEach
    void setUp() {
        AccountTables.deleteAccountsAndTheirData(jdbcClient);
        ana = insertAccount("oid-ana");
        bruno = insertAccount("oid-bruno");
    }

    @Test
    void anAccountCannotBlockItself() {
        assertThatThrownBy(() -> insertBlock(ana, ana))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("account_block_not_self");
    }

    @Test
    void aPairHasASingleBlockInEachDirection() {
        insertBlock(ana, bruno);
        insertBlock(bruno, ana);

        assertThatThrownBy(() -> insertBlock(ana, bruno))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("account_block_pkey");
    }

    @Test
    void blockedAccountMustExist() {
        assertThatThrownBy(() -> insertBlock(ana, UUID.fromString("01966c4e-7d1a-7c3e-9b5f-3f2a1c0d9e8b")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("account_block_blocked_account_id_fkey");
    }

    /** on delete restrict: a exclusão de conta passa pelo trustsafety, em vez de uma cascata invisível. */
    @Test
    void accountWithBlocksCannotBeDeletedBehindTheModulesBack() {
        insertBlock(ana, bruno);

        assertThatThrownBy(() -> jdbcClient.sql("delete from account where id = :id").param("id", bruno).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbcClient.sql("select count(*) from account_block").query(Long.class).single()).isEqualTo(1);
    }

    private UUID insertAccount(String subject) {
        return jdbcClient.sql("""
                        insert into account (issuer, subject, created_at) values ('https://issuer.example', :subject, now())
                        returning id
                        """)
                .param("subject", subject)
                .query(UUID.class).single();
    }

    private void insertBlock(UUID blocker, UUID blocked) {
        jdbcClient.sql("""
                        insert into account_block (blocker_account_id, blocked_account_id, created_at)
                        values (:blocker, :blocked, now())
                        """)
                .param("blocker", blocker)
                .param("blocked", blocked)
                .update();
    }

}
