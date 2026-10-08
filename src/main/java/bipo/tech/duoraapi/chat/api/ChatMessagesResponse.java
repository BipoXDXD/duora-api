package bipo.tech.duoraapi.chat.api;

import java.util.List;

import bipo.tech.duoraapi.chat.application.MessagesPage;
import bipo.tech.duoraapi.identity.AccountId;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Uma página de mensagens. O cursor é a posição, transparente, e não o pageToken opaco da docs/adr/0005: a
 * posição é a versão do chat, e o cliente precisa dela para achar lacunas (docs/adr/0021).
 *
 * @param nextAfterSeq null quando não há mais mensagens gravadas depois desta página
 */
@Schema(name = "ChatMessages")
record ChatMessagesResponse(
        @ArraySchema(maxItems = ChatParameters.MAX_PAGE_SIZE,
                arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                        description = "Mensagens depois de afterSeq, em ordem crescente de posição"))
        List<ChatMessageResponse> items,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"integer", "null"}, format = "int32",
                minimum = "1", maximum = ApiSchemas.MAX_MESSAGES,
                description = "O afterSeq da próxima página, ou null quando esta chega à última mensagem")
        Integer nextAfterSeq) {

    static ChatMessagesResponse of(MessagesPage page, AccountId viewer) {
        var items = page.messages().stream()
                .map(message -> ChatMessageResponse.of(message, viewer))
                .toList();
        return new ChatMessagesResponse(items,
                page.nextAfterSeq().isPresent() ? page.nextAfterSeq().getAsInt() : null);
    }

}
