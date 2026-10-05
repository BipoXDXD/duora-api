create table waitlist_entry (
    id        bigint generated always as identity primary key,
    email     text        not null unique check (length(email) <= 254),
    joined_at timestamptz not null
);
