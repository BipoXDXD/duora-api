-- Perfil (módulo profiles, docs/adr/0011): um por conta, por isso a chave é o próprio id da conta, que
-- também serve de índice à FK. A linha nasce vazia na primeira edição; antes dela, o perfil é lido como
-- vazio e na versão 0. version conta as edições gravadas e é o ETag do perfil.
-- on delete restrict: apagar uma conta exige que cada módulo apague antes os próprios dados pessoais
-- (fluxo de exclusão ainda pendente, docs/adr/0011), em vez de uma cascata que o código não vê.
-- Os CHECK repetem os limites do domínio como última defesa; idade e regras de edição ficam no domínio.
create table profile (
    account_id   uuid   primary key references account (id) on delete restrict,
    display_name text   check (length(display_name) between 1 and 50),
    birth_date   date   check (birth_date >= date '1900-01-01'),
    bio          text   check (length(bio) between 1 and 300),
    -- Unidade da federação (ISO 3166-2): região aproximada, nunca localização precisa.
    region       text   check (region in (
                     'BR-AC', 'BR-AL', 'BR-AP', 'BR-AM', 'BR-BA', 'BR-CE', 'BR-DF', 'BR-ES', 'BR-GO',
                     'BR-MA', 'BR-MT', 'BR-MS', 'BR-MG', 'BR-PA', 'BR-PB', 'BR-PR', 'BR-PE', 'BR-PI',
                     'BR-RJ', 'BR-RN', 'BR-RS', 'BR-RO', 'BR-RR', 'BR-SC', 'BR-SP', 'BR-SE', 'BR-TO')),
    version      bigint not null default 0 check (version >= 0)
);
