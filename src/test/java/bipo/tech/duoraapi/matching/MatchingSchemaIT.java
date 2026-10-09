package bipo.tech.duoraapi.matching;

import static bipo.tech.duoraapi.SchemaSupport.assertViolates;
import static bipo.tech.duoraapi.SchemaSupport.insertAccount;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Types;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import bipo.tech.duoraapi.AccountTables;
import bipo.tech.duoraapi.TestcontainersConfiguration;

/**
 * As regras das rodadas também valem no banco (docs/adr/0017): uma rodada por número, em sequência, cada
 * pessoa num lugar só por rodada, par recíproco e nunca repetido no evento.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class MatchingSchemaIT {

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private UUID event;
    private UUID ana;
    private UUID bruno;
    private UUID carla;

    @BeforeEach
    void setUp() {
        AccountTables.deleteAccountsAndTheirData(jdbcClient);
        event = insertEvent();
        ana = insertAccount(jdbcClient, "oid-ana");
        bruno = insertAccount(jdbcClient, "oid-bruno");
        carla = insertAccount(jdbcClient, "oid-carla");
    }

    @Test
    void anEventHasOneRoundPerNumber() {
        insertRound(1, null);

        assertViolates("round_pkey", () -> insertRound(1, null));
    }

    @Test
    void aRoundNeedsThePreviousOne() {
        assertViolates("round_previous_fk", () -> insertRound(2, 1));
    }

    @Test
    void aRoundAfterTheFirstMustPointToThePreviousOne() {
        insertRound(1, null);

        assertViolates("round_sequence_check", () -> insertRound(2, null));
    }

    @Test
    void aRoundCannotSkipNumbers() {
        insertRound(1, null);

        assertViolates("round_sequence_check", () -> insertRound(3, 1));
    }

    @Test
    void theFirstRoundHasNoPreviousOne() {
        insertRound(1, null);

        assertThatThrownBy(() -> insertRound(1, 0))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void roundsFollowOneAnother() {
        insertRound(1, null);

        assertThatCode(() -> insertRound(2, 1)).doesNotThrowAnyException();
    }

    @Test
    void roundNumberIsAtMostOneHundred() {
        assertThatThrownBy(() -> insertRound(101, 100))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aPairIsRecordedFromBothSides() {
        insertRound(1, null);

        assertThatCode(() -> insertPair(1, ana, bruno)).doesNotThrowAnyException();
        assertThat(jdbcClient.sql("select count(*) from round_seat").query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void aOneSidedPairIsRejected() {
        insertRound(1, null);

        assertViolates("round_seat_reciprocal_fk", () -> insertSeat(1, ana, bruno));
    }

    @Test
    void aPairMustBeReciprocalAndNotJustPointAtSomeonesSeat() {
        insertRound(1, null);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            insertSeat(1, ana, bruno);
            insertSeat(1, bruno, carla);
            insertSeat(1, carla, bruno);
        })).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void nobodyIsPairedWithThemselves() {
        insertRound(1, null);

        assertViolates("round_seat_not_self", () -> insertSeat(1, ana, ana));
    }

    @Test
    void aPersonHasOneSeatPerRound() {
        insertRound(1, null);
        insertPair(1, ana, bruno);

        assertViolates("round_seat_pkey", () -> insertSeat(1, ana, null));
    }

    @Test
    void thePairDoesNotRepeatInTheEvent() {
        insertRound(1, null);
        insertRound(2, 1);
        insertPair(1, ana, bruno);

        assertViolates("round_seat_pair_once_per_event", () -> insertPair(2, bruno, ana));
    }

    @Test
    void aPersonCanSitOutInSeveralRounds() {
        insertRound(1, null);
        insertRound(2, 1);
        insertSeat(1, carla, null);

        assertThatCode(() -> insertSeat(2, carla, null)).doesNotThrowAnyException();
    }

    @Test
    void aSeatNeedsItsRound() {
        assertViolates("round_seat_round_fk", () -> insertSeat(1, carla, null));
    }

    /** on delete restrict: apagar evento ou conta exige decidir antes o destino das rodadas. */
    @Test
    void eventsAndAccountsWithRoundsCannotBeDeletedBehindTheModulesBack() {
        insertRound(1, null);
        insertSeat(1, carla, null);

        assertThatThrownBy(() -> jdbcClient.sql("delete from event where id = :id").param("id", event).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcClient.sql("delete from account where id = :id").param("id", carla).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertPair(int round, UUID one, UUID other) {
        transactionTemplate.executeWithoutResult(status -> {
            insertSeat(round, one, other);
            insertSeat(round, other, one);
        });
    }

    private void insertSeat(int round, UUID account, UUID partner) {
        jdbcClient.sql("""
                        insert into round_seat (event_id, round_number, account_id, partner_account_id)
                        values (:event, :round, :account, :partner)
                        """)
                .param("event", event)
                .param("round", round)
                .param("account", account)
                .param("partner", partner, Types.OTHER)
                .update();
    }

    private void insertRound(int number, Integer previous) {
        jdbcClient.sql("""
                        insert into round (event_id, number, previous_number, seed, started_at)
                        values (:event, :number, :previous, 42, now())
                        """)
                .param("event", event)
                .param("number", number)
                .param("previous", previous, Types.INTEGER)
                .update();
    }

    private UUID insertEvent() {
        return jdbcClient.sql("""
                        insert into event (title, description, starts_at, ends_at, capacity, status, created_at)
                        values ('Noite de jogos', 'Jogos em dupla.', now(), now() + interval '3 hours', 10,
                                'PUBLISHED', now())
                        returning id
                        """)
                .query(UUID.class).single();
    }

}
