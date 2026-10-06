-- Conta interna (módulo identity, docs/adr/0011): nasce no primeiro acesso autenticado e é
-- identificada pela identidade externa, emissor + sujeito (no Entra, a claim oid), nunca pelo e-mail.
-- A UNIQUE é o que garante uma conta só por identidade, inclusive com acessos simultâneos.
-- Só o necessário para identificar a pessoa: e-mail, nome e papéis continuam no Entra.
create table account (
    id         uuid        primary key default uuidv7(),
    issuer     text        not null check (length(issuer) between 1 and 2048),
    subject    text        not null check (length(subject) between 1 and 255),
    created_at timestamptz not null,
    constraint account_external_identity_key unique (issuer, subject)
);
