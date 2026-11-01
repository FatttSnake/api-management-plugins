-- Files the plugin stored, one row per location-addressed object. Applied to the
-- administrator-configured MySQL datasource.
create table t_p_filebox_file
(
    id          varchar(36)  not null,
    owner_id    bigint       not null,
    name        varchar(255) not null,
    size        bigint       not null,
    path        varchar(512) not null,
    create_time bigint       not null,
    primary key (id)
);

create index idx_t_p_filebox_file_owner on t_p_filebox_file (owner_id, create_time);
