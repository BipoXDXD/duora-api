package bipo.tech.duoraapi.trustsafety;

import static bipo.tech.duoraapi.RateLimitTestSupport.clearBuckets;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import bipo.tech.duoraapi.AccountTables;
import bipo.tech.duoraapi.InvalidFieldException;
import bipo.tech.duoraapi.TestcontainersConfiguration;
import bipo.tech.duoraapi.identity.AccountId;

/**
 * A API publicada que o chat usa para denunciar uma mensagem (docs/adr/0021): a denúncia e a cópia da mensagem
 * são gravadas juntas, ou nenhuma das duas, e a cópia não depende do chat continuar existindo.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ReportsWithEvidenceIT {

    private static final UUID CHAT = UUID.fromString("01966c4e-7d1a-7c3e-9b5f-3f2a1c0d9e8b");
    private static final UUID EVENT = UUID.fromString("01966c4e-7d1a-7c3e-9b5f-3f2a1c0d9e8c");
    private static final Instant SENT_AT = Instant.parse("2026-10-06T12:34:56.123456Z");

    @Autowired
    private Reports reports;

    @Autowired
    private JdbcClient jdbcClient;

    private AccountId ana;
    private AccountId bruno;

    @BeforeEach
    void setUp() {
        AccountTables.deleteAccountsAndTheirData(jdbcClient);
        clearBuckets(jdbcClient);
        ana = insertAccount("oid-ana");
        bruno = insertAccount("oid-bruno");
    }

    @Test
    void aReportWithEvidenceKeepsACopyOfTheMessage() {
        var evidence = new ChatMessageEvidence(CHAT, EVENT, 2, 7, "mensagem ofensiva", SENT_AT);

        FiledReport report = reports.fileWithEvidence(ana, bruno, ReportReason.HARASSMENT, "na rodada 2", evidence);

        assertThat(report.reported()).isEqualTo(bruno);
        assertThat(report.reason()).isEqualTo(ReportReason.HARASSMENT);
        assertThat(report.description()).isEqualTo("na rodada 2");
        assertThat(report.status()).isEqualTo(ReportStatus.OPEN);
        assertThat(jdbcClient.sql("""
                        select reporter_account_id = :ana and reported_account_id = :bruno and reason = 'HARASSMENT'
                          from report where id = :id
                        """)
                .param("ana", ana.value()).param("bruno", bruno.value()).param("id", report.id())
                .query(Boolean.class).single()).isTrue();
        assertThat(evidenceOf(report.id())).isEqualTo(
                CHAT + " " + EVENT + " 2 7 mensagem ofensiva " + SENT_AT);
    }

    @Test
    void anInvalidReportWritesNothingAndSpendsNoQuota() {
        var evidence = new ChatMessageEvidence(CHAT, EVENT, 1, 1, "oi", SENT_AT);

        assertThatThrownBy(() -> reports.fileWithEvidence(ana, bruno, ReportReason.OTHER, "   ", evidence))
                .isInstanceOf(InvalidFieldException.class);

        assertThat(count("report")).isZero();
        assertThat(count("report_message_evidence")).isZero();
        assertThat(count("rate_limit_bucket")).isZero();
    }

    /** O chat é apagado pelo expurgo; a evidência não aponta para ele, então sobrevive. */
    @Test
    void theEvidenceDoesNotNeedTheChatToExist() {
        var evidence = new ChatMessageEvidence(UUID.randomUUID(), UUID.randomUUID(), 1, 1, "oi", SENT_AT);

        FiledReport report = reports.fileWithEvidence(ana, bruno, ReportReason.SCAM_OR_SPAM, null, evidence);

        assertThat(report.description()).isNull();
        assertThat(count("report_message_evidence")).isOne();
    }

    @Test
    void deletingTheReportDeletesItsEvidence() {
        var evidence = new ChatMessageEvidence(CHAT, EVENT, 1, 1, "oi", SENT_AT);
        FiledReport report = reports.fileWithEvidence(ana, bruno, ReportReason.SCAM_OR_SPAM, null, evidence);

        jdbcClient.sql("delete from report where id = :id").param("id", report.id()).update();

        assertThat(count("report_message_evidence")).isZero();
    }

    private String evidenceOf(UUID reportId) {
        return jdbcClient.sql("""
                        select chat_id, event_id, round_number, seq, body, sent_at
                          from report_message_evidence where report_id = :id
                        """)
                .param("id", reportId)
                .query((row, number) -> row.getString("chat_id") + " " + row.getString("event_id") + " "
                        + row.getInt("round_number") + " " + row.getInt("seq") + " " + row.getString("body") + " "
                        + row.getObject("sent_at", OffsetDateTime.class).toInstant())
                .single();
    }

    private long count(String table) {
        return jdbcClient.sql("select count(*) from " + table).query(Long.class).single();
    }

    private AccountId insertAccount(String subject) {
        return new AccountId(jdbcClient.sql("""
                        insert into account (issuer, subject, created_at) values ('https://issuer.example', :subject, now())
                        returning id
                        """)
                .param("subject", subject)
                .query(UUID.class).single());
    }

}
