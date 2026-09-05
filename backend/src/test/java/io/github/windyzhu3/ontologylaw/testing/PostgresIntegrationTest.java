package io.github.windyzhu3.ontologylaw.testing;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

/** Each subclass owns an isolated, migrated real database. Credentials exist only in memory. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class PostgresIntegrationTest {
    protected Database database;

    @BeforeAll
    void startDatabase() throws Exception { database = Database.start(); }

    @AfterAll
    void stopDatabase() { if (database != null) database.close(); }

    public static Path repositoryRoot() {
        Path path = Path.of("").toAbsolutePath();
        while (path != null && !Files.exists(path.resolve("database/schema-contract-52-plus-2/runtime/toolchain.lock.json"))) {
            path = path.getParent();
        }
        if (path == null) throw new IllegalStateException("Cannot locate schema contract");
        return path;
    }

    public static String lockedPostgresImage() throws Exception {
        return lockedPostgresImage(repositoryRoot().resolve("database/schema-contract-52-plus-2/runtime/toolchain.lock.json"));
    }

    public static String lockedPostgresImage(Path lock) throws Exception {
        var root = JsonMapper.builder().build().readTree(Files.readString(lock));
        String result = null;
        for (var image : root.path("images")) {
            if (image.path("image").asString().equals("postgres")) {
                if (result != null || !image.path("tag").asString().equals("18")
                        || !image.path("digest").asString().matches("sha256:[0-9a-f]{64}")) {
                    throw new IllegalStateException("PostgreSQL 18 must have exactly one locked RepoDigest");
                }
                result = "postgres@" + image.path("digest").asString();
            }
        }
        if (result == null) throw new IllegalStateException("Missing PostgreSQL RepoDigest");
        return result;
    }

    public static final class Database implements AutoCloseable {
        private final PostgreSQLContainer postgres;
        private final String migratorPassword = UUID.randomUUID().toString();
        private final String apiPassword = UUID.randomUUID().toString();
        private final String workerPassword = UUID.randomUUID().toString();
        private Database(PostgreSQLContainer postgres) { this.postgres = postgres; }

        public static Database start() throws Exception {
            var container = new PostgreSQLContainer(DockerImageName.parse(lockedPostgresImage())
                    .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("law_contract_runtime").withUsername("postgres")
                    .withPassword(UUID.randomUUID().toString());
            var database = new Database(container);
            try {
                container.start();
                database.initialize();
                return database;
            } catch (Exception | Error failure) {
                container.close();
                throw failure;
            }
        }

        private void initialize() throws Exception {
            try (var connection = adminConnection(); var sql = connection.createStatement()) {
                sql.execute("CREATE ROLE law_schema_migrator LOGIN NOINHERIT PASSWORD '" + migratorPassword + "'");
                sql.execute("ALTER DATABASE law_contract_runtime OWNER TO law_schema_migrator");
                for (String role : new String[]{"law_app_command", "law_app_query", "law_audit_append", "law_app_worker"}) {
                    sql.execute("CREATE ROLE " + role + " NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE");
                }
            }
            var flyway = Flyway.configure().dataSource(postgres.getJdbcUrl(), "law_schema_migrator", migratorPassword)
                    .defaultSchema("platform_meta")
                    .schemas("identity", "audit", "responsibility", "execution", "external_action", "evidence", "party",
                            "lead", "opportunity", "conflict", "contract", "transfer", "platform_meta")
                    .locations("filesystem:" + repositoryRoot().resolve("database/schema-contract-52-plus-2/generated/db/migration"))
                    .placeholders(Map.of("app_command_role", "law_app_command", "app_query_role", "law_app_query",
                            "audit_append_role", "law_audit_append", "app_worker_role", "law_app_worker"))
                    .cleanDisabled(true).baselineOnMigrate(false).validateMigrationNaming(true).load();
            flyway.migrate();
            flyway.validate();
            long migrations = java.util.Arrays.stream(flyway.info().applied())
                    .filter(migration -> migration.getVersion() != null).count();
            if (migrations != 20) throw new IllegalStateException("Expected 20 migrations, got " + migrations);
            try (var connection = adminConnection(); var sql = connection.createStatement()) {
                sql.execute(Files.readString(repositoryRoot().resolve("backend/src/test/resources/db/bootstrap-runtime-logins.sql")));
                sql.execute("ALTER ROLE law_api_login PASSWORD '" + apiPassword + "'");
                sql.execute("ALTER ROLE law_worker_login PASSWORD '" + workerPassword + "'");
            }
        }

        public Connection apiConnection() throws SQLException { return connect("law_api_login", apiPassword); }
        public Connection workerConnection() throws SQLException { return connect("law_worker_login", workerPassword); }
        public Connection migratorConnection() throws SQLException { return connect("law_schema_migrator", migratorPassword); }
        public Connection adminConnection() throws SQLException { return connect(postgres.getUsername(), postgres.getPassword()); }
        private Connection connect(String user, String password) throws SQLException {
            return DriverManager.getConnection(postgres.getJdbcUrl(), user, password);
        }
        @Override public void close() { postgres.close(); }
    }
}
