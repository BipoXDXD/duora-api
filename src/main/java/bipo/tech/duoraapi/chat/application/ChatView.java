package bipo.tech.duoraapi.chat.application;

import java.util.Objects;
import java.util.UUID;

/**
 * O estado do chat para quem chama, calculado na hora (docs/adr/0021).
 *
 * @param open se aceita mensagens agora; fechado pelo motivo que for, sem dizer qual
 * @param lastSeq a sequência da última mensagem, ou 0 sem nenhuma: a versão do chat para o cliente
 */
public record ChatView(UUID chatId, boolean open, int lastSeq) {

    public ChatView {
        Objects.requireNonNull(chatId, "chatId");
    }

}
