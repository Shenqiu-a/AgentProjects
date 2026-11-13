"""Verify isolated service schemas, legacy data copying, and packaged application startup.
Requires built JARs, Java 21, and a PostgreSQL installation with pgvector.
All data belongs to a new temporary cluster under the gitignored data/local directory.
"""
import argparse
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import time
import urllib.error
import urllib.request
import uuid
import zipfile

TABLES = {
    "AUTH": ["app_user"],
    "ORDER": ["product", "policy_rule", "customer_order"],
    "KNOWLEDGE": ["knowledge_document", "document_ingestion", "knowledge_chunk"],
    "AGENT": ["conversation", "chat_message", "agent_run", "tool_call", "run_event",
              "confirmation", "ticket", "ticket_status_history", "idempotency_record",
              "evaluation_job", "evaluation_case_result"],
}


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--postgres-bin", type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    work = root / "data/local/microservice-verification" / uuid.uuid4().hex
    work.mkdir(parents=True)
    project = work / "project"
    project.mkdir()
    libs = work / "libs"
    libs.mkdir()
    pgdata = work / "postgres"
    port = free_port()
    hidden = getattr(subprocess, "CREATE_NO_WINDOW", 0)
    processes = []
    started = False
    postgres_process = None
    postgres_log = None
    env = os.environ.copy()
    for key in list(env):
        if key.startswith(("DATABASE_", "AUTH_DATABASE_", "ORDER_DATABASE_", "KNOWLEDGE_DATABASE_", "AGENT_DATABASE_")):
            del env[key]
    env.update({"DATABASE_URL": f"jdbc:postgresql://127.0.0.1:{port}/after_sales",
                "DATABASE_USERNAME": "verifier", "DATABASE_PASSWORD": "",
                "SERVICE_AUTH_TOKEN": "isolated-verification-service-secret-32-characters"})
    log = open(work / "verification.log", "w", encoding="utf-8")

    def run(command, expected=0):
        result = subprocess.run([str(x) for x in command], env=env, cwd=project,
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                encoding="utf-8", errors="replace", creationflags=hidden)
        log.write(result.stdout)
        log.flush()
        if (result.returncode == 0) != (expected == 0):
            raise RuntimeError(f"Command failed: {command[0]}\n{result.stdout[-5000:]}")
        return result.stdout

    suffix = ".exe" if os.name == "nt" else ""
    def pg(tool):
        return args.postgres_bin / (tool + suffix)

    def sql(database, statement):
        return run([pg("psql"), "-X", "-A", "-t", "-h", "127.0.0.1", "-p", port,
                    "-U", "verifier", "-d", database, "-v", "ON_ERROR_STOP=1", "-c", statement]).strip()

    try:
        jars = {}
        for service in TABLES:
            name = service.lower() + "-service"
            jars[service] = root / f"backend/{name}/target/{name}-0.0.1-SNAPSHOT.jar"
            if not jars[service].is_file():
                raise RuntimeError("Build the project with mvn -f backend/pom.xml verify first.")
        with zipfile.ZipFile(jars["AUTH"]) as archive:
            for name in archive.namelist():
                if name.startswith("BOOT-INF/lib/") and name.endswith(".jar"):
                    (libs / Path(name).name).write_bytes(archive.read(name))
        classpath = str(libs / "*")
        run([pg("initdb"), "-D", pgdata, "-U", "verifier", "-A", "trust", "--encoding=UTF8", "--no-locale"])
        # Direct launch also works in Windows environments where pg_ctl cannot create a restricted token.
        postgres_log = open(work / "postgres.log", "w", encoding="utf-8")
        postgres_process = subprocess.Popen(
            [str(pg("postgres")), "-D", str(pgdata), "-h", "127.0.0.1", "-p", str(port)],
            stdout=postgres_log, stderr=subprocess.STDOUT, creationflags=hidden)
        for attempt in range(60):
            if postgres_process.poll() is not None:
                raise RuntimeError(f"Temporary PostgreSQL exited; inspect {work / 'postgres.log'}")
            probe = subprocess.run([str(pg("pg_isready")), "-h", "127.0.0.1", "-p", str(port)],
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, creationflags=hidden)
            if probe.returncode == 0:
                started = True
                break
            time.sleep(0.25)
        if not started:
            raise RuntimeError("Temporary PostgreSQL did not become ready")
        for relative in ["backend/legacy-db/migration", "data/fixtures/knowledge"]:
            shutil.copytree(root / relative, project / relative)
        (project / "scripts").mkdir()
        shutil.copyfile(root / "scripts/seed-local.sql", project / "scripts/seed-local.sql")
        (project / ".env").write_text(
            f"DATABASE_URL={env['DATABASE_URL']}\nDATABASE_USERNAME=verifier\n", encoding="utf-8")
        config = []
        sql("postgres", "CREATE DATABASE after_sales")
        run(["java", "-Dfile.encoding=UTF-8", "-cp", classpath,
             root / "scripts/DatabaseSetup.java", project, "setup"])

        schema_tool = work / "SchemaSetup.java"
        schema_tool.write_text(
            'import org.flywaydb.core.Flyway;\n'
            'class SchemaSetup { public static void main(String[] a) { '
            'Flyway.configure().dataSource(a[0],"verifier","").locations("filesystem:"+a[1])'
            '.cleanDisabled(true).load().migrate(); }}', encoding="utf-8")
        for service in TABLES:
            db = "after_sales_" + service.lower()
            sql("postgres", "CREATE DATABASE " + db)
            url = f"jdbc:postgresql://127.0.0.1:{port}/{db}"
            config += [f"{service}_DATABASE_URL={url}", f"{service}_DATABASE_USERNAME=verifier"]
            env[f"{service}_DATABASE_URL"] = url
            env[f"{service}_DATABASE_USERNAME"] = "verifier"
            run(["java", "-cp", classpath, schema_tool, url,
                 root / f"backend/{service.lower()}-service/src/main/resources/db/migration"])
        (project / ".env.microservices").write_text("\n".join(config), encoding="utf-8")
        migration = root / "scripts/MicroserviceDataMigration.java"
        run(["java", "-cp", classpath, migration, project, "plan"])
        run(["java", "-cp", classpath, migration, project, "copy"])
        hashes = {}
        for service, tables in TABLES.items():
            db = "after_sales_" + service.lower()
            owned = set(sql(db, "SELECT tablename FROM pg_tables WHERE schemaname='public'").splitlines())
            assert owned == set(tables) | {"flyway_schema_history"}, (service, owned)
            for table in tables:
                query = f"SELECT coalesce(md5(string_agg(row_to_json(t)::text,'' ORDER BY id)),'EMPTY') FROM {table} t"
                source, target = sql("after_sales", query), sql(db, query)
                assert source == target, (service, table, source, target)
                hashes[(db, table)] = source
        refused = run(["java", "-cp", classpath, migration, project, "copy"], expected=1)
        assert "must be empty" in refused
        for (db, table), before in hashes.items():
            assert sql(db, f"SELECT coalesce(md5(string_agg(row_to_json(t)::text,'' ORDER BY id)),'EMPTY') FROM {table} t") == before
        print("PASS: 18 tables copied exactly; ownership is isolated; repeat copy refused.", flush=True)

        auth_port = free_port()
        env["AUTH_SERVICE_URL"] = f"http://127.0.0.1:{auth_port}"
        env["REDIS_PORT"] = str(free_port())
        app_ports = {}
        for service in TABLES:
            app_port = auth_port if service == "AUTH" else free_port()
            app_ports[service] = app_port
            app_log = open(work / (service.lower() + ".log"), "w", encoding="utf-8")
            process = subprocess.Popen(
                ["java", "-jar", str(jars[service]), f"--server.port={app_port}",
                 "--management.health.redis.enabled=false"], cwd=project, env=env,
                stdout=app_log, stderr=subprocess.STDOUT, creationflags=hidden)
            processes.append((process, app_log, service))
        for process, app_log, service in processes:
            url = f"http://127.0.0.1:{app_ports[service]}/actuator/health"
            for attempt in range(90):
                if process.poll() is not None:
                    raise RuntimeError(f"{service} exited; inspect {work / (service.lower() + '.log')}")
                try:
                    with urllib.request.urlopen(url, timeout=2) as response:
                        if json.load(response)["status"] == "UP":
                            break
                except (OSError, ValueError):
                    time.sleep(0.5)
            else:
                raise RuntimeError(f"{service} did not become ready")
            print(f"PASS: {service} executable JAR started with its own migrated database.", flush=True)
        for service, path in [("AUTH", "/api/v1/auth/me"), ("ORDER", "/api/v1/orders"),
                              ("KNOWLEDGE", "/api/v1/knowledge/documents")]:
            try:
                urllib.request.urlopen(f"http://127.0.0.1:{app_ports[service]}{path}", timeout=5)
                raise AssertionError("Unauthenticated access was accepted")
            except urllib.error.HTTPError as error:
                assert error.code == 401, (service, error.code)
        print("PASS: unauthenticated business requests were rejected through real auth HTTP.", flush=True)
        print("LIMIT: Redis health was disabled for JAR smoke checks; no real Redis or Docker login was tested.", flush=True)
        print("Evidence directory: " + str(work), flush=True)
    finally:
        for process, stream, service in processes:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
            stream.close()
        try:
            if started:
                run([pg("pg_ctl"), "-D", pgdata, "-m", "fast", "-w", "stop"])
        finally:
            if postgres_process is not None and postgres_process.poll() is None:
                postgres_process.terminate()
                postgres_process.wait(timeout=15)
            if postgres_log is not None:
                postgres_log.close()
            log.close()


if __name__ == "__main__":
    main()
