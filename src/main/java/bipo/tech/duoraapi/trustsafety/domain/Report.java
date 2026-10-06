package bipo.tech.duoraapi.trustsafety.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import bipo.tech.duoraapi.identity.AccountId;

/**
 * Uma denúncia registrada, como a moderação vai recebê-la.
 *
 * @param description o relato, ou null quando quem denunciou não escreveu nada
 */
public record Report(UUID id, AccountId reporter, AccountId reported, ReportReason reason,
        ReportDescription description, ReportStatus status, Instant createdAt) {

    public Report {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(reporter, "reporter");
        Objects.requireNonNull(reported, "reported");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
    }

}
