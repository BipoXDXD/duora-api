package bipo.tech.duoraapi.chat.api;

import java.time.Instant;

import bipo.tech.duoraapi.chat.domain.ChatMessage;
import bipo.tech.duoraapi.chat.domain.ChatMessageText;
import bipo.tech.duoraapi.identity.AccountId;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Uma mensagem vista por uma das duas pessoas do chat. {@code fromMe} no lugar do id do remetente: não há
 * terceiro no chat (docs/adr/0021).
 */
@Schema(name = "ChatMessage")
record ChatMessageResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "int32", minimum = "1",
                maximum = ApiSchemas.MAX_MESSAGES,
                description = "A posição da mensagem no chat, sem lacunas: a ordem de chegada ao servidor")
        int seq,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Se quem chama enviou a mensagem")
        boolean fromMe,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = ChatMessageText.MAX_LENGTH,
                description = "O texto como foi gravado; mostre como texto comum, nunca como HTML ou link")
        String text,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = ApiSchemas.INSTANT_MAX_LENGTH,
                description = "Quando o servidor gravou a mensagem; não define a ordem")
        Instant sentAt) {

    static ChatMessageResponse of(ChatMessage message, AccountId viewer) {
        return new ChatMessageResponse(message.seq(), message.sentBy(viewer), message.text().value(),
                message.sentAt());
    }

    @Override
    public String toString() {
        return "ChatMessageResponse[seq=" + seq + ", fromMe=" + fromMe + ", text=redacted, sentAt=" + sentAt + "]";
    }

}
