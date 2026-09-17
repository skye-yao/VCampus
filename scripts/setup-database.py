"""Install the application schema in order with mysql.exe (Python 3, MySQL 8)."""
from pathlib import Path
import argparse
import datetime
import hashlib
import os
import re
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
SQL = ROOT / 'VCampusServer/src/resources'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=Path, default=SQL / 'db.properties')
    parser.add_argument('--demo', action='store_true', help='Load course demo data only into an empty course module')
    opt = parser.parse_args()
    props = dict(line.strip().split('=', 1) for line in opt.config.read_text(encoding='utf-8-sig').splitlines()
                 if line.strip() and not line.lstrip().startswith('#') and '=' in line)
    match = re.match(r'jdbc:mysql://([^/:]+)(?::(\d+))?/([A-Za-z0-9_]+)(?:\?|$)', props['db.url'])
    if not match:
        raise RuntimeError('Unsupported db.url; expected jdbc:mysql://host:port/database')
    host, port, database = match.groups()
    mysql, dump = shutil.which('mysql'), shutil.which('mysqldump')
    if not mysql or not dump:
        raise RuntimeError('Add the MySQL bin directory to PATH (mysql and mysqldump are required).')
    env = os.environ.copy()
    env['MYSQL_PWD'] = props.get('db.password', '')
    connection = ['--host=' + host, '--port=' + (port or '3306'), '--user=' + props['db.username'],
                  '--default-character-set=utf8mb4']

    def execute(sql, selected=True):
        prefix = f'USE `{database}`;\n' if selected else ''
        result = subprocess.run([mysql, *connection, '--batch', '--skip-column-names'],
                                input=(prefix + sql).encode('utf-8'), capture_output=True, env=env)
        if result.returncode:
            raise RuntimeError(result.stderr.decode('utf-8', errors='replace').strip())
        return result.stdout.decode('utf-8', errors='replace').strip()

    exists = execute(f"SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='{database}';", False) == '1'
    if exists:
        backup_dir = ROOT / '.codex-tmp/database-backups'
        backup_dir.mkdir(parents=True, exist_ok=True)
        backup = backup_dir / (database + '-' + datetime.datetime.now().strftime('%Y%m%d-%H%M%S-%f') + '.sql')
        with backup.open('wb') as output:
            result = subprocess.run([dump, *connection, '--single-transaction', '--routines', '--triggers',
                                     '--hex-blob', '--no-tablespaces', '--set-gtid-purged=OFF', database],
                                    stdout=output, stderr=subprocess.PIPE, env=env)
        if result.returncode:
            raise RuntimeError('Backup failed: ' + result.stderr.decode(errors='replace'))
        print('Backup:', backup, flush=True)
    execute(f'CREATE DATABASE IF NOT EXISTS `{database}` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;', False)

    def has_table(name):
        return execute(f"SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='{name}';") == '1'

    # Do not guess the migration version of a manually upgraded database.
    if has_table('course') and not has_table('_vcampus_sql_history'):
        raise RuntimeError('Existing course schema has no migration history. Inspect its version before upgrading; no migrations were applied.')
    execute('CREATE TABLE IF NOT EXISTS `_vcampus_sql_history` (filename VARCHAR(160) PRIMARY KEY, sha256 CHAR(64) NOT NULL, applied_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP) ENGINE=InnoDB;')

    def apply(file):
        name = file.relative_to(SQL).as_posix()
        contents = file.read_bytes()
        digest = hashlib.sha256(contents).hexdigest()
        previous = execute(f"SELECT sha256 FROM `_vcampus_sql_history` WHERE filename='{name}';")
        if previous:
            if previous != digest:
                raise RuntimeError(f'{name} changed after being applied; inspect before rerunning.')
            print('SKIP:', name, flush=True)
            return
        print('APPLY:', name, flush=True)
        execute(contents.decode('utf-8-sig').replace('`virtual_campus`', f'`{database}`'))
        execute(f"INSERT INTO `_vcampus_sql_history` (filename,sha256) VALUES ('{name}','{digest}');")

    if not has_table('tbl_user'):
        apply(SQL / 'init.sql')
    else:
        print('Base schema exists; retaining existing application data.', flush=True)
    for file in sorted((SQL / 'migrations').glob('*.sql')):
        apply(file)
    if opt.demo:
        previous = execute("SELECT COUNT(*) FROM `_vcampus_sql_history` WHERE filename='seed/seed-course-demo.sql';")
        if previous == '0' and execute('SELECT COUNT(*) FROM course;') != '0':
            raise RuntimeError('Course data already exists; demo import refused to preserve it.')
        apply(SQL / 'seed/seed-course-demo.sql')
    print('Database ready:', database, flush=True)


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, OSError, KeyError) as error:
        print('ERROR:', error, file=sys.stderr)
        print('Stopped. MySQL DDL may already be committed; inspect the failed migration before retrying.', file=sys.stderr)
        sys.exit(1)
