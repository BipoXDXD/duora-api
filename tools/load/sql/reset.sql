-- Limpa o que os cenários criam, para cada um começar do zero. Contas e perfis ficam (são o preparo da
-- execução, seed.js). rate_limit_bucket também limpa, para um cenário não herdar o saldo gasto do anterior.
-- chat_message cai com chat (on delete cascade), mas truncate não dispara cascade de linha: as duas entram.
truncate table chat_message, chat, round_decision, connection, round_seat, round, registration, event, rate_limit_bucket;
