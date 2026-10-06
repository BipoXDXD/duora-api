-- PK bigint vira UUIDv7, que é também o ID público (docs/adr/0004). O default volátil é avaliado
-- linha a linha, então as linhas existentes ganham ids distintos na própria migration.
alter table waitlist_entry drop constraint waitlist_entry_pkey;
alter table waitlist_entry drop column id;
alter table waitlist_entry add column id uuid not null default uuidv7();
alter table waitlist_entry add primary key (id);
