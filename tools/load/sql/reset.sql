-- Limpa o que os cenários criam, para cada um começar do zero. Contas e perfis ficam (são o preparo da
-- execução, seed.js). rate_limit_bucket também limpa, para um cenário não herdar o saldo gasto do anterior.
truncate table round_decision, connection, round_seat, round, registration, event, rate_limit_bucket;
