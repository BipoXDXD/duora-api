package bipo.tech.duoraapi.chat.application;

/**
 * Quem chama não tem chat nessa rodada: ficou de fora, não estava no sorteio, ou a rodada ou o evento não
 * existem. A resposta é a mesma em todos os casos, e não diz quem conversa com quem.
 */
public class ChatNotFoundException extends RuntimeException {

    public ChatNotFoundException() {
        super("the caller has no chat in this round");
    }

}
