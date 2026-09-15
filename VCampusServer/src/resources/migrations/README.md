# Course management migrations

Apply `V001` through `V007` in numeric order after the repository's canonical
`VCampusServer/src/resources/init.sql` schema has been created. These migration
files are the authoritative course-module schema changes; do not duplicate them
into `init.sql`.

Database connections set their MySQL session time zone to UTC. Course timestamps
whose names end in `_utc` must therefore be written and compared in UTC.
