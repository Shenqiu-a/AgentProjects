"""在独立临时 PostgreSQL 上执行后端测试；不连接开发数据库，不删除临时目录。"""
import argparse
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--postgres-bin', type=Path, required=True)
    parser.add_argument('--offline', action='store_true')
    args = parser.parse_args()
    project = Path(__file__).resolve().parent.parent
    work = project / 'data/local/s1-verification' / uuid.uuid4().hex
    work.mkdir(parents=True)
    pgdata = work / 'postgres'
    flags = getattr(subprocess, 'CREATE_NO_WINDOW', 0)
    suffix = '.exe' if os.name == 'nt' else ''
    def pg(name): return args.postgres_bin / (name + suffix)
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        port = sock.getsockname()[1]
    env = os.environ.copy()
    for key in list(env):
        if key.startswith(('DATABASE_', 'AUTH_DATABASE_', 'ORDER_DATABASE_', 'AGENT_DATABASE_',
                           'KNOWLEDGE_DATABASE_', 'REDIS_TEST_', 'S1_ORDER_TEST_', 'S1_AGENT_TEST_')):
            env.pop(key)
    process = None
    pglog = None
    print('Logs:', work)
    with (work / 'verification.log').open('w', encoding='utf-8') as log:
        def run(command, check=True):
            result = subprocess.run([str(x) for x in command], cwd=project, env=env,
                                    stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
            log.flush()
            if check and result.returncode:
                raise RuntimeError('Command failed: ' + str(command[0]) + '; inspect verification.log')
            return result.returncode
        try:
            run([pg('initdb'), '-D', pgdata, '-U', 's1tester', '-A', 'trust', '--encoding=UTF8', '--no-locale'])
            pglog = (work / 'postgres.log').open('w', encoding='utf-8')
            process = subprocess.Popen([str(pg('postgres')), '-D', str(pgdata), '-h', '127.0.0.1', '-p', str(port)],
                                       stdout=pglog, stderr=subprocess.STDOUT, creationflags=flags)
            for _ in range(100):
                if process.poll() is not None:
                    raise RuntimeError('Temporary PostgreSQL exited; inspect postgres.log')
                result = subprocess.run([str(pg('pg_isready')), '-h', '127.0.0.1', '-p', str(port)],
                                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, creationflags=flags)
                if result.returncode == 0: break
                time.sleep(.1)
            else:
                raise RuntimeError('Temporary PostgreSQL not ready')
            for name in ['s1_order_test', 's1_agent_test']:
                run([pg('createdb'), '-h', '127.0.0.1', '-p', port, '-U', 's1tester', name])
            env['S1_ORDER_TEST_URL'] = f'jdbc:postgresql://127.0.0.1:{port}/s1_order_test'
            env['S1_AGENT_TEST_URL'] = f'jdbc:postgresql://127.0.0.1:{port}/s1_agent_test'
            mvn = shutil.which('mvn')
            if mvn is None: raise RuntimeError('Maven is not on PATH')
            command = [mvn, '-B', '-ntp', '-f', 'backend/pom.xml', 'verify']
            if args.offline: command.insert(1, '-o')
            code = run(command, check=False)
            totals = dict(tests=0, failures=0, errors=0, skipped=0)
            for report in (project / 'backend').glob('*/target/surefire-reports/TEST-*.xml'):
                suite = ET.parse(report).getroot()
                for key in totals: totals[key] += int(suite.get(key, 0))
            summary = dict(mavenExitCode=code, **totals)
            (work / 'result.json').write_text(json.dumps(summary, indent=2), encoding='utf-8')
            print(json.dumps(summary))
            if code: raise SystemExit(code)
        finally:
            if process is not None and process.poll() is None:
                run([pg('pg_ctl'), '-D', pgdata, '-m', 'fast', '-w', 'stop'], check=False)
                try: process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.terminate()
                    process.wait(timeout=10)
            if pglog is not None: pglog.close()


if __name__ == '__main__':
    main()
