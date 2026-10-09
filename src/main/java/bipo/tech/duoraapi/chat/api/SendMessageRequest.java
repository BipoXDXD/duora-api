package bipo.tech.duoraapi.chat.api;

import jakarta.validation.constraints.NotNull;

import bipo.tech.duoraapi.chat.domain.ChatMessageText;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Só o texto. Chat, posição, remetente e horário são do servidor: chave desconhecida é 400
 * (fail-on-unknown-properties). Os limites do texto ficam no domínio.
 */
@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
record SendMessageRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = ChatMessageText.MAX_LENGTH,
                description = "O texto, em parágrafos, sem caracteres invisíveis nem de controle. Espaço nas "
                        + "pontas é removido e o texto é normalizado em NFC antes de contar os caracteres. Link "
                        + "e marcação são texto comum.")
        @NotNull String text) {

    /** O Spring MVC registra o corpo lido por este toString em DEBUG: a conversa fica de fora. */
    @Override
    public String toString() {
        return "SendMessageRequest[text=redacted]";
    }

}
