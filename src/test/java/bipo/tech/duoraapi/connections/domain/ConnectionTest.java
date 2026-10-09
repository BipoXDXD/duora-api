package bipo.tech.duoraapi.connections.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import bipo.tech.duoraapi.identity.AccountId;

/** Interesse mútuo vira conexão; qualquer outra combinação, não (docs/adr/0019). */
class ConnectionTest {

    private static final UUID EVENT = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    private static final UUID OTHER_EVENT = UUID.fromString("00000000-0000-0000-0000-0000000000e2");
    private static final AccountId ANA = new AccountId(UUID.fromString("00000000-0000-0000-0000-00000000000a"));
    private static final AccountId BIA = new AccountId(UUID.fromString("00000000-0000-0000-0000-00000000000b"));
    private static final AccountId CAIO = new AccountId(UUID.fromString("00000000-0000-0000-0000-00000000000c"));
    private static final Instant FIRST_AT = Instant.parse("2026-11-01T22:10:00Z");
    private static final Instant LATEST_AT = Instant.parse("2026-11-01T22:12:00Z");
    private static final boolean NOT_BLOCKED = false;
    private static final boolean BLOCKED = true;

    @Test
    void twoYesesFormAConnectionDatedByTheLatestDecision() {
        var latest = new Decision(EVENT, 2, ANA, BIA, true, LATEST_AT);
        var partners = new Decision(EVENT, 2, BIA, ANA, true, FIRST_AT);

        assertThat(Connection.fromDecisions(latest, Optional.of(partners), NOT_BLOCKED))
                .contains(new Connection(ConnectionPair.of(ANA, BIA), LATEST_AT));
    }

    @Test
    void aNoFromThePartnerFormsNothing() {
        var latest = new Decision(EVENT, 2, ANA, BIA, true, LATEST_AT);
        var partners = new Decision(EVENT, 2, BIA, ANA, false, FIRST_AT);

        assertThat(Connection.fromDecisions(latest, Optional.of(partners), NOT_BLOCKED)).isEmpty();
    }

    @Test
    void aPartnerWhoHasNotDecidedFormsNothing() {
        var latest = new Decision(EVENT, 2, ANA, BIA, true, LATEST_AT);

        assertThat(Connection.fromDecisions(latest, Optional.empty(), NOT_BLOCKED)).isEmpty();
    }

    @Test
    void aNoFromTheLatestFormsNothing() {
        var latest = new Decision(EVENT, 2, ANA, BIA, false, LATEST_AT);
        var partners = new Decision(EVENT, 2, BIA, ANA, true, FIRST_AT);

        assertThat(Connection.fromDecisions(latest, Optional.of(partners), NOT_BLOCKED)).isEmpty();
    }

    @Test
    void aBlockBetweenThemFormsNothingEvenWithTwoYeses() {
        var latest = new Decision(EVENT, 2, ANA, BIA, true, LATEST_AT);
        var partners = new Decision(EVENT, 2, BIA, ANA, true, FIRST_AT);

        assertThat(Connection.fromDecisions(latest, Optional.of(partners), BLOCKED)).isEmpty();
    }

    @Test
    void aYesAboutSomeoneElseFormsNothing() {
        var latest = new Decision(EVENT, 2, ANA, BIA, true, LATEST_AT);
        var aboutCaio = new Decision(EVENT, 2, BIA, CAIO, true, FIRST_AT);

        assertThat(Connection.fromDecisions(latest, Optional.of(aboutCaio), NOT_BLOCKED)).isEmpty();
    }

    @Test
    void aYesFromAnotherRoundFormsNothing() {
        var latest = new Decision(EVENT, 2, ANA, BIA, true, LATEST_AT);
        var otherRound = new Decision(EVENT, 1, BIA, ANA, true, FIRST_AT);

        assertThat(Connection.fromDecisions(latest, Optional.of(otherRound), NOT_BLOCKED)).isEmpty();
    }

    @Test
    void aYesFromAnotherEventFormsNothing() {
        var latest = new Decision(EVENT, 2, ANA, BIA, true, LATEST_AT);
        var otherEvent = new Decision(OTHER_EVENT, 2, BIA, ANA, true, FIRST_AT);

        assertThat(Connection.fromDecisions(latest, Optional.of(otherEvent), NOT_BLOCKED)).isEmpty();
    }

}
