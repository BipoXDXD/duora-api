package bipo.tech.duoraapi.trustsafety.adapter;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import bipo.tech.duoraapi.identity.AccountId;
import bipo.tech.duoraapi.trustsafety.ChatMessageEvidence;
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

    private static final String INSERT_REPORT = """
            insert into report (reporter_account_id, reported_account_id, reason, description, status, created_at)
            values (:reporter, :reported, :reason, :description, :status, :createdAt)
            returning %s
            """.formatted(COLUMNS);

    private final JdbcClient jdbcClient;

    JdbcReportRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Report add(NewReport report) {
        return translatingUnknownAccount(() -> withReportParams(jdbcClient.sql(INSERT_REPORT), report)
                .query(JdbcReportRepository::toReport)
                .single());
    }

    /**
     * Um comando só, com as duas inserções em CTEs: o PostgreSQL grava as duas ou nenhuma, sem transação aberta
     * por aqui. Quem enviou a mensagem é a conta denunciada, já gravada na denúncia.
     */
    @Override
    public Report addWithEvidence(NewReport report, ChatMessageEvidence evidence) {
        return translatingUnknownAccount(() -> withReportParams(jdbcClient.sql("""
                        with filed as (%s),
                             evidence as (
                                 insert into report_message_evidence (report_id, chat_id, event_id, round_number, seq,
                                                                      body, sent_at)
                                 select id, :chatId, :eventId, :roundNumber, :seq, :body, :sentAt from filed)
                        select %s from filed
                        """.formatted(INSERT_REPORT, COLUMNS)), report)
                .param("chatId", evidence.chatId())
                .param("eventId", evidence.eventId())
                .param("roundNumber", evidence.roundNumber())
                .param("seq", evidence.seq())
                .param("body", evidence.text())
                .param("sentAt", OffsetDateTime.ofInstant(evidence.sentAt(), ZoneOffset.UTC))
                .query(JdbcReportRepository::toReport)
                .single());
    }

    private static JdbcClient.StatementSpec withReportParams(JdbcClient.StatementSpec statement, NewReport report) {
        return statement
                .param("reporter", report.reporter().value())
                .param("reported", report.reported().value())
                .param("reason", report.reason().name())
                .param("description", report.description().map(ReportDescription::value).orElse(null))
                .param("status", ReportStatus.OPEN.name())
                .param("createdAt", OffsetDateTime.ofInstant(report.filedAt(), ZoneOffset.UTC));
    }

    private static Report translatingUnknownAccount(Supplier<Report> insert) {
        try {
            return insert.get();
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
