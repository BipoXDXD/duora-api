-- Evidência da denúncia de uma mensagem do chat (módulo trustsafety, docs/adr/0021 e docs/adr/0015): uma
-- cópia da mensagem no instante da denúncia, para a moderação ler depois que o chat for expurgado (24 h após o
-- fim do evento). Quem enviou a mensagem é a conta denunciada (report.reported_account_id): o chat só deixa
-- denunciar mensagem do par.
-- Uma evidência por denúncia, na mesma linha de vida dela: apagar a denúncia apaga a cópia (cascade), e a
-- retenção é a das denúncias, ainda pendente com o usuário (docs/adr/0015, pendência 2; docs/adr/0023).
-- chat_id, event_id e round_number são só referência, sem FK: o chat é apagado pelo expurgo, e trustsafety não
-- conhece o esquema do chat nem o dos eventos (como em round_decision, docs/adr/0019).
-- O texto é dado sensível: nunca vai para log. Os limites repetem os da mensagem (1 a 500 caracteres, posição
-- de 1 a 300) e mudam junto com o chat.
create table report_message_evidence (
    report_id    uuid        primary key references report (id) on delete cascade,
    chat_id      uuid        not null,
    event_id     uuid        not null,
    round_number integer     not null check (round_number between 1 and 100),
    seq          integer     not null check (seq between 1 and 300),
    body         text        not null check (char_length(body) between 1 and 500),
    sent_at      timestamptz not null
);
