package io.github.windyzhu3.ontologylaw;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.example.outside.OutsideRootFixture;
import io.github.windyzhu3.ontologylaw.bootstrap.BootstrapIllegalDependencyFixture;
import io.github.windyzhu3.ontologylaw.lead.LeadPortFixture;
import io.github.windyzhu3.ontologylaw.lead.internal.persistence.FrameworkLeakFixture;
import io.github.windyzhu3.ontologylaw.matter.MatterFixture;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ArchitectureTest {
    private static final String ROOT = "io.github.windyzhu3.ontologylaw";
    private static final Set<String> DOMAIN_MODULES = Set.of(
            "audit", "execution", "identity", "lead", "opportunity", "query", "responsibility");
    private static final Set<String> OWNER_MODULES = Set.of(
            "audit", "execution", "identity", "lead", "opportunity", "responsibility");
    private static final Set<String> ALLOWED_TOP_LEVEL_PACKAGES = Set.of(
            "api", "audit", "bootstrap", "execution", "identity",
            "lead", "opportunity", "query", "responsibility", "worker");
    private static final Map<String, Set<String>> ALLOWED_MODULE_DEPENDENCIES = Map.ofEntries(
            Map.entry("root", Set.of("bootstrap")),
            Map.entry("bootstrap", Set.of("api", "worker")),
            Map.entry("identity", Set.of()),
            Map.entry("audit", Set.of("identity")),
            Map.entry("opportunity", Set.of("identity", "audit")),
            Map.entry("execution", Set.of("identity", "audit")),
            Map.entry("responsibility", Set.of("identity", "audit", "execution")),
            Map.entry("lead", Set.of("identity", "audit", "execution", "responsibility", "opportunity")),
            Map.entry("query", Set.of("identity", "responsibility", "lead", "opportunity")),
            Map.entry("api", Set.of("identity", "audit", "execution", "responsibility", "lead", "opportunity", "query")),
            Map.entry("worker", Set.of("execution")));

    private final JavaClasses productionClasses = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importUrl(OntologyLawApplication.class.getProtectionDomain().getCodeSource().getLocation());

    @Test
    void domain_code_is_pure_java_without_framework_or_network_dependencies() {
        checkDomainFrameworkPurity(productionClasses);
    }

    @Test
    void persistence_packages_cannot_use_http_jackson_or_spring() {
        assertThrows(AssertionError.class, () -> checkDomainFrameworkPurity(
                new ClassFileImporter().importClasses(FrameworkLeakFixture.UrlLeak.class)));
        assertThrows(AssertionError.class, () -> checkDomainFrameworkPurity(
                new ClassFileImporter().importClasses(FrameworkLeakFixture.JacksonLeak.class)));
        assertThrows(AssertionError.class, () -> checkDomainFrameworkPurity(
                new ClassFileImporter().importClasses(FrameworkLeakFixture.SpringLeak.class)));
    }

    private static void checkDomainFrameworkPurity(JavaClasses classesToCheck) {
        for (String domain : DOMAIN_MODULES) {
            noClasses().that().resideInAPackage(ROOT + "." + domain + "..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.springframework..",
                            "com.fasterxml.jackson..",
                            "java.net..",
                            "javax.net..",
                            "jakarta.servlet..",
                            "com.sun.net.httpserver..",
                            "org.apache.hc..",
                            "org.apache.http..",
                            "org.eclipse.jetty..",
                            "io.netty.handler.codec.http..",
                            "reactor.netty.http..",
                            "okhttp3..")
                    .allowEmptyShould(true)
                    .check(classesToCheck);
        }
    }

    @Test
    void jooq_dependencies_are_confined_to_owner_internal_persistence_packages() {
        noClasses().that().resideOutsideOfPackages(OWNER_MODULES.stream()
                        .map(module -> ROOT + "." + module + ".internal.persistence..")
                        .toArray(String[]::new))
                .should().dependOnClassesThat().resideInAPackage("org.jooq..")
                .allowEmptyShould(true)
                .check(productionClasses);
    }

    @Test
    void api_surface_does_not_expose_persistence_records_repositories_or_internal_commands() {
        noClasses().that().resideInAPackage(ROOT + ".api..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.jooq..", ROOT + "..internal.persistence..", ROOT + "..internal.command..")
                .allowEmptyShould(true)
                .check(productionClasses);
        noClasses().that().resideInAPackage(ROOT + ".api..")
                .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository")
                .allowEmptyShould(true)
                .check(productionClasses);
    }

    @Test
    void no_module_accesses_another_modules_internal_package() {
        for (String owner : ALLOWED_TOP_LEVEL_PACKAGES) {
            noClasses().that().resideOutsideOfPackage(ROOT + "." + owner + "..")
                    .should().dependOnClassesThat().resideInAPackage(ROOT + "." + owner + ".internal..")
                    .allowEmptyShould(true)
                    .check(productionClasses);
        }
    }

    @Test
    void production_dependencies_follow_the_complete_module_dag() {
        checkClassLocations(productionClasses);
        checkModuleDag(productionClasses);
    }

    @Test
    void source_location_policy_rejects_unknown_top_level_module() {
        JavaClasses fixtureClasses = new ClassFileImporter().importClasses(MatterFixture.class);
        assertThrows(AssertionError.class, () -> checkClassLocations(fixtureClasses));
    }

    @Test
    void source_location_policy_rejects_class_outside_the_root_package() {
        JavaClasses fixtureClasses = new ClassFileImporter().importClasses(OutsideRootFixture.class);
        assertThrows(AssertionError.class, () -> checkClassLocations(fixtureClasses));
    }

    @Test
    void module_dag_rejects_bootstrap_business_dependency() {
        JavaClasses fixtureClasses = new ClassFileImporter()
                .importClasses(BootstrapIllegalDependencyFixture.class, LeadPortFixture.class);
        assertThrows(AssertionError.class, () -> checkModuleDag(fixtureClasses));
    }

    @Test
    void module_dag_rejects_root_assembly_business_dependency() {
        JavaClasses fixtureClasses = new ClassFileImporter()
                .importClasses(RootIllegalDependencyFixture.class, LeadPortFixture.class);
        assertThrows(AssertionError.class, () -> checkModuleDag(fixtureClasses));
    }

    private static void checkClassLocations(JavaClasses classesToCheck) {
        for (JavaClass origin : classesToCheck) {
            String packageName = origin.getPackageName();
            if (!packageName.equals(ROOT) && !packageName.startsWith(ROOT + ".")) {
                fail(origin.getName() + " must reside under the single root package " + ROOT);
            }
            String consumer = moduleOf(origin);
            if (!consumer.equals("root") && !ALLOWED_TOP_LEVEL_PACKAGES.contains(consumer)) {
                fail(origin.getName() + " uses forbidden top-level package " + consumer);
            }
        }
    }

    private static void checkModuleDag(JavaClasses classesToCheck) {
        for (JavaClass origin : classesToCheck) {
            String consumer = moduleOf(origin);
            if (!ALLOWED_MODULE_DEPENDENCIES.containsKey(consumer)) {
                fail(origin.getName() + " has unknown module consumer " + consumer);
            }
            for (var dependency : origin.getDirectDependenciesFromSelf()) {
                String target = moduleOf(dependency.getTargetClass());
                if (target.isEmpty()) {
                    continue;
                }
                if (!ALLOWED_MODULE_DEPENDENCIES.containsKey(target)) {
                    fail(origin.getName() + " depends on unknown module " + target);
                }
                if (!target.equals(consumer)
                        && !ALLOWED_MODULE_DEPENDENCIES.get(consumer).contains(target)) {
                    fail(consumer + " must not depend on " + target);
                }
            }
        }
    }

    @Test
    void spring_modulith_accepts_the_application_module_structure() {
        ApplicationModules.of(OntologyLawApplication.class).verify();
    }

    private static String moduleOf(JavaClass type) {
        if (type.getPackageName().equals(ROOT)) {
            return "root";
        }
        String prefix = ROOT + ".";
        if (!type.getPackageName().startsWith(prefix)) {
            return "";
        }
        String remainder = type.getPackageName().substring(prefix.length());
        int separator = remainder.indexOf('.');
        return separator < 0 ? remainder : remainder.substring(0, separator);
    }
}
