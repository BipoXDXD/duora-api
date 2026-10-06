package bipo.tech.duoraapi.trustsafety.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import bipo.tech.duoraapi.trustsafety.domain.ReportReason;

/**
 * Em DEBUG, o Spring MVC registra o corpo lido pelo toString do DTO, truncado em 100 caracteres. O
 * truncamento não é proteção: o relato não pode estar no toString em posição nenhuma.
 */
class FileReportRequestTest {

    @Test
    void doesNotExposeTheDescriptionInToString() {
        var request = new FileReportRequest(UUID.fromString("01966c4e-7d1a-7c3e-9b5f-3f2a1c0d9e8b"),
                ReportReason.OTHER, "ameaçou me encontrar");

        assertThat(request.toString()).doesNotContain("ameaçou");
    }

}
