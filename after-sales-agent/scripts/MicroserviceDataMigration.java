import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.sql.*;
import java.util.*;
import org.springframework.security.crypto.bcrypt.BCrypt;

/** 只接受本机的指定开发库；源库只读、目标必须为空，不覆盖或清空任何现有数据。 */
public class MicroserviceDataMigration {
    static final Map<String, List<String>> TABLES = new LinkedHashMap<>();
    static {
        TABLES.put("AUTH", List.of("app_user"));
        TABLES.put("ORDER", List.of("product", "policy_rule", "customer_order"));
        TABLES.put("KNOWLEDGE", List.of("knowledge_document", "document_ingestion", "knowledge_chunk"));
        TABLES.put("AGENT", List.of("conversation", "chat_message", "agent_run", "tool_call", "run_event",
                "confirmation", "ticket", "ticket_status_history", "idempotency_record",
                "evaluation_job", "evaluation_case_result"));
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        String action = args.length > 1 ? args[1] : "plan";
        if (!Set.of("plan", "copy", "seed-accounts").contains(action)) throw new IllegalArgumentException("Unknown action");
        Properties config = read(root.resolve(".env.microservices"));
        if (action.equals("seed-accounts")) { seedAccounts(root, config); return; }
        Properties legacy = read(root.resolve(".env"));
        Map<String, Connection> targets = new LinkedHashMap<>();
        try (Connection source = connect(legacy, "DATABASE", "after_sales", 5432)) {
            source.setReadOnly(true);
            source.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            source.setAutoCommit(false);
            boolean empty = true;
            for (String service : TABLES.keySet()) {
                String database = "after_sales_" + service.toLowerCase(Locale.ROOT);
                int port = 55431 + new ArrayList<>(TABLES.keySet()).indexOf(service);
                Connection target = connect(config, service + "_DATABASE", database, port);
                targets.put(service, target);
                target.setAutoCommit(false);
                try (Statement s = target.createStatement()) { s.execute("SET CONSTRAINTS ALL DEFERRED"); }
                for (String table : TABLES.get(service)) {
                    long sourceCount = count(source, table), targetCount = count(target, table);
                    System.out.printf("%s.%s source=%d target=%d%n", service, table, sourceCount, targetCount);
                    empty &= targetCount == 0;
                }
            }
            if (action.equals("plan")) { System.out.println("PLAN ONLY: no rows were written."); return; }
            if (!empty) throw new IllegalStateException("All target business tables must be empty; existing data was preserved.");
            // 先完成全部复制与约束检查，再依次提交。此离线工具不实现跨库两阶段提交。
            for (var entry : targets.entrySet()) {
                for (String table : TABLES.get(entry.getKey())) copyTable(source, entry.getValue(), table);
                try (Statement s = entry.getValue().createStatement()) { s.execute("SET CONSTRAINTS ALL IMMEDIATE"); }
            }
            for (var entry : targets.entrySet()) {
                entry.getValue().commit();
                System.out.println("COMMITTED " + entry.getKey());
            }
            source.rollback();
            System.out.println("Migration completed; legacy database was read only.");
        } finally {
            for (Connection target : targets.values()) {
                try { target.rollback(); } finally { target.close(); }
            }
        }
    }

    static Properties read(Path path) throws IOException {
        Properties properties = new Properties();
        if (Files.exists(path)) try (Reader r = Files.newBufferedReader(path)) { properties.load(r); }
        return properties;
    }

    static String value(Properties config, String key, String fallback) {
        return System.getenv().getOrDefault(key, config.getProperty(key, fallback));
    }

    static Connection connect(Properties config, String prefix, String database, int port) throws SQLException {
        String url = value(config, prefix + "_URL", "jdbc:postgresql://127.0.0.1:" + port + "/" + database);
        URI uri = URI.create(url.substring("jdbc:".length()));
        if (!List.of("localhost", "127.0.0.1", "[::1]", "::1").contains(uri.getHost())
                || !("/" + database).equals(uri.getPath()) || uri.getQuery() != null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Only the named local development database is allowed: " + database);
        }
        Properties credentials = new Properties();
        credentials.setProperty("user", value(config, prefix + "_USERNAME", database));
        credentials.setProperty("password", value(config, prefix + "_PASSWORD", ""));
        credentials.setProperty("connectTimeout", "5");
        credentials.setProperty("socketTimeout", "60");
        return DriverManager.getConnection(url, credentials);
    }

    static long count(Connection connection, String table) throws SQLException {
        try (Statement s = connection.createStatement(); ResultSet rows = s.executeQuery("SELECT count(*) FROM " + table)) {
            rows.next(); return rows.getLong(1);
        }
    }

    static void copyTable(Connection source, Connection target, String table) throws SQLException {
        try (Statement s = source.createStatement(); ResultSet rows = s.executeQuery("SELECT * FROM " + table)) {
            ResultSetMetaData columns = rows.getMetaData();
            List<String> names = new ArrayList<>();
            for (int i = 1; i <= columns.getColumnCount(); i++) names.add(columns.getColumnName(i));
            String sql = "INSERT INTO " + table + " (" + String.join(",", names) + ") VALUES ("
                    + String.join(",", Collections.nCopies(names.size(), "?")) + ")";
            try (PreparedStatement insert = target.prepareStatement(sql)) {
                while (rows.next()) {
                    for (int i = 1; i <= names.size(); i++) insert.setObject(i, rows.getObject(i));
                    insert.addBatch();
                }
                insert.executeBatch();
            }
        }
    }

    static void seedAccounts(Path root, Properties config) throws Exception {
        Path path = root.resolve("data/local/microservice-accounts.properties");
        Properties accounts = read(path);
        try (Connection target = connect(config, "AUTH_DATABASE", "after_sales_auth", 55431)) {
            target.setAutoCommit(false);
            for (String username : List.of("alice", "bob", "admin")) {
                try (PreparedStatement exists = target.prepareStatement("SELECT 1 FROM app_user WHERE username = ?")) {
                    exists.setString(1, username);
                    try (ResultSet rows = exists.executeQuery()) { if (rows.next()) continue; }
                }
                byte[] random = new byte[18]; new SecureRandom().nextBytes(random);
                String password = accounts.getProperty(username + ".password", Base64.getUrlEncoder().withoutPadding().encodeToString(random));
                accounts.setProperty(username + ".password", password);
                try (PreparedStatement insert = target.prepareStatement(
                        "INSERT INTO app_user (username,password_hash,role) VALUES (?,?,?)")) {
                    insert.setString(1, username); insert.setString(2, BCrypt.hashpw(password, BCrypt.gensalt(10)));
                    insert.setString(3, username.equals("admin") ? "ADMIN" : "CUSTOMER"); insert.executeUpdate();
                }
            }
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) { accounts.store(writer, "Local demo accounts"); }
            target.commit();
        }
        System.out.println("Local passwords: " + path);
    }
}
