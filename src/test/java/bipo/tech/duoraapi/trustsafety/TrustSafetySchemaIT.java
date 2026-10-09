package bipo.tech.duoraapi.trustsafety;

import static bipo.tech.duoraapi.SchemaSupport.assertViolates;
import static bipo.tech.duoraapi.SchemaSupport.insertAccount;
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
        ana = insertAccount(jdbcClient, "oid-ana");
        bruno = insertAccount(jdbcClient, "oid-bruno");
    }

    @Test
    void anAccountCannotBlockItself() {
        assertViolates("account_block_not_self", () -> insertBlock(ana, ana));
    }

    @Test
    void aPairHasASingleBlockInEachDirection() {
        insertBlock(ana, bruno);
        insertBlock(bruno, ana);

        assertViolates("account_block_pkey", () -> insertBlock(ana, bruno));
    }

    @Test
    void blockedAccountMustExist() {
        assertViolates("account_block_blocked_account_id_fkey",
                () -> insertBlock(ana, UUID.fromString("01966c4e-7d1a-7c3e-9b5f-3f2a1c0d9e8b")));
    }

    /** on delete restrict: a exclusão de conta passa pelo trustsafety, em vez de uma cascata invisível. */
    @Test
    void accountWithBlocksCannotBeDeletedBehindTheModulesBack() {
        insertBlock(ana, bruno);

        assertThatThrownBy(() -> jdbcClient.sql("delete from account where id = :id").param("id", bruno).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbcClient.sql("select count(*) from account_block").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void anAccountCannotReportItself() {
        assertViolates("report_not_self", () -> insertReport(ana, ana, "HARASSMENT", null));
    }

    @Test
    void otherReasonNeedsADescription() {
        assertViolates("report_other_needs_description", () -> insertReport(ana, bruno, "OTHER", null));
    }

    @Test
    void reasonComesFromTheClosedList() {
        assertViolates("report_reason_check", () -> insertReport(ana, bruno, "BORING", null));
    }

    @Test
    void descriptionHasAtMostAThousandCharacters() {
        insertReport(ana, bruno, "OTHER", "a".repeat(1000));

        assertViolates("report_description_check", () -> insertReport(ana, bruno, "OTHER", "a".repeat(1001)));
    }

    @Test
    void reportStartsOpenAndHasAUuidV7() {
        insertReport(ana, bruno, "HARASSMENT", null);

        assertThat(jdbcClient.sql("select status from report").query(String.class).single()).isEqualTo("OPEN");
        assertThat(jdbcClient.sql("select id from report").query(UUID.class).single().version()).isEqualTo(7);
    }

    @Test
    void accountWithReportsCannotBeDeletedBehindTheModulesBack() {
        insertReport(ana, bruno, "HARASSMENT", null);

        assertThatThrownBy(() -> jdbcClient.sql("delete from account where id = :id").param("id", ana).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcClient.sql("delete from account where id = :id").param("id", bruno).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertReport(UUID reporter, UUID reported, String reason, String description) {
        jdbcClient.sql("""
                        insert into report (reporter_account_id, reported_account_id, reason, description, status, created_at)
                        values (:reporter, :reported, :reason, :description, 'OPEN', now())
                        """)
                .param("reporter", reporter)
                .param("reported", reported)
                .param("reason", reason)
                .param("description", description)
                .update();
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
