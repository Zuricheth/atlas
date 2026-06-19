alter table notebook
    add column if not exists parent_key bigint generated always as (coalesce(parent_id, 0)) stored;

alter table notebook
    add column if not exists active_key bigint generated always as (case when deleted = 0 then 0 else id end) stored;

create unique index uk_notebook_active_sibling
    on notebook (user_id, parent_key, node_type, name, active_key);
