package bipo.tech.duoraapi.chat.application;

/** Não há mensagem nessa posição do chat de quem chama. */
public class MessageNotFoundException extends RuntimeException {

    public MessageNotFoundException() {
        super("there is no message at this position of the chat");
    }

}
