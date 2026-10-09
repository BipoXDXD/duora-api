package bipo.tech.duoraapi.trustsafety.domain;

import java.util.Optional;
import java.util.UUID;

import bipo.tech.duoraapi.identity.AccountId;
import bipo.tech.duoraapi.trustsafety.ReportStatus;

/** As denúncias gravadas. */
public interface ReportRepository {

    /**
     * Grava a denúncia no estado inicial, {@link ReportStatus#OPEN}, com id novo.
     *
     * @throws UnknownAccountException se a conta denunciada não existe
     */
    Report add(NewReport report);

    /** A denúncia, só se foi {@code reporter} quem a fez: a de outra pessoa não existe para ele. */
    Optional<Report> findFiledBy(AccountId reporter, UUID id);

}
