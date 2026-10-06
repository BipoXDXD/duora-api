package bipo.tech.duoraapi.trustsafety.api;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

import bipo.tech.duoraapi.trustsafety.domain.ReportReason;

/**
 * Só o que quem denuncia informa. Id, autoria, estado e data são do servidor: chave desconhecida é 400
 * (fail-on-unknown-properties). Os limites da descrição ficam no domínio.
 */
record FileReportRequest(@NotNull UUID reportedAccountId, @NotNull ReportReason reason, String description) {
}
