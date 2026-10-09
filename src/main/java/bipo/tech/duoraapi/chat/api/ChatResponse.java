package bipo.tech.duoraapi.chat.api;

import java.util.UUID;

import bipo.tech.duoraapi.chat.application.ChatView;
import io.swagger.v3.oas.annotations.media.Schema;

/** O chat de quem chama na rodada. Não diz com quem, que a pessoa já lê no pareamento, nem por que fechou. */
@Schema(name = "Chat")
record ChatResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = ApiSchemas.UUID_LENGTH,
                maxLength = ApiSchemas.UUID_LENGTH,
                description = "Id opaco do chat, para casar avisos de tempo real com a tela")
        UUID chatId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Se aceita mensagens agora. Fechado, só leitura, pelo motivo que for: a rodada "
                        + "seguinte começou, o evento acabou, o chat chegou a " + ApiSchemas.MAX_MESSAGES + " mensagens ou há um bloqueio")
        boolean open,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "int32", minimum = "0",
                maximum = ApiSchemas.MAX_MESSAGES,
                description = "A posição da última mensagem, ou 0 sem nenhuma: a versão do chat")
        int lastSeq) {

    static ChatResponse of(ChatView view) {
        return new ChatResponse(view.chatId(), view.open(), view.lastSeq());
    }

}
