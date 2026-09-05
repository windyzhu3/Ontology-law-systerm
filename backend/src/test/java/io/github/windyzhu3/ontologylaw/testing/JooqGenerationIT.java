package io.github.windyzhu3.ontologylaw.testing;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jooq.codegen.GenerationTool;
import org.jooq.meta.jaxb.Configuration;
import org.jooq.meta.jaxb.Generate;
import org.jooq.meta.jaxb.Generator;
import org.jooq.meta.jaxb.Logging;
import org.jooq.meta.jaxb.Target;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Rebuilds from the locked, migrated server, never from DDL parsing or hand-edited output. */
class JooqGenerationIT extends PostgresIntegrationTest {
    // Deliberately explicit: adding a table is an owner/scope decision, not a schema wildcard.
    private static final Map<String, List<String>> TABLES = new TreeMap<>(Map.of(
            "identity", List.of("tenant", "principal", "organization_unit", "appointment", "authority_grant", "delegation_grant", "object_access_grant"),
            "audit", List.of("audit_entry"),
            "execution", List.of("command_execution_slot", "command_receipt", "domain_event", "domain_event_outbox"),
            "responsibility", List.of("task_occurrence", "decision_record", "wait_receipt", "action_draft"),
            "party", List.of("party"),
            "lead", List.of("lead", "lead_assignment", "lead_contact_result"),
            "opportunity", List.of("opportunity")));

    @TempDir Path generated;

    @Test void generated_owner_sources_and_manifest_match_live_schema_byte_for_byte() throws Exception {
        try (var connection = database.migratorConnection()) {
            for (var owner : TABLES.entrySet()) {
                var tool = new GenerationTool();
                tool.setConnection(connection);
                tool.run(new Configuration().withLogging(Logging.WARN).withGenerator(new Generator()
                        .withName("org.jooq.codegen.JavaGenerator")
                        .withDatabase(new org.jooq.meta.jaxb.Database().withName("org.jooq.meta.postgres.PostgresDatabase")
                                .withInputSchema(owner.getKey()).withIncludes(String.join("|", owner.getValue()))
                                .withIncludeRoutines(false).withIncludeSequences(false)
                                .withIncludeUDTs(false).withIncludeSystemTables(false))
                        .withGenerate(new Generate().withRecords(false).withPojos(true).withImmutablePojos(true)
                                .withDaos(false).withRelations(false).withGeneratedAnnotation(false)
                                .withImplicitJoinPathsToOne(false).withImplicitJoinPathsToMany(false)
                                .withImplicitJoinPathsManyToMany(false).withImplicitJoinPathTableSubtypes(false)
                                .withJavaTimeTypes(true).withNewline("\n").withIndentation("    "))
                        .withTarget(new Target().withPackageName("io.github.windyzhu3.ontologylaw."
                                + owner.getKey() + ".internal.persistence.jooq")
                                .withDirectory(generated.toString()).withEncoding("UTF-8").withClean(false))));
            }
        }
        Map<String, byte[]> actual = contents(generated);
        long pojos = actual.keySet().stream().filter(name -> name.contains("/tables/pojos/")).count();
        assertEquals(21, pojos, "Only the explicit R1 tables may be generated");
        for (var file : actual.entrySet()) {
            String source = new String(file.getValue(), StandardCharsets.UTF_8);
            assertFalse(source.contains("\r"), file.getKey() + " must use platform-independent LF");
            assertFalse(file.getKey().contains("/records/") || file.getKey().contains("/daos/"), file.getKey());
            assertFalse(source.contains("UpdatableRecord") || source.contains("TableRecord")
                    || source.contains("DAOImpl"), "No Active Record or DAO: " + file.getKey());
        }
        StringBuilder manifest = new StringBuilder();
        for (var file : actual.entrySet()) {
            manifest.append(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file.getValue())))
                    .append("  ").append(file.getKey()).append('\n');
        }
        actual.put("MANIFEST.sha256", manifest.toString().getBytes(StandardCharsets.UTF_8));
        Path committed = repositoryRoot().resolve("backend/src/generated/jooq");
        String mode = System.getProperty("jooq.generation", "check");
        if (mode.equals("write")) {
            // Remove stale generated files only within the dedicated generated root.
            for (String name : contents(committed).keySet()) {
                if (!actual.containsKey(name)) Files.delete(committed.resolve(name));
            }
            for (var file : actual.entrySet()) {
                Path destination = committed.resolve(file.getKey());
                Files.createDirectories(destination.getParent());
                Files.write(destination, file.getValue());
            }
        } else {
            assertEquals("check", mode, "Unknown generation mode");
            Map<String, byte[]> expected = contents(committed);
            assertEquals(expected.keySet(), actual.keySet(), "Generated file set drift; run generate-jooq.sh --write");
            for (String name : actual.keySet()) {
                assertArrayEquals(expected.get(name), actual.get(name), "Generated content drift: " + name);
            }
        }
    }

    private static Map<String, byte[]> contents(Path root) throws Exception {
        Map<String, byte[]> files = new TreeMap<>();
        if (Files.exists(root)) {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                    files.put(root.relativize(path).toString().replace('\\', '/'), Files.readAllBytes(path));
                }
            }
        }
        return files;
    }
}
