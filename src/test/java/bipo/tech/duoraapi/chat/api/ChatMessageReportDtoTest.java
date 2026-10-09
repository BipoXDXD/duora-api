package bipo.tech.duoraapi.chat.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import bipo.tech.duoraapi.identity.AccountId;
import bipo.tech.duoraapi.trustsafety.FiledReport;
import bipo.tech.duoraapi.trustsafety.ReportReason;
import bipo.tech.duoraapi.trustsafety.ReportStatus;

/**
 * Em DEBUG, o Spring MVC registra o corpo lido e escrito pelo toString do DTO: o relato da denúncia de mensagem
 * não pode estar nele em posição nenhuma.
 */
class ChatMessageReportDtoTest {

    private static final UUID ID = UUID.fromString("01966c4e-7d1a-7c3e-9b5f-3f2a1c0d9e8b");

    @Test
    void theRequestDoesNotExposeTheDescriptionInToString() {
        var request = new ReportChatMessageRequest(ReportReason.OTHER, "ameaçou me encontrar");

        assertThat(request.toString()).doesNotContain("ameaçou");
    }

    @Test
    void theResponseDoesNotExposeTheDescriptionInToString() {
        var report = new FiledReport(ID, new AccountId(ID), ReportReason.OTHER, "ameaçou me encontrar",
                ReportStatus.OPEN, Instant.parse("2026-11-01T22:00:00Z"));

        assertThat(ChatMessageReportResponse.of(report).toString()).doesNotContain("ameaçou");
        assertThat(report.toString()).doesNotContain("ameaçou");
    }

}
