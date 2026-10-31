-- Snapshots the plugin stored, keyed by content so identical text shares one row. Applied to
-- the SQLite datasource the gateway provisions for the plugin.
create table t_p_filebox_snapshot
(
    content_key varchar(64) not null,
    owner_id    bigint      not null,
    size        bigint      not null,
    create_time bigint      not null,
    primary key (content_key)
);
