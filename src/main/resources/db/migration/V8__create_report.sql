-- Denúncia de uma conta por outra (módulo trustsafety, docs/adr/0015), para a moderação. Nasce OPEN;
-- os próximos estados entram com a moderação, ainda pendente com o usuário. Retenção da denúncia e do
-- relato também está pendente: por enquanto nada é apagado.
-- id em UUIDv7 (docs/adr/0004): só quem denunciou o vê, então o instante legível no id não revela nada
-- que ele não saiba.
-- on delete restrict nas duas contas, como em account_block: a exclusão de conta vai decidir o que fazer
-- com denúncias feitas e recebidas, que podem ser evidência.
-- Os motivos repetem ReportReason: a lista muda junto com o código, por isso CHECK e não lookup table.
create table report (
    id                  uuid        primary key default uuidv7(),
    reporter_account_id uuid        not null references account (id) on delete restrict,
    reported_account_id uuid        not null references account (id) on delete restrict,
    reason              text        not null check (reason in (
                            'HARASSMENT', 'HATE_SPEECH', 'SEXUAL_CONTENT', 'VIOLENCE_OR_THREAT', 'SCAM_OR_SPAM',
                            'FAKE_PROFILE', 'SUSPECTED_MINOR', 'OTHER')),
    description         text        check (length(description) between 1 and 1000),
    status              text        not null check (status in ('OPEN')),
    created_at          timestamptz not null,
    constraint report_not_self check (reporter_account_id <> reported_account_id),
    constraint report_other_needs_description check (reason <> 'OTHER' or description is not null)
);

-- Índices das FKs: a leitura da própria denúncia é pela chave primária mais o dono, e a futura fila de
-- moderação vai agrupar pela conta denunciada.
create index report_reporter_account_id_idx on report (reporter_account_id);
create index report_reported_account_id_idx on report (reported_account_id);
