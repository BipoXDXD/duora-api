package bipo.tech.duoraapi.connections;

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
 * As invariantes do connections também valem no banco, como última defesa contra um caminho que passe por
 * fora do domínio (docs/adr/0019).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ConnectionsSchemaIT {

    private static final UUID EVENT = UUID.fromString("01966c4e-7d1a-7c3e-9b5f-3f2a1c0d9e8b");

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
    void aPersonDecidesOncePerRound() {
        insertDecision(1, ana, bruno, true);
        insertDecision(2, ana, bruno, true);

        assertViolates("round_decision_pkey", () -> insertDecision(1, ana, bruno, false));
    }

    @Test
    void nobodyDecidesAboutThemselves() {
        assertViolates("round_decision_not_self", () -> insertDecision(1, ana, ana, true));
    }

    @Test
    void theRoundNumberGoesFromOneToOneHundred() {
        insertDecision(100, ana, bruno, true);

        assertViolates("round_decision_round_number_check", () -> insertDecision(0, ana, bruno, true));
        assertViolates("round_decision_round_number_check", () -> insertDecision(101, ana, bruno, true));
    }

    @Test
    void aPairHasASingleConnection() {
        insertConnection(lower(), higher());

        assertViolates("connection_pkey", () -> insertConnection(lower(), higher()));
    }

    /** O par fica sempre na mesma ordem: a linha invertida seria uma segunda conexão do mesmo par. */
    @Test
    void theConnectionPairIsNormalized() {
        assertViolates("connection_normalized_pair", () -> insertConnection(higher(), lower()));
        assertViolates("connection_normalized_pair", () -> insertConnection(ana, ana));
    }

    @Test
    void accountWithDecisionsOrConnectionsCannotBeDeletedBehindTheModulesBack() {
        insertDecision(1, ana, bruno, true);
        insertConnection(lower(), higher());

        assertThatThrownBy(() -> jdbcClient.sql("delete from account where id = :id").param("id", ana).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcClient.sql("delete from account where id = :id").param("id", bruno).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbcClient.sql("select count(*) from connection").query(Long.class).single()).isEqualTo(1);
    }

    private UUID lower() {
        return ana.toString().compareTo(bruno.toString()) < 0 ? ana : bruno;
    }

    private UUID higher() {
        return lower().equals(ana) ? bruno : ana;
    }

    private void insertDecision(int round, UUID account, UUID partner, boolean interested) {
        jdbcClient.sql("""
                        insert into round_decision
                            (event_id, round_number, account_id, partner_account_id, interested, decided_at)
                        values (:event, :round, :account, :partner, :interested, now())
                        """)
                .param("event", EVENT)
                .param("round", round)
                .param("account", account)
                .param("partner", partner)
                .param("interested", interested)
                .update();
    }

    private void insertConnection(UUID first, UUID second) {
        jdbcClient.sql("""
                        insert into connection (first_account_id, second_account_id, connected_at)
                        values (:first, :second, now())
                        """)
                .param("first", first)
                .param("second", second)
                .update();
    }

}
