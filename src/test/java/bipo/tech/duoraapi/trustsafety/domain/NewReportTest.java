package bipo.tech.duoraapi.trustsafety.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import bipo.tech.duoraapi.identity.AccountId;
import bipo.tech.duoraapi.trustsafety.ReportReason;

class NewReportTest {

    private static final AccountId ANA = new AccountId(UUID.fromString("01966c4e-7d1a-7c3e-9b5f-3f2a1c0d9e8b"));
    private static final AccountId BRUNO = new AccountId(UUID.fromString("01966c4e-7d1a-7c3e-9b5f-3f2a1c0d9e8c"));
    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");

    @Test
    void anAccountReportsAnotherWithADescription() {
        var description = ReportDescription.fromText("Mensagens ofensivas").orElseThrow();

        var report = new NewReport(ANA, BRUNO, ReportReason.HARASSMENT, description, NOW);

        assertThat(report.reporter()).isEqualTo(ANA);
        assertThat(report.reported()).isEqualTo(BRUNO);
        assertThat(report.reason()).isEqualTo(ReportReason.HARASSMENT);
        assertThat(report.description()).hasValue(description);
        assertThat(report.filedAt()).isEqualTo(NOW);
    }

    /** Os motivos da lista já dizem o que houve; a descrição é complemento. */
    @ParameterizedTest
    @EnumSource(value = ReportReason.class, mode = EnumSource.Mode.EXCLUDE, names = "OTHER")
    void listedReasonsDoNotNeedADescription(ReportReason reason) {
        var report = new NewReport(ANA, BRUNO, reason, null, NOW);

        assertThat(report.description()).isEmpty();
    }

    /** "Outro" sem texto não diz nada à moderação. */
    @Test
    void otherReasonNeedsADescription() {
        assertThatThrownBy(() -> new NewReport(ANA, BRUNO, ReportReason.OTHER, null, NOW))
                .isInstanceOf(InvalidReportException.class)
                .hasMessage("description is required when the reason is OTHER");
    }

    @Test
    void otherReasonWithADescriptionIsAccepted() {
        var description = ReportDescription.fromText("Pediu dinheiro emprestado").orElseThrow();

        assertThat(new NewReport(ANA, BRUNO, ReportReason.OTHER, description, NOW).description()).hasValue(description);
    }

    @ParameterizedTest
    @ValueSource(strings = {"reporter", "reported", "reason", "filedAt"})
    void everyRequiredFieldMustBePresent(String missingField) {
        var reporter = "reporter".equals(missingField) ? null : ANA;
        var reported = "reported".equals(missingField) ? null : BRUNO;
        var reason = "reason".equals(missingField) ? null : ReportReason.HARASSMENT;
        var filedAt = "filedAt".equals(missingField) ? null : NOW;

        assertThatThrownBy(() -> new NewReport(reporter, reported, reason, null, filedAt))
                .isInstanceOf(NullPointerException.class)
                .hasMessage(missingField);
    }

    @Test
    void anAccountCannotReportItself() {
        assertThatThrownBy(() -> new NewReport(ANA, new AccountId(ANA.value()), ReportReason.HARASSMENT, null, NOW))
                .isInstanceOf(InvalidReportException.class)
                .hasMessage("an account cannot report itself");
    }

}
