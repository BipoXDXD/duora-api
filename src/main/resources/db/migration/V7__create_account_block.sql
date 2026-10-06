-- Bloqueio entre contas (módulo trustsafety, docs/adr/0015). Tem direção: cada lado bloqueia e
-- desbloqueia só o próprio. A chave primária é o par, o que garante um bloqueio só por par inclusive com
-- pedidos simultâneos (insert ... on conflict do nothing), e serve de índice à FK de quem bloqueia, à
-- lista "quem eu bloqueei" e à pergunta "estão bloqueados em qualquer direção?".
-- on delete restrict, como em profile (docs/adr/0011): apagar uma conta exige que cada módulo apague os
-- próprios dados antes. A FK é só integridade: trustsafety não lê nem escreve a tabela account.
create table account_block (
    blocker_account_id uuid        not null references account (id) on delete restrict,
    blocked_account_id uuid        not null references account (id) on delete restrict,
    created_at         timestamptz not null,
    primary key (blocker_account_id, blocked_account_id),
    constraint account_block_not_self check (blocker_account_id <> blocked_account_id)
);

-- A chave primária começa por quem bloqueia; a FK de quem foi bloqueado precisa do próprio índice.
create index account_block_blocked_account_id_idx on account_block (blocked_account_id);
