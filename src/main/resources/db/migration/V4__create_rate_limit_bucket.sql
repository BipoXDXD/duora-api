-- Estado dos buckets do Bucket4j, compartilhado entre réplicas (docs/adr/0006). O formato das colunas
-- é o que o Bucket4jPostgreSQL espera; a chave é "<limite>:<cliente>", como "join-waitlist:203.0.113.7".
-- expires_at (epoch em ms) é preenchido pelo Bucket4j e permite apagar o que já se repôs por inteiro.
create table rate_limit_bucket (
    id         text primary key check (length(id) <= 200),
    state      bytea,
    expires_at bigint
);
