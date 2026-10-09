package bipo.tech.duoraapi.trustsafety.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import bipo.tech.duoraapi.FieldErrorCode;
import bipo.tech.duoraapi.identity.AccountId;
import bipo.tech.duoraapi.trustsafety.ReportReason;

/**
 * Uma denúncia ainda não registrada: o que quem denuncia informa. Ganha id e estado inicial ao ser
 * gravada ({@link ReportRepository#add}). Ninguém denuncia a si mesmo, e o motivo {@code OTHER} exige
 * descrição.
 */
public final class NewReport {

    private final AccountId reporter;
    private final AccountId reported;
    private final ReportReason reason;
    private final ReportDescription description;
    private final Instant filedAt;

    /** @param description opcional, salvo com o motivo {@code OTHER} */
    public NewReport(AccountId reporter, AccountId reported, ReportReason reason, ReportDescription description,
            Instant filedAt) {
        this.reporter = Objects.requireNonNull(reporter, "reporter");
        this.reported = Objects.requireNonNull(reported, "reported");
        this.reason = Objects.requireNonNull(reason, "reason");
        this.filedAt = Objects.requireNonNull(filedAt, "filedAt");
        this.description = description;
        if (reporter.equals(reported)) {
            throw new InvalidReportException("reportedAccountId", FieldErrorCode.SELF_REFERENCE,
                    "an account cannot report itself");
        }
        if (reason == ReportReason.OTHER && description == null) {
            throw new InvalidReportException("description", FieldErrorCode.REQUIRED,
                    "description is required when the reason is OTHER");
        }
    }

    public AccountId reporter() {
        return reporter;
    }

    public AccountId reported() {
        return reported;
    }

    public ReportReason reason() {
        return reason;
    }

    public Optional<ReportDescription> description() {
        return Optional.ofNullable(description);
    }

    public Instant filedAt() {
        return filedAt;
    }

}
