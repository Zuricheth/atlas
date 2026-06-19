create table if not exists note_history
(
    id           bigint primary key auto_increment,
    note_id      bigint       not null,
    user_id      bigint       not null,
    title        varchar(128) null,
    content      longtext     null,
    summary      varchar(512) null,
    note_version int          not null,
    created_at   datetime     not null default current_timestamp,
    constraint fk_note_history_note foreign key (note_id) references note (id) on delete cascade
) engine = InnoDB default charset = utf8mb4;

create index idx_note_history_note on note_history (note_id, note_version desc);
