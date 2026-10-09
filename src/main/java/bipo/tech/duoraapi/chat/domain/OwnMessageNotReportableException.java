package bipo.tech.duoraapi.chat.domain;

/**
 * Quem enviou a mensagem tentou denunciá-la. É pedido inválido, e não estado: a mensagem é sempre de quem a
 * enviou (docs/adr/0021).
 */
public class OwnMessageNotReportableException extends RuntimeException {

    public OwnMessageNotReportableException() {
        super("a message of your own cannot be reported");
    }

}
