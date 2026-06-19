create table if not exists atlas_user
(
    id            bigint primary key auto_increment,
    username      varchar(32)  not null,
    email         varchar(128) null,
    password_hash varchar(255) not null,
    created_at    datetime    not null default current_timestamp,
    updated_at    datetime    not null default current_timestamp on update current_timestamp,
    deleted       tinyint     not null default 0,
    unique key uk_user_username (username),
    unique key uk_user_email (email),
    key idx_user_deleted (deleted)
) engine = InnoDB default charset = utf8mb4;

create table if not exists notebook
(
    id          bigint primary key auto_increment,
    user_id     bigint       not null,
    parent_id   bigint       null,
    parent_key  bigint generated always as (coalesce(parent_id, 0)) stored,
    node_type   varchar(16)  not null default 'collection',
    name        varchar(64)  not null,
    description varchar(255) null,
    sort_order  int          not null default 0,
    created_at  datetime     not null default current_timestamp,
    updated_at  datetime     not null default current_timestamp on update current_timestamp,
    deleted     tinyint      not null default 0,
    active_key  bigint generated always as (case when deleted = 0 then 0 else id end) stored,
    key idx_notebook_user (user_id, deleted, updated_at),
    key idx_notebook_tree (user_id, parent_id, deleted, node_type, sort_order, name)
) engine = InnoDB default charset = utf8mb4;

create table if not exists note
(
    id          bigint primary key auto_increment,
    user_id     bigint       not null,
    notebook_id bigint       not null,
    title       varchar(128) not null,
    content     longtext     not null,
    summary     varchar(512) null,
    search_text longtext     null,
    created_at  datetime     not null default current_timestamp,
    updated_at  datetime     not null default current_timestamp on update current_timestamp,
    deleted     tinyint      not null default 0,
    key idx_note_user_notebook (user_id, notebook_id, deleted, updated_at),
    fulltext key ft_note_title_search (title, search_text) with parser ngram
) engine = InnoDB default charset = utf8mb4;

create table if not exists tag
(
    id         bigint primary key auto_increment,
    user_id    bigint      not null,
    name       varchar(32) not null,
    color      varchar(16) null,
    created_at datetime    not null default current_timestamp,
    updated_at datetime    not null default current_timestamp on update current_timestamp,
    deleted    tinyint     not null default 0,
    unique key uk_tag_user_name (user_id, name),
    key idx_tag_user (user_id, deleted)
) engine = InnoDB default charset = utf8mb4;

create table if not exists note_chunk
(
    id          bigint primary key auto_increment,
    user_id     bigint       not null,
    note_id     bigint       not null,
    chunk_index int          not null,
    content     longtext     not null,
    embedding   longtext     null,
    dim         int          null,
    model       varchar(128) null,
    provider_id bigint       null,
    status      tinyint      not null default 0,
    version     int          not null default 0,
    created_at  datetime     not null default current_timestamp,
    updated_at  datetime     not null default current_timestamp on update current_timestamp,
    unique key uk_note_chunk (note_id, chunk_index),
    key idx_note_chunk_user (user_id, note_id),
    key idx_note_chunk_status (status),
    key idx_note_chunk_dim (dim)
) engine = InnoDB default charset = utf8mb4;

create table if not exists paper_attachment
(
    id                bigint primary key auto_increment,
    user_id           bigint       not null,
    notebook_id       bigint       not null,
    note_id           bigint       not null,
    original_filename varchar(255) not null,
    stored_filename   varchar(255) not null,
    storage_path      varchar(512) not null,
    content_type      varchar(128) null,
    file_size         bigint       not null,
    extracted_text    longtext     null,
    created_at        datetime     not null default current_timestamp,
    updated_at        datetime     not null default current_timestamp on update current_timestamp,
    deleted           tinyint      not null default 0,
    key idx_paper_attachment_user (user_id, deleted, created_at),
    key idx_paper_attachment_note (note_id)
) engine = InnoDB default charset = utf8mb4;

create table if not exists library_item
(
    id                bigint primary key auto_increment,
    user_id           bigint       not null,
    notebook_id       bigint       not null,
    note_id           bigint       not null,
    title             varchar(128) not null,
    original_filename varchar(255) not null,
    stored_filename   varchar(255) not null,
    storage_path      varchar(512) not null,
    content_type      varchar(128) null,
    file_ext          varchar(32)  null,
    file_size         bigint       not null,
    category          varchar(64)  null,
    status            varchar(32)  not null,
    extracted_text    longtext     null,
    created_at        datetime     not null default current_timestamp,
    updated_at        datetime     not null default current_timestamp on update current_timestamp,
    deleted           tinyint      not null default 0,
    key idx_library_item_user (user_id, deleted, created_at),
    key idx_library_item_notebook (notebook_id, deleted, created_at),
    key idx_library_item_note (note_id)
) engine = InnoDB default charset = utf8mb4;

create table if not exists ai_provider
(
    id         bigint primary key auto_increment,
    name       varchar(64)  not null,
    base_url   varchar(255) not null,
    api_key    varchar(255) null,
    enabled    tinyint      not null default 1,
    remark     varchar(255) null,
    created_at datetime     not null default current_timestamp,
    updated_at datetime     not null default current_timestamp on update current_timestamp,
    unique key uk_provider_name (name)
) engine = InnoDB default charset = utf8mb4;

create table if not exists ai_model
(
    id          bigint primary key auto_increment,
    provider_id bigint       not null,
    kind        varchar(16)  not null,
    name        varchar(128) not null,
    alias       varchar(64)  null,
    dim         int          null,
    enabled     tinyint      not null default 1,
    remark      varchar(255) null,
    created_at  datetime     not null default current_timestamp,
    updated_at  datetime     not null default current_timestamp on update current_timestamp,
    unique key uk_model_provider_name_kind (provider_id, name, kind),
    key idx_model_kind_enabled (kind, enabled)
) engine = InnoDB default charset = utf8mb4;

create table if not exists ai_active
(
    id         bigint primary key auto_increment,
    scope      varchar(32) not null default 'system',
    kind       varchar(16) not null,
    model_id   bigint     not null,
    updated_at datetime   not null default current_timestamp on update current_timestamp,
    unique key uk_active_scope_kind (scope, kind)
) engine = InnoDB default charset = utf8mb4;

create table if not exists ai_agent
(
    id            bigint primary key auto_increment,
    name          varchar(64)  not null,
    model_id      bigint       not null,
    system_prompt longtext     not null,
    vcp_folder    varchar(128) null,
    enabled       tinyint      not null default 1,
    is_default    tinyint      not null default 0,
    created_at    datetime    not null default current_timestamp,
    updated_at    datetime    not null default current_timestamp on update current_timestamp,
    key idx_ai_agent_enabled (enabled, is_default, id)
) engine = InnoDB default charset = utf8mb4;

create table if not exists note_tag
(
    id         bigint primary key auto_increment,
    user_id    bigint   not null,
    note_id    bigint   not null,
    tag_id     bigint   not null,
    created_at datetime not null default current_timestamp,
    unique key uk_note_tag (note_id, tag_id),
    key idx_note_tag_user_note (user_id, note_id),
    key idx_note_tag_user_tag (user_id, tag_id)
) engine = InnoDB default charset = utf8mb4;

create table if not exists vcp_memory_draft
(
    id                   bigint primary key auto_increment,
    user_id              bigint       not null,
    note_id              bigint       not null,
    notebook_id          bigint       not null,
    title                varchar(128) not null,
    memory_content       longtext     not null,
    suggested_daily_note varchar(128) null,
    target_daily_note    varchar(128) null,
    status               varchar(24)  not null default 'pending',
    synced_path          varchar(512) null,
    created_at           datetime     not null default current_timestamp,
    updated_at           datetime     not null default current_timestamp on update current_timestamp,
    key idx_vcp_draft_user_status (user_id, status, updated_at),
    key idx_vcp_draft_note (note_id)
) engine = InnoDB default charset = utf8mb4;

create table if not exists deepwiki_page
(
    id           bigint primary key auto_increment,
    user_id      bigint       not null,
    notebook_id  bigint       not null,
    agent_id     bigint       null,
    mode         varchar(32)  not null,
    focus        varchar(128) null,
    focus_key    varchar(128) not null default '',
    title        varchar(255) not null,
    source_count int          not null default 0,
    markdown     longtext     not null,
    created_at   datetime     not null default current_timestamp,
    updated_at   datetime     not null default current_timestamp on update current_timestamp,
    unique key uk_deepwiki_page_key (user_id, notebook_id, mode, focus_key),
    key idx_deepwiki_page_notebook (user_id, notebook_id, updated_at)
) engine = InnoDB default charset = utf8mb4;

create table if not exists inbox_request
(
    id             bigint primary key auto_increment,
    user_id        bigint        not null,
    source_project varchar(128)  not null,
    title          varchar(255)  not null,
    description    varchar(1024) null,
    status         varchar(24)   not null default 'pending',
    imported_count int           not null default 0,
    failed_count   int           not null default 0,
    created_at     datetime      not null default current_timestamp,
    updated_at     datetime      not null default current_timestamp on update current_timestamp,
    key idx_inbox_request_user_status (user_id, status, updated_at)
) engine = InnoDB default charset = utf8mb4;

create table if not exists inbox_file
(
    id                bigint primary key auto_increment,
    request_id        bigint        not null,
    user_id           bigint        not null,
    original_filename varchar(255)  not null,
    relative_path     varchar(512)  null,
    stored_filename   varchar(255)  not null,
    storage_path      varchar(512)  not null,
    content_type      varchar(128)  null,
    file_size         bigint        not null,
    status            varchar(24)   not null default 'pending',
    note_id           bigint        null,
    library_item_id   bigint        null,
    error_message     varchar(1024) null,
    created_at        datetime      not null default current_timestamp,
    updated_at        datetime      not null default current_timestamp on update current_timestamp,
    key idx_inbox_file_request (request_id, status, id),
    key idx_inbox_file_user (user_id, status, updated_at)
) engine = InnoDB default charset = utf8mb4;
