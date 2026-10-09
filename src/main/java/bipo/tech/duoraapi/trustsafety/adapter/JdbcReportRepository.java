package bipo.tech.duoraapi.trustsafety.adapter;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import bipo.tech.duoraapi.identity.AccountId;
import bipo.tech.duoraapi.trustsafety.ReportReason;
import bipo.tech.duoraapi.trustsafety.ReportStatus;
import bipo.tech.duoraapi.trustsafety.domain.NewReport;
import bipo.tech.duoraapi.trustsafety.domain.Report;
import bipo.tech.duoraapi.trustsafety.domain.ReportDescription;
import bipo.tech.duoraapi.trustsafety.domain.ReportRepository;
import bipo.tech.duoraapi.trustsafety.domain.UnknownAccountException;

/** Denúncias na tabela report, por SQL; o id vem do default uuidv7() do banco. */
@Repository
class JdbcReportRepository implements ReportRepository {

    private static final String COLUMNS =
            "id, reporter_account_id, reported_account_id, reason, description, status, created_at";

    private final JdbcClient jdbcClient;

    JdbcReportRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Report add(NewReport report) {
        try {
            return jdbcClient.sql("""
                            insert into report (reporter_account_id, reported_account_id, reason, description, status,
                                                created_at)
                            values (:reporter, :reported, :reason, :description, :status, :createdAt)
                            returning %s
                            """.formatted(COLUMNS))
                    .param("reporter", report.reporter().value())
                    .param("reported", report.reported().value())
                    .param("reason", report.reason().name())
                    .param("description", report.description().map(ReportDescription::value).orElse(null))
                    .param("status", ReportStatus.OPEN.name())
                    .param("createdAt", OffsetDateTime.ofInstant(report.filedAt(), ZoneOffset.UTC))
                    .query(JdbcReportRepository::toReport)
                    .single();
        } catch (DataIntegrityViolationException e) {
            // Quem denuncia é a conta autenticada, que existe: a FK que falha é a da conta denunciada.
            throw SqlStates.isForeignKeyViolation(e) ? new UnknownAccountException() : e;
        }
    }

    @Override
    public Optional<Report> findFiledBy(AccountId reporter, UUID id) {
        return jdbcClient.sql("""
                        select %s from report
                        where id = :id and reporter_account_id = :reporter
                        """.formatted(COLUMNS))
                .param("id", id)
                .param("reporter", reporter.value())
                .query(JdbcReportRepository::toReport)
                .optional();
    }

    private static Report toReport(ResultSet row, int rowNumber) throws SQLException {
        String description = row.getString("description");
        return new Report(
                row.getObject("id", UUID.class),
                new AccountId(row.getObject("reporter_account_id", UUID.class)),
                new AccountId(row.getObject("reported_account_id", UUID.class)),
                ReportReason.valueOf(row.getString("reason")),
                description == null ? null : new ReportDescription(description),
                ReportStatus.valueOf(row.getString("status")),
                row.getObject("created_at", OffsetDateTime.class).toInstant());
    }

}
