# Course management migrations

Apply `V001` through `V007` in numeric order after the repository's canonical
`VCampusServer/src/resources/init.sql` schema has been created. These migration
files are the authoritative course-module schema changes; do not duplicate them
into `init.sql`.

Database connections set their MySQL session time zone to UTC. Course timestamps
whose names end in `_utc` must therefore be written and compared in UTC.

从001开始补充课程模块，前提是基础库已经建好（即已经运行过 `../init.sql`）；否则先运行 `init.sql` 再回来。
完整的建库步骤（从头构建、补充构建、测试库、常见报错）见 `docs/数据库构建.md`。

要点：`init.sql` 里写死了 `CREATE DATABASE`/`USE virtual_campus`，要装到别的库名必须先改这两行。
`V003` 与 `V004` 含裸 `ALTER`，只能在缺列的空库上跑一次；其余迁移可重复执行。
