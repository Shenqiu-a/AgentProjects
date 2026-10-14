import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.springframework.security.crypto.bcrypt.BCrypt;

/** 显式执行的本地数据库工具，不会被 Spring Boot 自动加载。JDK 21 source launcher。 */
public class DatabaseSetup {
    static final List<String> TABLES = List.of("app_user", "product", "policy_rule", "customer_order",
        "knowledge_document", "document_ingestion", "knowledge_chunk", "conversation", "chat_message",
        "agent_run", "tool_call", "run_event", "confirmation", "ticket", "ticket_status_history",
        "idempotency_record", "evaluation_job", "evaluation_case_result");
    static final List<String> USERS = List.of("alice", "bob", "admin");

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        String action = args.length > 1 ? args[1] : "setup";
        if (!List.of("setup", "migrate", "verify").contains(action)) throw new IllegalArgumentException("Unknown action");
        Properties env = readProperties(root.resolve(".env"));
        // 与 Spring Boot 一样：进程环境变量优先于 .env。
        for (String key : List.of("DATABASE_URL", "DATABASE_HOST", "POSTGRES_PORT", "POSTGRES_DB", "DATABASE_USERNAME", "DATABASE_PASSWORD")) {
            String value = System.getenv(key); if (value != null) env.setProperty(key, value);
        }
        String url = env.getProperty("DATABASE_URL", "jdbc:postgresql://" + env.getProperty("DATABASE_HOST", "localhost")
            + ":" + env.getProperty("POSTGRES_PORT", "5432") + "/" + env.getProperty("POSTGRES_DB", "after_sales"));
        URI uri = URI.create(url.substring("jdbc:".length()));
        if (!List.of("localhost", "127.0.0.1", "[::1]", "::1").contains(uri.getHost()) || !"/after_sales".equals(uri.getPath())) {
            throw new IllegalArgumentException("This development tool only accepts local after_sales; evaluation/production are excluded.");
        }
        Properties jdbc = new Properties();
        jdbc.setProperty("user", env.getProperty("DATABASE_USERNAME", "after_sales"));
        jdbc.setProperty("password", env.getProperty("DATABASE_PASSWORD", ""));
        jdbc.setProperty("connectTimeout", "5"); jdbc.setProperty("socketTimeout", "30");
        try (Connection c = DriverManager.getConnection(url, jdbc); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT current_database()")) {
            r.next(); require("after_sales".equals(r.getString(1)), "Wrong database");
        }
        if (!action.equals("verify")) {
            Flyway flyway = Flyway.configure().dataSource(url, jdbc.getProperty("user"), jdbc.getProperty("password"))
                .locations("filesystem:" + root.resolve("backend/src/main/resources/db/migration").toString().replace('\\', '/'))
                .schemas("public").defaultSchema("public").encoding("UTF-8").validateMigrationNaming(true)
                .baselineOnMigrate(false).cleanDisabled(true).load();
            flyway.migrate();
            flyway.validate();
        }
        Path credentialPath = root.resolve("data/local/test-accounts.properties");
        if (action.equals("setup")) {
            Properties accounts = credentials(credentialPath);
            try (Connection c = DriverManager.getConnection(url, jdbc)) {
                c.setAutoCommit(false);
                try {
                    // 多个初始化进程串行执行；不改变现有数据，不重置已有密码。
                    try (Statement s = c.createStatement()) { s.execute("SELECT pg_advisory_xact_lock(81420261014)"); }
                    seed(c, root, accounts);
                    verifyAccounts(c, accounts);
                    c.commit();
                } catch (Exception e) { c.rollback(); throw e; }
            }
            System.out.println("Local account passwords: " + credentialPath);
            System.out.println("Fixture date (Asia/Shanghai): " + accounts.getProperty("seed.date"));
        }
        if (!action.equals("migrate")) {
            try (Connection c = DriverManager.getConnection(url, jdbc)) {
                if (Files.exists(credentialPath)) verifyAccounts(c, readProperties(credentialPath));
                verify(c);
            }
        }
    }

    static Properties readProperties(Path p) throws IOException {
        Properties values = new Properties();
        if (Files.exists(p)) try (Reader r = Files.newBufferedReader(p)) { values.load(r); }
        return values;
    }

    static Properties credentials(Path p) throws IOException {
        Properties values = readProperties(p);
        for (String user : USERS) {
            if (!values.containsKey(user + ".password")) {
                byte[] bytes = new byte[18]; new SecureRandom().nextBytes(bytes);
                values.setProperty(user + ".password", Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
            }
        }
        if (!values.containsKey("seed.date")) values.setProperty("seed.date", LocalDate.now(ZoneId.of("Asia/Shanghai")).toString());
        Files.createDirectories(p.getParent());
        if (!Files.exists(p)) {
            try (Writer out = Files.newBufferedWriter(p, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
                values.store(out, "LOCAL DEVELOPMENT ONLY. Git ignored. Do not share or use in production.");
            }
        } else {
            Properties disk = readProperties(p);
            for (String key : List.of("alice.password", "bob.password", "admin.password", "seed.date"))
                require(disk.containsKey(key), "Incomplete local credentials file: " + key);
        }
        LocalDate.parse(values.getProperty("seed.date"));
        return values;
    }

    static void seed(Connection c, Path root, Properties accounts) throws Exception {
        try (Statement s = c.createStatement()) {
            s.execute("SET LOCAL TIME ZONE 'Asia/Shanghai'");
            s.execute("CREATE TEMP TABLE seed_account (username text, password_hash text) ON COMMIT DROP");
            s.execute("CREATE TEMP TABLE seed_document (n integer, slug text, title text, kind text, product_no integer, content text, storage_key text) ON COMMIT DROP");
        }
        try (PreparedStatement p = c.prepareStatement("SELECT set_config('app.seed_date', ?, true)")) {
            p.setString(1, accounts.getProperty("seed.date")); p.execute();
        }
        try (PreparedStatement p = c.prepareStatement("INSERT INTO seed_account VALUES (?, ?)")) {
            for (String user : USERS) {
                p.setString(1, user); p.setString(2, BCrypt.hashpw(accounts.getProperty(user + ".password"), BCrypt.gensalt(10))); p.addBatch();
            } p.executeBatch();
        }
        String[][] documents = {
            {"policy-ear-v1", "模拟耳机维修政策 R-EAR-1", "POLICY", "1"},
            {"policy-key-v1", "模拟键盘维修政策 R-KEY-1", "POLICY", "2"},
            {"guide-ear-left", "模拟耳机排查：左耳无声", "GUIDE", "1"},
            {"guide-ear-charge", "模拟耳机排查：无法充电", "GUIDE", "1"},
            {"guide-key-failure", "模拟键盘排查：按键失灵", "GUIDE", "2"},
            {"faq-repair-process", "模拟维修申请流程", "FAQ", null},
            {"faq-ticket-status", "模拟工单状态说明", "FAQ", null}
        };
        try (PreparedStatement p = c.prepareStatement("INSERT INTO seed_document VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            for (int i = 0; i < documents.length; i++) {
                String[] d = documents[i]; String key = "data/fixtures/knowledge/" + d[0] + ".md";
                p.setInt(1, i + 1); p.setString(2, d[0]); p.setString(3, d[1]); p.setString(4, d[2]);
                p.setObject(5, d[3] == null ? null : Integer.valueOf(d[3]));
                p.setString(6, Files.readString(root.resolve(key), StandardCharsets.UTF_8)); p.setString(7, key); p.addBatch();
            } p.executeBatch();
        }
        try (Statement s = c.createStatement()) { s.execute(Files.readString(root.resolve("scripts/seed-local.sql"), StandardCharsets.UTF_8)); }
    }

    static void verifyAccounts(Connection c, Properties accounts) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("SELECT id::text, password_hash, role FROM app_user WHERE username = ?")) {
            for (int i = 0; i < USERS.size(); i++) {
                String user = USERS.get(i); p.setString(1, user);
                try (ResultSet r = p.executeQuery()) {
                    require(r.next(), "Missing fixture user: " + user);
                    require(r.getString(1).equals("00000000-0000-4000-8000-00000000000" + (i + 1)), "Existing username belongs to different data: " + user);
                    require(BCrypt.checkpw(accounts.getProperty(user + ".password"), r.getString(2)),
                        "Existing password differs for " + user + "; no password was reset. Restore original ignored credentials file.");
                    require(r.getString(3).equals(user.equals("admin") ? "ADMIN" : "CUSTOMER"), "Wrong fixture role");
                }
            }
        }
    }

    static void verify(Connection c) throws SQLException {
        for (String table : TABLES) {
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT count(*) FROM public." + table)) {
                r.next(); System.out.printf("%-26s %d%n", table, r.getLong(1));
            }
        }
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT count(*) FROM knowledge_chunk k JOIN knowledge_document d ON d.id=k.document_id AND d.active_generation=k.generation WHERE d.status='READY' AND k.index_namespace='mock-v1' AND vector_dims(k.embedding)=16 AND abs(vector_norm(k.embedding)-1)<0.000001")) {
            r.next(); require(r.getInt(1) >= 7, "Missing ready mock chunks");
        }
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT count(*) FROM ticket t JOIN confirmation f ON f.id=t.source_confirmation_id AND f.ticket_id=t.id AND f.user_id=t.user_id WHERE f.status='APPROVED' AND t.ticket_no='T-DEMO-0001'")) {
            r.next(); require(r.getInt(1) == 1, "Fixture ticket/confirmation mismatch");
        }
        constraintChecks(c);
        System.out.println("VERIFIED: 18 tables; BCrypt login hashes; mock vectors; ticket confirmation; uniqueness and ownership constraints.");
    }

    static void constraintChecks(Connection c) throws SQLException {
        c.setAutoCommit(false);
        try {
            // 每项检查回滚到 savepoint；全部结束再次回滚，不留下探测数据。
            expectViolation(c, "23503", "cross-user bound order", "INSERT INTO conversation(user_id,bound_order_id,title) VALUES ('00000000-0000-4000-8000-000000000002','20000000-0000-4000-8000-000000000001','probe')");
            expectViolation(c, "23514", "embedding dimension mismatch", "INSERT INTO knowledge_chunk(document_id,generation,ordinal,char_start,char_end,content,content_hash,embedding,embedding_model,embedding_dimensions,index_namespace) SELECT document_id,generation,9999,0,1,'x',content_hash,'[1,0,0]'::vector,embedding_model,embedding_dimensions,index_namespace FROM knowledge_chunk LIMIT 1");
            try (Statement s = c.createStatement()) {
                s.execute("INSERT INTO agent_run (conversation_id,user_id,trigger_message_id,status,mode,executor_version) SELECT conversation_id,user_id,trigger_message_id,'QUEUED','mock','constraint-probe' FROM agent_run WHERE id='52000000-0000-4000-8000-000000000001'");
            }
            expectViolation(c, "23505", "one active run", "INSERT INTO agent_run (conversation_id,user_id,trigger_message_id,status,mode,executor_version) SELECT conversation_id,user_id,trigger_message_id,'QUEUED','mock','constraint-probe' FROM agent_run WHERE id='52000000-0000-4000-8000-000000000001'");
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("INSERT INTO confirmation(run_id,user_id,draft,draft_hash,expires_at) SELECT r.id,r.user_id,'{}'::jsonb,repeat('0',64),now()+interval '10 minutes' FROM agent_run r WHERE executor_version='constraint-probe' RETURNING id")) {
                r.next(); String confirmation = r.getString(1);
                expectViolation(c, "23505", "one active ticket per order", "INSERT INTO ticket(ticket_no,user_id,order_id,source_confirmation_id,reason_code,description) VALUES ('T-CONSTRAINT-PROBE','00000000-0000-4000-8000-000000000001','20000000-0000-4000-8000-000000000006','" + confirmation + "','OTHER','仅用于约束验证且会回滚的测试记录')");
            }
        } finally { c.rollback(); c.setAutoCommit(true); }
    }

    static void expectViolation(Connection c, String state, String label, String sql) throws SQLException {
        Savepoint point = c.setSavepoint();
        try (Statement s = c.createStatement()) {
            s.execute(sql); throw new IllegalStateException("Constraint did not reject: " + label);
        } catch (SQLException e) {
            if (!state.equals(e.getSQLState())) throw e;
            System.out.println("CHECK PASS: " + label);
        } finally { c.rollback(point); c.releaseSavepoint(point); }
    }

    static void require(boolean ok, String message) { if (!ok) throw new IllegalStateException(message); }
}
