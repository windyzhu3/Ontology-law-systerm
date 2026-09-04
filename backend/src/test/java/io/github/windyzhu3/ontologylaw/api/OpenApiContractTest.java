package io.github.windyzhu3.ontologylaw.api;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenApiContractTest {

    private static final String SCHEMA_REF_PREFIX = "#/components/schemas/";
    private static final String PARAMETER_REF_PREFIX = "#/components/parameters/";
    private static final String RESPONSE_REF_PREFIX = "#/components/responses/";

    private static final Map<OperationKey, String> REQUIRED_OPERATIONS = requiredOperations();
    private static final Map<String, Set<String>> ERROR_CODES_BY_OPERATION = requiredErrorCodes();
    private static final Map<String, OperationContract> OPERATION_CONTRACTS = operationContracts();
    private static final Map<String, ErrorRule> ERROR_RULES = errorRules();
    private static final Map<String, ExampleContract> EXAMPLE_CONTRACTS = exampleContracts();
    private static final Set<String> TASK_COMMAND_OPERATION_IDS = Set.of(
            "resolveDuplicateLead",
            "completeLeadIngress",
            "assignLead",
            "recordRoutingDisposition",
            "acknowledgeSourceIntakeStopRequest",
            "recordContactResult",
            "reviewLeadValidity"
    );
    private static final Set<String> REQUIRED_EXAMPLES = Set.of(
            "capture-lead.request.json",
            "current-work-card.response.json",
            "save-action-draft.request.json",
            "resolve-duplicate-lead.request.json",
            "complete-lead-ingress.request.json",
            "assign-lead.request.json",
            "record-routing-disposition.request.json",
            "acknowledge-source-intake-stop-request.request.json",
            "record-contact-result.request.json",
            "review-lead-validity.request.json",
            "command-receipt.response.json",
            "reopen-due-contact-tasks.request.json",
            "problem.response.json"
    );
    private static final Set<String> FORBIDDEN_PUBLIC_NAMES = Set.of(
            "tenantid",
            "tenant_id",
            "x-tenant-id",
            "principalid",
            "principal_id",
            "grantid",
            "grant_id",
            "organizationid",
            "organization_id",
            "commandexecutionslotid",
            "command_execution_slot_id"
    );
    private static final List<ActionVariant> ACTION_VARIANTS = List.of(
            new ActionVariant(
                    "RESOLVE_DUPLICATE_LEAD",
                    "ResolveDuplicateLeadValuesV1",
                    "PartialResolveDuplicateLeadValuesV1",
                    "ResolveDuplicateLeadDraftBinding",
                    "ResolveDuplicateLeadCommandForm",
                    "ResolveDuplicateLeadDraftProjection"
            ),
            new ActionVariant(
                    "COMPLETE_LEAD_INGRESS",
                    "CompleteLeadIngressValuesV1",
                    "PartialCompleteLeadIngressValuesV1",
                    "CompleteLeadIngressDraftBinding",
                    "CompleteLeadIngressCommandForm",
                    "CompleteLeadIngressDraftProjection"
            ),
            new ActionVariant(
                    "ASSIGN_LEAD",
                    "AssignLeadValuesV1",
                    "PartialAssignLeadValuesV1",
                    "AssignLeadDraftBinding",
                    "AssignLeadCommandForm",
                    "AssignLeadDraftProjection"
            ),
            new ActionVariant(
                    "RECORD_ROUTING_DISPOSITION",
                    "RecordRoutingDispositionValuesV1",
                    "PartialRecordRoutingDispositionValuesV1",
                    "RecordRoutingDispositionDraftBinding",
                    "RecordRoutingDispositionCommandForm",
                    "RecordRoutingDispositionDraftProjection"
            ),
            new ActionVariant(
                    "ACKNOWLEDGE_SOURCE_INTAKE_STOP_REQUEST",
                    "AcknowledgeSourceIntakeStopRequestValuesV1",
                    "PartialAcknowledgeSourceIntakeStopRequestValuesV1",
                    "AcknowledgeSourceIntakeStopRequestDraftBinding",
                    "AcknowledgeSourceIntakeStopRequestCommandForm",
                    "AcknowledgeSourceIntakeStopRequestDraftProjection"
            ),
            new ActionVariant(
                    "RECORD_CONTACT_RESULT",
                    "RecordContactResultValuesV1",
                    "PartialRecordContactResultValuesV1",
                    "RecordContactResultDraftBinding",
                    "RecordContactResultCommandForm",
                    "RecordContactResultDraftProjection"
            ),
            new ActionVariant(
                    "REVIEW_LEAD_VALIDITY",
                    "ReviewLeadValidityValuesV1",
                    "PartialReviewLeadValidityValuesV1",
                    "ReviewLeadValidityDraftBinding",
                    "ReviewLeadValidityCommandForm",
                    "ReviewLeadValidityDraftProjection"
            )
    );
    private static final List<CurrentCardVariant> CURRENT_CARD_VARIANTS = List.of(
            new CurrentCardVariant(
                    "RESOLVE_LEAD_DUPLICATE",
                    "ResolveLeadDuplicateCurrentCard",
                    "RESOLVE_DUPLICATE_LEAD"
            ),
            new CurrentCardVariant(
                    "COMPLETE_LEAD_INGRESS",
                    "CompleteLeadIngressCurrentCard",
                    "COMPLETE_LEAD_INGRESS"
            ),
            new CurrentCardVariant(
                    "ASSIGN_LEAD",
                    "AssignLeadCurrentCard",
                    "ASSIGN_LEAD"
            ),
            new CurrentCardVariant(
                    "RESOLVE_LEAD_ROUTING_GAP",
                    "ResolveLeadRoutingGapCurrentCard",
                    "RECORD_ROUTING_DISPOSITION"
            ),
            new CurrentCardVariant(
                    "ACK_SOURCE_INTAKE_STOP_REQUEST",
                    "AcknowledgeSourceIntakeStopRequestCurrentCard",
                    "ACKNOWLEDGE_SOURCE_INTAKE_STOP_REQUEST"
            ),
            new CurrentCardVariant(
                    "CONTACT_LEAD",
                    "ContactLeadCurrentCard",
                    "RECORD_CONTACT_RESULT"
            ),
            new CurrentCardVariant(
                    "REVIEW_LEAD_VALIDITY",
                    "ReviewLeadValidityCurrentCard",
                    "REVIEW_LEAD_VALIDITY"
            )
    );

    private static Path repositoryRoot;
    private static Path specPath;
    private static JsonNode document;

    @BeforeAll
    static void loadCanonicalContract() throws IOException {
        repositoryRoot = locateRepositoryRoot();
        specPath = repositoryRoot.resolve("contracts/openapi/ontology-law-api.yaml");

        assertTrue(
                Files.isRegularFile(specPath),
                () -> "Canonical OpenAPI spec is missing: " + specPath
        );

        Path contractDirectory = specPath.getParent();
        List<Path> specifications;
        try (Stream<Path> files = Files.list(contractDirectory)) {
            specifications = files
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return fileName.endsWith(".yaml") || fileName.endsWith(".yml");
                    })
                    .sorted()
                    .toList();
        }
        assertEquals(
                List.of(specPath),
                specifications,
                "contracts/openapi must contain exactly the canonical OpenAPI document"
        );

        ParseOptions parseOptions = new ParseOptions();
        parseOptions.setResolve(true);
        parseOptions.setResolveFully(true);
        SwaggerParseResult parseResult = new OpenAPIV3Parser()
                .readLocation(specPath.toString(), null, parseOptions);
        assertNotNull(parseResult.getOpenAPI(), () -> "swagger-parser could not parse " + specPath);
        List<String> parserMessages = parseResult.getMessages() == null
                ? List.of()
                : parseResult.getMessages();
        assertTrue(
                parserMessages.isEmpty(),
                () -> "swagger-parser reported contract errors: " + parserMessages
        );
        assertEquals(
                "3.1.0",
                parseResult.getOpenAPI().getOpenapi(),
                "swagger-parser must recognize the canonical document as OpenAPI 3.1.0"
        );

        try (var input = Files.newInputStream(specPath)) {
            document = JsonNode.wrap(new Yaml().load(input));
        }
    }

    @Test
    void canonicalDocumentUsesOpenApi31AndHasNoGlobalSecurityFallback() {
        assertEquals("3.1.0", document.path("openapi").asText(), "OpenAPI version must be frozen at 3.1.0");
        JsonNode globalSecurity = document.path("security");
        assertTrue(
                globalSecurity.isMissingNode() || globalSecurity.size() == 0,
                "security must be bound explicitly per operation"
        );
    }

    @Test
    void freezesExactlyTwelveNamedOperations() {
        Map<OperationKey, String> actual = new LinkedHashMap<>();
        JsonNode paths = document.path("paths");
        paths.fields().forEachRemaining(pathEntry -> pathEntry.getValue().fields().forEachRemaining(methodEntry -> {
            String method = methodEntry.getKey().toUpperCase(Locale.ROOT);
            if (!Set.of("GET", "PUT", "POST", "DELETE", "PATCH", "HEAD", "OPTIONS", "TRACE").contains(method)) {
                return;
            }
            String path = pathEntry.getKey();
            String previous = actual.put(
                    new OperationKey(method, path),
                    methodEntry.getValue().path("operationId").asText()
            );
            assertTrue(previous == null, () -> "Duplicate operation at " + method + " " + path);
        }));

        assertEquals(REQUIRED_OPERATIONS, actual, "The R1 HTTP surface must remain closed and exact");
        assertEquals(
                REQUIRED_OPERATIONS.size(),
                paths.size(),
                "Each frozen R1 path must expose exactly one business operation"
        );
    }

    @Test
    void freezesEveryRequestAndSuccessfulResponseProjection() {
        assertEquals(new HashSet<>(REQUIRED_OPERATIONS.values()), OPERATION_CONTRACTS.keySet(),
                "Every frozen operation needs an exact transport contract");

        OPERATION_CONTRACTS.forEach((operationId, contract) -> {
            OperationKey key = operationKey(operationId);
            JsonNode operation = operationNode(key);

            Set<String> actualParameterRefs = new HashSet<>();
            for (JsonNode parameter : rawParameters(key)) {
                assertTrue(parameter.hasNonNull("$ref"),
                        () -> operationId + " parameters must use frozen reusable components");
                actualParameterRefs.add(parameter.path("$ref").asText());
            }
            assertEquals(contract.parameterRefs(), actualParameterRefs,
                    () -> operationId + " exact parameter/header set");
            assertEquals(contract.parameterRefs().size(), rawParameters(key).size(),
                    () -> operationId + " must not duplicate parameters");

            JsonNode requestBody = operation.path("requestBody");
            if (contract.requestSchema() == null) {
                assertTrue(requestBody.isMissingNode(), () -> operationId + " must not declare a request body");
            } else {
                assertTrue(requestBody.path("required").asBoolean(), () -> operationId + " request body is required");
                assertEquals(Set.of("application/json"), fieldNames(requestBody.path("content")),
                        () -> operationId + " request media type");
                assertEquals(
                        schemaRef(contract.requestSchema()),
                        requestBody.path("content").path("application/json").path("schema").path("$ref").asText(),
                        () -> operationId + " request schema"
                );
            }

            JsonNode responses = operation.path("responses");
            Set<String> actualSuccessStatuses = new HashSet<>();
            responses.fieldNames().forEachRemaining(status -> {
                if (isSuccessStatus(status)) {
                    actualSuccessStatuses.add(status);
                }
            });
            assertEquals(contract.successResponses().keySet(), actualSuccessStatuses,
                    () -> operationId + " exact success statuses");

            contract.successResponses().forEach((status, expected) -> {
                JsonNode rawResponse = responses.path(status);
                if (expected.responseComponent() != null) {
                    assertEquals(
                            responseRef(expected.responseComponent()),
                            rawResponse.path("$ref").asText(),
                            () -> operationId + " " + status + " reusable response"
                    );
                }
                JsonNode response = dereference(rawResponse);
                assertEquals(expected.headerComponents().keySet(), fieldNames(response.path("headers")),
                        () -> operationId + " " + status + " exact success headers");
                expected.headerComponents().forEach((headerName, componentName) -> assertEquals(
                        "#/components/headers/" + componentName,
                        response.path("headers").path(headerName).path("$ref").asText(),
                        () -> operationId + " " + status + " " + headerName
                ));

                JsonNode content = response.path("content");
                if (expected.responseSchema() == null) {
                    assertTrue(content.isMissingNode() || content.size() == 0,
                            () -> operationId + " " + status + " must not declare a body");
                } else {
                    assertEquals(Set.of("application/json"), fieldNames(content),
                            () -> operationId + " " + status + " success media type");
                    assertEquals(
                            schemaRef(expected.responseSchema()),
                            content.path("application/json").path("schema").path("$ref").asText(),
                            () -> operationId + " " + status + " response schema"
                    );
                }
            });
        });
    }

    @Test
    void bindsExactlyBearerForPublicOperationsAndMutualTlsForInternalOperation() {
        JsonNode securitySchemes = document.at("/components/securitySchemes");
        assertTrue(securitySchemes.isObject(), "components.securitySchemes is required");
        assertEquals(Set.of("publicBearer", "internalMutualTls"), fieldNames(securitySchemes));

        JsonNode publicBearer = dereference(securitySchemes.get("publicBearer"));
        assertEquals("http", publicBearer.path("type").asText());
        assertEquals("bearer", publicBearer.path("scheme").asText());

        JsonNode internalMutualTls = dereference(securitySchemes.get("internalMutualTls"));
        assertEquals("mutualTLS", internalMutualTls.path("type").asText());

        REQUIRED_OPERATIONS.forEach((key, operationId) -> {
            String expectedScheme = key.path().startsWith("/internal/")
                    ? "internalMutualTls"
                    : "publicBearer";
            JsonNode security = operationNode(key).path("security");
            assertTrue(security.isArray(), () -> operationId + " must declare operation security");
            assertEquals(1, security.size(), () -> operationId + " must declare exactly one security alternative");
            JsonNode requirement = security.get(0);
            assertEquals(Set.of(expectedScheme), fieldNames(requirement), () -> operationId + " security binding");
            assertTrue(requirement.get(expectedScheme).isArray(), () -> operationId + " scopes must be an array");
            assertEquals(0, requirement.get(expectedScheme).size(), () -> operationId + " must not invent OAuth scopes");
        });
    }

    @Test
    void bindsAuthenticationChallengesToTheSelectedTransport() {
        REQUIRED_OPERATIONS.forEach((key, operationId) -> {
            JsonNode unauthorizedResponse = dereference(
                    operationNode(key).path("responses").path("401")
            );
            JsonNode headers = unauthorizedResponse.path("headers");
            if (key.path().startsWith("/internal/")) {
                assertFalse(headers.has("WWW-Authenticate"), "mTLS 401 must not send a Bearer challenge");
            } else {
                JsonNode challenge = dereference(headers.path("WWW-Authenticate"));
                assertFalse(challenge.isMissingNode(), () -> operationId + " 401 must send WWW-Authenticate");
                assertBearerChallengeSchema(dereference(challenge.path("schema")));
            }
        });
    }

    @Test
    void freezesPerOperationErrorStatusCodeAndMetadataMatrix() {
        REQUIRED_OPERATIONS.forEach((key, operationId) -> {
            JsonNode operation = operationNode(key);
            Map<String, Set<String>> expectedByStatus = expectedErrorsByStatus(operationId);
            assertEquals(
                    ERROR_CODES_BY_OPERATION.get(operationId),
                    stringSet(operation.path("x-error-codes")),
                    () -> operationId + " x-error-codes"
            );

            JsonNode responses = operation.path("responses");
            Set<String> actualErrorStatuses = new HashSet<>();
            responses.fieldNames().forEachRemaining(status -> {
                if (!isSuccessStatus(status)) {
                    actualErrorStatuses.add(status);
                }
            });
            assertEquals(expectedByStatus.keySet(), actualErrorStatuses,
                    () -> operationId + " exact error statuses");

            expectedByStatus.forEach((status, expectedCodes) -> {
                JsonNode rawResponse = responses.path(status);
                assertEquals(
                        responseRef(errorResponseComponent(status, key.path().startsWith("/internal/"))),
                        rawResponse.path("$ref").asText(),
                        () -> operationId + " " + status + " error response component"
                );
                JsonNode response = dereference(rawResponse);
                JsonNode content = response.path("content");
                assertEquals(
                        Set.of("application/problem+json"),
                        fieldNames(content),
                        () -> operationId + " " + status + " error media type"
                );
                JsonNode problemSchema = content.path("application/problem+json").path("schema");
                assertEquals(
                        "#/components/schemas/Problem",
                        problemSchema.path("$ref").asText(),
                        () -> operationId + " " + status + " error schema"
                );

                Map<String, String> expectedHeaders = expectedErrorHeaders(
                        status,
                        key.path().startsWith("/internal/")
                );
                assertEquals(expectedHeaders.keySet(), fieldNames(response.path("headers")),
                        () -> operationId + " " + status + " exact error headers");
                expectedHeaders.forEach((name, component) -> assertEquals(
                        "#/components/headers/" + component,
                        response.path("headers").path(name).path("$ref").asText(),
                        () -> operationId + " " + status + " " + name
                ));
            });
        });
    }

    @Test
    void freezesIdempotencyAndConditionalRequestHeaders() {
        REQUIRED_OPERATIONS.forEach((key, operationId) -> {
            if (!key.method().equals("GET")) {
                JsonNode idempotencyKey = requiredParameter(key, "header", "Idempotency-Key");
                assertTrue(idempotencyKey.path("required").asBoolean(), () -> operationId + " requires Idempotency-Key");
                JsonNode schema = dereference(idempotencyKey.path("schema"));
                assertEquals("string", schema.path("type").asText(), () -> operationId + " Idempotency-Key type");
                assertEquals("uuid", schema.path("format").asText(), () -> operationId + " Idempotency-Key format");
            } else {
                assertParameterAbsent(key, "Idempotency-Key");
            }
        });

        REQUIRED_OPERATIONS.forEach((key, operationId) -> {
            if (TASK_COMMAND_OPERATION_IDS.contains(operationId)) {
                JsonNode ifMatch = requiredParameter(key, "header", "If-Match");
                assertTrue(ifMatch.path("required").asBoolean(), () -> operationId + " requires Task If-Match");
                assertStrongEtagSchema(dereference(ifMatch.path("schema")), "task", operationId + " If-Match");
                assertParameterAbsent(key, "If-None-Match");
            }
        });

        OperationKey saveDraft = operationKey("saveActionDraft");
        JsonNode draftIfMatch = requiredParameter(saveDraft, "header", "If-Match");
        JsonNode draftIfNoneMatch = requiredParameter(saveDraft, "header", "If-None-Match");
        assertFalse(draftIfMatch.path("required").asBoolean(), "Draft If-Match is conditional, not independently required");
        assertFalse(draftIfNoneMatch.path("required").asBoolean(), "Draft If-None-Match is conditional, not independently required");
        assertStrongEtagSchema(dereference(draftIfMatch.path("schema")), "draft", "saveActionDraft If-Match");
        assertSchemaAcceptsOnlyLiteral(dereference(draftIfNoneMatch.path("schema")), "*");
        assertEquals(
                "exactly-one-of-if-none-match-star-or-if-match-draft-etag",
                operationNode(saveDraft).path("x-precondition-mode").asText(),
                "saveActionDraft must mechanically declare its exactly-one conditional-header rule"
        );

        OperationKey current = operationKey("getCurrentWorkCard");
        JsonNode workbenchIfNoneMatch = requiredParameter(current, "header", "If-None-Match");
        assertFalse(workbenchIfNoneMatch.path("required").asBoolean(), "Workbench If-None-Match is optional");
        assertStrongEtagSchema(
                dereference(workbenchIfNoneMatch.path("schema")),
                "wb",
                "getCurrentWorkCard If-None-Match"
        );

        for (String operationId : Set.of("captureLead", "reopenDueContactTasks", "getCommandReceipt")) {
            OperationKey key = operationKey(operationId);
            assertParameterAbsent(key, "If-Match");
            assertParameterAbsent(key, "If-None-Match");
        }
    }

    @Test
    void reusablePathAndResponseHeadersKeepTheirExactWireSemantics() {
        assertPathUuidParameter("TaskIdPath", "taskId");
        assertPathUuidParameter("CommandIdPath", "commandId");

        JsonNode receiptLocation = document.at("/components/headers/ReceiptLocation/schema");
        assertEquals("string", receiptLocation.path("type").asText(), "Receipt Location type");
        assertEquals(
                "^/api/v1/commands/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}/receipt$",
                receiptLocation.path("pattern").asText(),
                "Receipt Location must be the exact command recovery route"
        );
        Pattern locationPattern = Pattern.compile(receiptLocation.path("pattern").asText());
        assertTrue(locationPattern.matcher(
                "/api/v1/commands/01955c2a-7b14-7a12-8f45-4f6e8c9a1013/receipt").matches());
        assertFalse(locationPattern.matcher(
                "https://example.invalid/api/v1/commands/01955c2a-7b14-7a12-8f45-4f6e8c9a1013/receipt").matches());

        JsonNode retryAfter = document.at("/components/headers/RetryAfter/schema");
        assertEquals("integer", retryAfter.path("type").asText(), "Retry-After type");
        assertEquals(0L, retryAfter.path("minimum").asLong(), "Retry-After lower bound");
    }

    @Test
    void shipsMountsAndValidatesExactlyThirteenJsonExamples() throws IOException {
        Path examplesDirectory = specPath.getParent().resolve("examples");
        assertTrue(Files.isDirectory(examplesDirectory), "OpenAPI examples directory is required");

        Set<String> actual;
        try (Stream<Path> files = Files.list(examplesDirectory)) {
            actual = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .map(path -> path.getFileName().toString())
                    .collect(java.util.stream.Collectors.toSet());
        }
        assertEquals(REQUIRED_EXAMPLES, actual, "The executable example set is a closed R1 contract");
        assertEquals(13, EXAMPLE_CONTRACTS.size(), "Every example needs one exact mount contract");
        assertEquals(EXAMPLE_CONTRACTS.keySet(), fieldNames(document.at("/components/examples")),
                "components.examples must equal the frozen executable set");

        Map<String, List<String>> exampleRefLocations = new LinkedHashMap<>();
        collectExampleRefLocations(document, "", exampleRefLocations);
        Set<String> expectedRefs = new HashSet<>();

        EXAMPLE_CONTRACTS.forEach((componentName, contract) -> {
            Path example = examplesDirectory.resolve(contract.fileName());
            assertTrue(Files.isRegularFile(example), () -> "Missing example: " + contract.fileName());

            JsonNode component = document.at("/components/examples/" + jsonPointerToken(componentName));
            assertEquals(Set.of("externalValue"), fieldNames(component),
                    () -> componentName + " must be one externalValue and no inline shadow copy");
            assertEquals("./examples/" + contract.fileName(), component.path("externalValue").asText(),
                    () -> componentName + " externalValue");

            JsonNode media = document.at(contract.mediaPointer());
            assertFalse(media.isMissingNode(), () -> componentName + " mount media is missing");
            assertEquals(schemaRef(contract.schemaName()), media.path("schema").path("$ref").asText(),
                    () -> componentName + " mounted schema");
            assertEquals(Set.of(contract.exampleKey()), fieldNames(media.path("examples")),
                    () -> componentName + " must be the only example at its frozen media location");
            assertEquals(
                    "#/components/examples/" + componentName,
                    media.path("examples").path(contract.exampleKey()).path("$ref").asText(),
                    () -> componentName + " mount reference"
            );

            String componentRef = "#/components/examples/" + componentName;
            expectedRefs.add(componentRef);
            assertEquals(
                    List.of(contract.mediaPointer()
                            + "/examples/" + jsonPointerToken(contract.exampleKey()) + "/$ref"),
                    exampleRefLocations.getOrDefault(componentRef, List.of()),
                    () -> componentName + " must have one exact semantic mount"
            );

            try (JsonParser parser = new JsonFactory().createParser(example.toFile())) {
                assertEquals(JsonToken.START_OBJECT, parser.nextToken(),
                        () -> contract.fileName() + " must contain one JSON object");
                parser.skipChildren();
                assertEquals(null, parser.nextToken(),
                        () -> contract.fileName() + " must not contain a trailing JSON document");
            } catch (IOException parseFailure) {
                throw new AssertionError("Malformed JSON example: " + contract.fileName(), parseFailure);
            }

            JsonNode instance;
            try (var input = Files.newInputStream(example)) {
                instance = JsonNode.wrap(new Yaml().load(input));
            } catch (IOException | RuntimeException parseFailure) {
                throw new AssertionError("Could not load JSON example: " + contract.fileName(), parseFailure);
            }
            List<String> violations = schemaViolations(
                    instance,
                    schema(contract.schemaName()),
                    "$",
                    new HashSet<>()
            );
            assertEquals(List.of(), violations,
                    () -> contract.fileName() + " violates " + contract.schemaName() + ": " + violations);
        });

        assertEquals(expectedRefs, exampleRefLocations.keySet(),
                "No example component may be mounted outside the frozen media locations");
    }

    @Test
    void neverExposesTenantOrInternalIdentityAsAParameterOrSchemaProperty() {
        List<String> violations = new ArrayList<>();
        collectForbiddenNames(document, "$", violations);
        assertEquals(List.of(), violations, "Tenant and internal identity are derived from server-side ActorContext");
    }

    @Test
    void draftAndWorkbenchSchemasFreezeActionDependentClosedUnions() {
        JsonNode saveDraft = schema("SaveActionDraftV1");
        JsonNode actionDraft = schema("ActionDraftProjection");
        JsonNode commandForm = schema("CommandForm");
        JsonNode currentCard = schema("CurrentCard");

        Set<String> saveProperties = Set.of("actionCode", "schemaVersion", "values");
        Set<String> commandFormProperties = Set.of("actionCode", "schemaVersion", "values", "fields");
        Set<String> actionDraftProperties = Set.of(
                "draftId", "draftRevision", "actionCode", "schemaVersion",
                "values", "digest", "updatedAt", "editable"
        );
        Set<String> currentCardProperties = Set.of(
                "taskId", "taskType", "taskRevision", "subject", "owner", "businessPurpose",
                "primaryCommand", "expectedCompletionFact", "sla", "versionStatus", "commandForm",
                "actionDraft", "preconditions"
        );

        assertClosedObjectShape(saveDraft, saveProperties, saveProperties, "SaveActionDraftV1");
        assertClosedObjectShape(commandForm, commandFormProperties, commandFormProperties, "CommandForm");
        assertClosedObjectShape(actionDraft, actionDraftProperties, actionDraftProperties, "ActionDraftProjection");
        assertClosedObjectShape(currentCard, currentCardProperties, currentCardProperties, "CurrentCard");

        Set<String> fullValueRefs = new HashSet<>();
        Set<String> saveBindingRefs = new HashSet<>();
        Set<String> commandFormRefs = new HashSet<>();
        Set<String> actionDraftRefs = new HashSet<>();
        Map<String, String> saveMappings = new LinkedHashMap<>();
        Map<String, String> commandFormMappings = new LinkedHashMap<>();
        Map<String, String> actionDraftMappings = new LinkedHashMap<>();
        for (ActionVariant variant : ACTION_VARIANTS) {
            fullValueRefs.add(schemaRef(variant.fullValuesSchema()));
            saveBindingRefs.add(schemaRef(variant.saveBindingSchema()));
            commandFormRefs.add(schemaRef(variant.commandFormSchema()));
            actionDraftRefs.add(schemaRef(variant.actionDraftSchema()));
            saveMappings.put(variant.actionCode(), schemaRef(variant.saveBindingSchema()));
            commandFormMappings.put(variant.actionCode(), schemaRef(variant.commandFormSchema()));
            actionDraftMappings.put(variant.actionCode(), schemaRef(variant.actionDraftSchema()));

            assertClosedComponent(variant.fullValuesSchema());
            assertClosedComponent(variant.partialValuesSchema());
            assertActionBinding(
                    variant.saveBindingSchema(),
                    variant.actionCode(),
                    variant.fullValuesSchema(),
                    saveProperties
            );
            assertActionBinding(
                    variant.commandFormSchema(),
                    variant.actionCode(),
                    variant.partialValuesSchema(),
                    commandFormProperties
            );
            assertActionBinding(
                    variant.actionDraftSchema(),
                    variant.actionCode(),
                    variant.fullValuesSchema(),
                    actionDraftProperties
            );
        }

        assertExactOneOfRefs(
                saveDraft.path("properties").path("values"),
                fullValueRefs,
                "SaveActionDraftV1.values"
        );
        assertExactOneOfRefs(
                actionDraft.path("properties").path("values"),
                fullValueRefs,
                "ActionDraftProjection.values"
        );
        assertDiscriminatorAndBranches(
                saveDraft,
                "actionCode",
                saveMappings,
                saveBindingRefs,
                "SaveActionDraftV1"
        );
        assertDiscriminatorAndBranches(
                commandForm,
                "actionCode",
                commandFormMappings,
                commandFormRefs,
                "CommandForm"
        );
        assertDiscriminatorAndBranches(
                actionDraft,
                "actionCode",
                actionDraftMappings,
                actionDraftRefs,
                "ActionDraftProjection"
        );

        Set<String> currentCardRefs = new HashSet<>();
        Map<String, String> currentCardMappings = new LinkedHashMap<>();
        for (CurrentCardVariant cardVariant : CURRENT_CARD_VARIANTS) {
            currentCardRefs.add(schemaRef(cardVariant.currentCardSchema()));
            currentCardMappings.put(cardVariant.taskType(), schemaRef(cardVariant.currentCardSchema()));

            ActionVariant actionVariant = actionVariant(cardVariant.actionCode());
            JsonNode cardSchema = schema(cardVariant.currentCardSchema());
            assertClosedObjectShape(
                    cardSchema,
                    currentCardProperties,
                    currentCardProperties,
                    cardVariant.currentCardSchema()
            );

            JsonNode taskType = cardSchema.path("properties").path("taskType");
            assertEquals(schemaRef("TaskType"), taskType.path("$ref").asText(),
                    cardVariant.currentCardSchema() + " taskType must reuse TaskType");
            assertSchemaAcceptsOnlyLiteral(taskType, cardVariant.taskType());

            assertEquals(
                    schemaRef(actionVariant.commandFormSchema()),
                    cardSchema.path("properties").path("commandForm").path("$ref").asText(),
                    cardVariant.currentCardSchema() + " commandForm binding"
            );
            assertNullableOneOfRef(
                    cardSchema.path("properties").path("actionDraft"),
                    schemaRef(actionVariant.actionDraftSchema()),
                    cardVariant.currentCardSchema() + " actionDraft binding"
            );
        }
        assertDiscriminatorAndBranches(
                currentCard,
                "taskType",
                currentCardMappings,
                currentCardRefs,
                "CurrentCard"
        );
    }

    @Test
    void recordContactResultRequiresLegalNeedOnlyForConnectedValid() {
        JsonNode schema = schema("RecordContactResultV1");
        assertTrue(schema.path("properties").has("legalNeed"), "RecordContactResultV1 must declare legalNeed");
        assertFalse(stringSet(schema.path("required")).contains("legalNeed"), "legalNeed is not unconditionally required");

        JsonNode conditional = findResultCodeConditional(schema);
        assertNotNull(conditional, "RecordContactResultV1 must encode a CONNECTED_VALID if/then/else rule");
        assertTrue(
                stringSet(conditional.path("then").path("required")).contains("legalNeed"),
                "CONNECTED_VALID requires legalNeed"
        );
        JsonNode forbidden = conditional.path("else").path("not").path("required");
        assertTrue(stringSet(forbidden).contains("legalNeed"), "Other results must forbid legalNeed");
    }

    @Test
    void problemShapeHasExactlyTheFrozenRequiredFieldsAndOptionalExtensions() {
        JsonNode problem = schema("Problem");
        assertEquals(
                Set.of("type", "title", "status", "code", "detail", "instance", "retryPolicy"),
                stringSet(problem.path("required")),
                "Problem required fields"
        );
        assertEquals(
                Set.of(
                        "type", "title", "status", "code", "detail", "instance", "retryPolicy",
                        "fieldErrors", "currentETag", "receiptRef"
                ),
                fieldNames(problem.path("properties")),
                "Problem must expose only the frozen RFC 9457 fields and safe extensions"
        );
        assertTrue(problem.path("additionalProperties").isBoolean());
        assertFalse(problem.path("additionalProperties").asBoolean(), "Problem rejects undeclared fields");
    }

    @Test
    void problemSchemaEnforcesTheCompleteErrorRegistry() {
        assertEquals(ERROR_RULES.keySet(), stringSet(schema("ErrorCode").path("enum")),
                "ErrorCode must equal the frozen R1 registry");
        assertEquals(
                Set.of(
                        "NO",
                        "SAME_KEY_AFTER_FIX",
                        "SAME_KEY_AFTER_REAUTH",
                        "NEW_KEY_AFTER_REFRESH",
                        "NEW_KEY_AFTER_ADMIN_FIX",
                        "SAME_KEY_AFTER_BACKOFF"
                ),
                stringSet(schema("RetryPolicy").path("enum")),
                "RetryPolicy must equal the frozen R1 registry"
        );

        JsonNode problemSchema = schema("Problem");
        ERROR_RULES.forEach((code, rule) -> {
            Map<String, Object> valid = validProblem(code, rule);
            assertSchemaAccepts(valid, problemSchema, code + " canonical Problem");

            Map<String, Object> wrongStatus = copy(valid);
            wrongStatus.put("status", rule.status() == 400 ? 401 : 400);
            assertSchemaRejects(wrongStatus, problemSchema, code + " wrong HTTP status");

            Map<String, Object> wrongRetry = copy(valid);
            wrongRetry.put("retryPolicy", rule.retryPolicy().equals("NO") ? "SAME_KEY_AFTER_FIX" : "NO");
            assertSchemaRejects(wrongRetry, problemSchema, code + " wrong retryPolicy");

            if (rule.fieldErrorsRequired()) {
                Map<String, Object> missingFieldErrors = copy(valid);
                missingFieldErrors.remove("fieldErrors");
                assertSchemaRejects(missingFieldErrors, problemSchema, code + " missing fieldErrors");
            } else {
                Map<String, Object> forbiddenFieldErrors = copy(valid);
                forbiddenFieldErrors.put("fieldErrors", validFieldErrors());
                assertSchemaRejects(forbiddenFieldErrors, problemSchema, code + " forbidden fieldErrors");
            }

            if (rule.currentEtagKind() == null) {
                Map<String, Object> forbiddenCurrentEtag = copy(valid);
                forbiddenCurrentEtag.put("currentETag", currentEtag("TASK"));
                assertSchemaRejects(forbiddenCurrentEtag, problemSchema, code + " forbidden currentETag");
            } else {
                if (rule.currentEtagRequired()) {
                    Map<String, Object> missingCurrentEtag = copy(valid);
                    missingCurrentEtag.remove("currentETag");
                    assertSchemaRejects(missingCurrentEtag, problemSchema, code + " missing currentETag");
                } else {
                    Map<String, Object> absentCurrentEtag = copy(valid);
                    absentCurrentEtag.remove("currentETag");
                    assertSchemaAccepts(absentCurrentEtag, problemSchema,
                            code + " may omit currentETag when the Draft does not exist");
                }
                Map<String, Object> wrongCurrentEtag = copy(valid);
                wrongCurrentEtag.put("currentETag", currentEtag(otherResourceKind(rule.currentEtagKind())));
                assertSchemaRejects(wrongCurrentEtag, problemSchema, code + " wrong currentETag kind");
            }

            if (rule.receiptRefRequired()) {
                Map<String, Object> missingReceiptRef = copy(valid);
                missingReceiptRef.remove("receiptRef");
                assertSchemaRejects(missingReceiptRef, problemSchema, code + " missing receiptRef");
            } else {
                Map<String, Object> terminalRejectedReceipt = copy(valid);
                terminalRejectedReceipt.put("receiptRef", receiptRef());
                assertSchemaAccepts(terminalRejectedReceipt, problemSchema,
                        code + " may carry an already-terminal REJECTED receiptRef");
            }
        });
    }

    @Test
    void successfulReceiptsRejectRejectionsAndBindExactCompletionFacts() {
        Map<String, SuccessfulReceiptContract> contracts = Map.of(
                "LeadCommandReceipt", new SuccessfulReceiptContract("LeadFactRef", "LEAD", "revision"),
                "ActionDraftCommandReceipt",
                new SuccessfulReceiptContract("ActionDraftFactRef", "ACTION_DRAFT", "revision"),
                "TaskOccurrenceCommandReceipt",
                new SuccessfulReceiptContract("TaskOccurrenceFactRef", "TASK_OCCURRENCE", "revision"),
                "DecisionRecordCommandReceipt",
                new SuccessfulReceiptContract("DecisionRecordFactRef", "DECISION_RECORD", "digest"),
                "LeadAssignmentCommandReceipt",
                new SuccessfulReceiptContract("LeadAssignmentFactRef", "LEAD_ASSIGNMENT", "revision"),
                "LeadContactResultCommandReceipt",
                new SuccessfulReceiptContract("LeadContactResultFactRef", "LEAD_CONTACT_RESULT", "digest")
        );

        contracts.forEach((receiptName, contract) -> {
            JsonNode receipt = schema(receiptName);
            Set<String> receiptFields = Set.of("commandId", "receiptId", "outcome", "completedAt", "resultFact");
            assertClosedObjectShape(receipt, receiptFields, receiptFields, receiptName);
            assertEquals(Set.of("SUCCEEDED", "NO_CHANGE"),
                    stringSet(receipt.path("properties").path("outcome").path("enum")),
                    receiptName + " outcome");
            assertEquals(schemaRef(contract.factSchema()),
                    receipt.path("properties").path("resultFact").path("$ref").asText(),
                    receiptName + " exact resultFact");
            assertPropertyRef(receipt, "commandId", "Uuid", receiptName);
            assertPropertyRef(receipt, "receiptId", "Uuid", receiptName);
            assertPropertyRef(receipt, "completedAt", "Instant", receiptName);

            JsonNode fact = schema(contract.factSchema());
            Set<String> factFields = Set.of("factType", "factRef", contract.versionField());
            assertClosedObjectShape(fact, factFields, factFields, contract.factSchema());
            assertSchemaAcceptsOnlyLiteral(fact.path("properties").path("factType"), contract.factType());
            assertPropertyRef(fact, "factRef", "OpaqueRef", contract.factSchema());
            assertPropertyRef(
                    fact,
                    contract.versionField(),
                    contract.versionField().equals("revision") ? "Revision" : "Digest32",
                    contract.factSchema()
            );
            assertFalse(fact.path("properties").has(contract.versionField().equals("revision") ? "digest" : "revision"),
                    contract.factSchema() + " must expose exactly one revision/digest selector");
        });

        assertEquals(schemaRef("ActionDraftCommandReceipt"),
                schema("ActionDraftWriteResult").path("properties").path("receipt").path("$ref").asText(),
                "Draft writes must return an ACTION_DRAFT success receipt");
        assertExactOneOfRefs(
                schema("CommandReceipt"),
                Set.of(schemaRef("SuccessfulCommandReceipt"), schemaRef("RejectedCommandReceipt")),
                "recoverable CommandReceipt"
        );
        assertClosedObjectShape(
                schema("SuccessfulCommandReceipt"),
                Set.of("commandId", "receiptId", "outcome", "completedAt", "resultFact"),
                Set.of("commandId", "receiptId", "outcome", "completedAt", "resultFact"),
                "SuccessfulCommandReceipt"
        );
        assertPropertyRef(schema("SuccessfulCommandReceipt"), "commandId", "Uuid", "SuccessfulCommandReceipt");
        assertPropertyRef(schema("SuccessfulCommandReceipt"), "receiptId", "Uuid", "SuccessfulCommandReceipt");
        assertPropertyRef(schema("SuccessfulCommandReceipt"), "completedAt", "Instant", "SuccessfulCommandReceipt");
        assertPropertyRef(schema("SuccessfulCommandReceipt"), "resultFact", "PublicFactRef", "SuccessfulCommandReceipt");
        assertClosedObjectShape(
                schema("RejectedCommandReceipt"),
                Set.of("commandId", "receiptId", "outcome", "completedAt", "rejectionCode"),
                Set.of("commandId", "receiptId", "outcome", "completedAt", "rejectionCode"),
                "RejectedCommandReceipt"
        );
        assertPropertyRef(schema("RejectedCommandReceipt"), "commandId", "Uuid", "RejectedCommandReceipt");
        assertPropertyRef(schema("RejectedCommandReceipt"), "receiptId", "Uuid", "RejectedCommandReceipt");
        assertPropertyRef(schema("RejectedCommandReceipt"), "completedAt", "Instant", "RejectedCommandReceipt");
        assertSchemaAcceptsOnlyLiteral(
                schema("RejectedCommandReceipt").path("properties").path("outcome"),
                "REJECTED"
        );
        assertEquals(
                schemaRef("TerminalRejectionCode"),
                schema("RejectedCommandReceipt").path("properties").path("rejectionCode").path("$ref").asText(),
                "Only post-slot business failures may be recovered as REJECTED receipts"
        );
        assertEquals(
                Set.of(
                        "NOT_AUTHORIZED", "APPOINTMENT_INACTIVE", "TASK_NOT_OPEN", "TASK_ALREADY_COMPLETED",
                        "DRAFT_DIGEST_MISMATCH", "INGRESS_COMPLETION_ALREADY_RECORDED",
                        "STALE_TASK", "STALE_DRAFT", "STALE_SUBJECT",
                        "SUPERVISOR_UNRESOLVED", "SOURCE_INTAKE_OWNER_UNRESOLVED"
                ),
                stringSet(schema("TerminalRejectionCode").path("enum")),
                "Terminal rejection codes must exclude pre-slot, conflict, rate-limit, and technical failures"
        );

        Set<String> publicFactRefs = new HashSet<>();
        contracts.values().forEach(contract -> publicFactRefs.add(schemaRef(contract.factSchema())));
        assertExactOneOfRefs(
                schema("PublicFactRef"),
                Set.copyOf(publicFactRefs),
                "PublicFactRef"
        );
    }

    @Test
    void everyWireEtagIsAResourceSpecificStrongOpaqueTag() {
        assertResponseEtag("getCurrentWorkCard", "200", "wb");
        assertResponseEtag("getCurrentWorkCard", "304", "wb");
        assertResponseEtag("saveActionDraft", "200", "draft");
        assertResponseEtag("saveActionDraft", "201", "draft");
        assertResponseEtag("reopenDueContactTasks", "200", "task");
    }

    private static Path locateRepositoryRoot() {
        Path cursor = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (cursor != null) {
            if (Files.isRegularFile(cursor.resolve("backend/pom.xml"))) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        return Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    private static Map<OperationKey, String> requiredOperations() {
        Map<OperationKey, String> operations = new LinkedHashMap<>();
        operations.put(new OperationKey("POST", "/api/v1/leads"), "captureLead");
        operations.put(new OperationKey("GET", "/api/v1/workcards/current"), "getCurrentWorkCard");
        operations.put(new OperationKey("PUT", "/api/v1/tasks/{taskId}/draft"), "saveActionDraft");
        operations.put(new OperationKey("POST", "/api/v1/tasks/{taskId}/commands/resolve-duplicate-lead"), "resolveDuplicateLead");
        operations.put(new OperationKey("POST", "/api/v1/tasks/{taskId}/commands/complete-lead-ingress"), "completeLeadIngress");
        operations.put(new OperationKey("POST", "/api/v1/tasks/{taskId}/commands/assign-lead"), "assignLead");
        operations.put(new OperationKey("POST", "/api/v1/tasks/{taskId}/commands/record-routing-disposition"), "recordRoutingDisposition");
        operations.put(new OperationKey("POST", "/api/v1/tasks/{taskId}/commands/acknowledge-source-intake-stop-request"), "acknowledgeSourceIntakeStopRequest");
        operations.put(new OperationKey("POST", "/api/v1/tasks/{taskId}/commands/record-contact-result"), "recordContactResult");
        operations.put(new OperationKey("POST", "/api/v1/tasks/{taskId}/commands/review-lead-validity"), "reviewLeadValidity");
        operations.put(new OperationKey("GET", "/api/v1/commands/{commandId}/receipt"), "getCommandReceipt");
        operations.put(new OperationKey("POST", "/internal/v1/tasks/commands/reopen-due-contact-tasks"), "reopenDueContactTasks");
        return Map.copyOf(operations);
    }

    private static Map<String, OperationContract> operationContracts() {
        Map<String, OperationContract> contracts = new LinkedHashMap<>();
        contracts.put("captureLead", operationContract(
                "CaptureLeadV1",
                parameters("IdempotencyKey"),
                success("201", null, "LeadCommandReceipt", headers("Location", "ReceiptLocation"))
        ));
        contracts.put("getCurrentWorkCard", operationContract(
                null,
                parameters("WorkbenchIfNoneMatch"),
                success("200", null, "CurrentWorkCardEnvelope", headers("ETag", "WorkbenchETagHeader")),
                success("304", null, null, headers("ETag", "WorkbenchETagHeader"))
        ));
        contracts.put("saveActionDraft", operationContract(
                "SaveActionDraftV1",
                parameters("TaskIdPath", "IdempotencyKey", "DraftIfMatch", "DraftCreateIfNoneMatch"),
                success("200", null, "ActionDraftWriteResult",
                        headers("Location", "ReceiptLocation", "ETag", "DraftETagHeader")),
                success("201", null, "ActionDraftWriteResult",
                        headers("Location", "ReceiptLocation", "ETag", "DraftETagHeader"))
        ));
        contracts.put("resolveDuplicateLead", taskCommandContract(
                "ResolveDuplicateLeadV1", "DecisionRecordCommandSucceeded"));
        contracts.put("completeLeadIngress", taskCommandContract(
                "CompleteLeadIngressV1", "LeadCommandSucceeded"));
        contracts.put("assignLead", taskCommandContract(
                "AssignLeadV1", "LeadAssignmentCommandSucceeded"));
        contracts.put("recordRoutingDisposition", taskCommandContract(
                "RecordRoutingDispositionV1", "DecisionRecordCommandSucceeded"));
        contracts.put("acknowledgeSourceIntakeStopRequest", taskCommandContract(
                "AcknowledgeSourceIntakeStopRequestV1", "DecisionRecordCommandSucceeded"));
        contracts.put("recordContactResult", taskCommandContract(
                "RecordContactResultV1", "LeadContactResultCommandSucceeded"));
        contracts.put("reviewLeadValidity", taskCommandContract(
                "ReviewLeadValidityV1", "DecisionRecordCommandSucceeded"));
        contracts.put("getCommandReceipt", operationContract(
                null,
                parameters("CommandIdPath"),
                success("200", null, "CommandReceipt", Map.of())
        ));
        contracts.put("reopenDueContactTasks", operationContract(
                "ReopenDueContactTaskV1",
                parameters("IdempotencyKey"),
                success("200", null, "TaskOccurrenceCommandReceipt",
                        headers("Location", "ReceiptLocation", "ETag", "TaskETagHeader"))
        ));
        return Map.copyOf(contracts);
    }

    private static Map<String, ExampleContract> exampleContracts() {
        Map<String, ExampleContract> examples = new LinkedHashMap<>();
        examples.put("CaptureLeadRequest", example(
                "capture-lead.request.json", "CaptureLeadV1", "captureLead",
                "/paths/~1api~1v1~1leads/post/requestBody/content/application~1json"));
        examples.put("CurrentWorkCardResponse", example(
                "current-work-card.response.json", "CurrentWorkCardEnvelope", "currentWorkCard",
                "/paths/~1api~1v1~1workcards~1current/get/responses/200/content/application~1json"));
        examples.put("SaveActionDraftRequest", example(
                "save-action-draft.request.json", "SaveActionDraftV1", "saveActionDraft",
                "/paths/~1api~1v1~1tasks~1{taskId}~1draft/put/requestBody/content/application~1json"));
        examples.put("ResolveDuplicateLeadRequest", example(
                "resolve-duplicate-lead.request.json", "ResolveDuplicateLeadV1", "resolveDuplicateLead",
                commandRequestMedia("resolve-duplicate-lead")));
        examples.put("CompleteLeadIngressRequest", example(
                "complete-lead-ingress.request.json", "CompleteLeadIngressV1", "completeLeadIngress",
                commandRequestMedia("complete-lead-ingress")));
        examples.put("AssignLeadRequest", example(
                "assign-lead.request.json", "AssignLeadV1", "assignLead",
                commandRequestMedia("assign-lead")));
        examples.put("RecordRoutingDispositionRequest", example(
                "record-routing-disposition.request.json", "RecordRoutingDispositionV1", "recordRoutingDisposition",
                commandRequestMedia("record-routing-disposition")));
        examples.put("AcknowledgeSourceIntakeStopRequestRequest", example(
                "acknowledge-source-intake-stop-request.request.json",
                "AcknowledgeSourceIntakeStopRequestV1",
                "acknowledgeSourceIntakeStopRequest",
                commandRequestMedia("acknowledge-source-intake-stop-request")));
        examples.put("RecordContactResultRequest", example(
                "record-contact-result.request.json", "RecordContactResultV1", "recordContactResult",
                commandRequestMedia("record-contact-result")));
        examples.put("ReviewLeadValidityRequest", example(
                "review-lead-validity.request.json", "ReviewLeadValidityV1", "reviewLeadValidity",
                commandRequestMedia("review-lead-validity")));
        examples.put("CommandReceiptResponse", example(
                "command-receipt.response.json", "CommandReceipt", "receipt",
                "/paths/~1api~1v1~1commands~1{commandId}~1receipt/get/responses/200/content/application~1json"));
        examples.put("ReopenDueContactTasksRequest", example(
                "reopen-due-contact-tasks.request.json", "ReopenDueContactTaskV1", "reopenDueContactTask",
                "/paths/~1internal~1v1~1tasks~1commands~1reopen-due-contact-tasks/post/requestBody/content/application~1json"));
        examples.put("ProblemResponse", example(
                "problem.response.json", "Problem", "staleTask",
                "/components/responses/PreconditionFailedProblem/content/application~1problem+json"));
        return Map.copyOf(examples);
    }

    private static ExampleContract example(
            String fileName,
            String schemaName,
            String exampleKey,
            String mediaPointer
    ) {
        return new ExampleContract(fileName, schemaName, exampleKey, mediaPointer);
    }

    private static String commandRequestMedia(String commandPath) {
        return "/paths/~1api~1v1~1tasks~1{taskId}~1commands~1"
                + commandPath
                + "/post/requestBody/content/application~1json";
    }

    private static OperationContract taskCommandContract(String requestSchema, String responseComponent) {
        return operationContract(
                requestSchema,
                parameters("TaskIdPath", "IdempotencyKey", "TaskIfMatch"),
                success("200", responseComponent, responseSchemaForComponent(responseComponent),
                        headers("Location", "ReceiptLocation"))
        );
    }

    private static String responseSchemaForComponent(String responseComponent) {
        return switch (responseComponent) {
            case "DecisionRecordCommandSucceeded" -> "DecisionRecordCommandReceipt";
            case "LeadCommandSucceeded" -> "LeadCommandReceipt";
            case "LeadAssignmentCommandSucceeded" -> "LeadAssignmentCommandReceipt";
            case "LeadContactResultCommandSucceeded" -> "LeadContactResultCommandReceipt";
            default -> throw new IllegalArgumentException("Unknown success response component: " + responseComponent);
        };
    }

    private static OperationContract operationContract(
            String requestSchema,
            Set<String> parameterRefs,
            SuccessResponse... successResponses
    ) {
        Map<String, SuccessResponse> responses = new LinkedHashMap<>();
        for (SuccessResponse response : successResponses) {
            SuccessResponse previous = responses.put(response.status(), response);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate success status: " + response.status());
            }
        }
        return new OperationContract(requestSchema, parameterRefs, Map.copyOf(responses));
    }

    private static SuccessResponse success(
            String status,
            String responseComponent,
            String responseSchema,
            Map<String, String> headerComponents
    ) {
        return new SuccessResponse(status, responseComponent, responseSchema, headerComponents);
    }

    private static Set<String> parameters(String... componentNames) {
        Set<String> references = new HashSet<>();
        for (String componentName : componentNames) {
            references.add(PARAMETER_REF_PREFIX + componentName);
        }
        return Set.copyOf(references);
    }

    private static Map<String, String> headers(String... nameAndComponentPairs) {
        if (nameAndComponentPairs.length % 2 != 0) {
            throw new IllegalArgumentException("Headers require name/component pairs");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        for (int index = 0; index < nameAndComponentPairs.length; index += 2) {
            headers.put(nameAndComponentPairs[index], nameAndComponentPairs[index + 1]);
        }
        return Map.copyOf(headers);
    }

    private static Map<String, Set<String>> requiredErrorCodes() {
        Map<String, Set<String>> errors = new LinkedHashMap<>();
        errors.put("captureLead", errors("VALIDATION_FAILED,IDEMPOTENCY_KEY_REQUIRED,IDEMPOTENCY_KEY_INVALID,UNAUTHENTICATED,NOT_AUTHORIZED,APPOINTMENT_INACTIVE,COMMAND_PAYLOAD_CONFLICT,SUPERVISOR_UNRESOLVED,RATE_LIMITED,INTERNAL_ERROR,SERVICE_UNAVAILABLE"));
        errors.put("getCurrentWorkCard", errors("UNAUTHENTICATED,NOT_AUTHORIZED,NOT_FOUND,RATE_LIMITED,INTERNAL_ERROR,SERVICE_UNAVAILABLE"));
        errors.put("saveActionDraft", errors("VALIDATION_FAILED,IDEMPOTENCY_KEY_REQUIRED,IDEMPOTENCY_KEY_INVALID,UNAUTHENTICATED,NOT_AUTHORIZED,APPOINTMENT_INACTIVE,NOT_FOUND,COMMAND_PAYLOAD_CONFLICT,TASK_NOT_OPEN,TASK_ALREADY_COMPLETED,DRAFT_DIGEST_MISMATCH,STALE_TASK,STALE_DRAFT,DRAFT_PRECONDITION_REQUIRED,RATE_LIMITED,INTERNAL_ERROR,SERVICE_UNAVAILABLE"));
        errors.put("resolveDuplicateLead", errors("VALIDATION_FAILED,IDEMPOTENCY_KEY_REQUIRED,IDEMPOTENCY_KEY_INVALID,UNAUTHENTICATED,NOT_AUTHORIZED,APPOINTMENT_INACTIVE,NOT_FOUND,COMMAND_PAYLOAD_CONFLICT,TASK_NOT_OPEN,TASK_ALREADY_COMPLETED,DRAFT_DIGEST_MISMATCH,STALE_TASK,STALE_DRAFT,STALE_SUBJECT,SUPERVISOR_UNRESOLVED,TASK_PRECONDITION_REQUIRED,RATE_LIMITED,INTERNAL_ERROR,SERVICE_UNAVAILABLE"));
        errors.put("completeLeadIngress", errors("VALIDATION_FAILED,IDEMPOTENCY_KEY_REQUIRED,IDEMPOTENCY_KEY_INVALID,UNAUTHENTICATED,NOT_AUTHORIZED,APPOINTMENT_INACTIVE,NOT_FOUND,COMMAND_PAYLOAD_CONFLICT,TASK_NOT_OPEN,TASK_ALREADY_COMPLETED,DRAFT_DIGEST_MISMATCH,INGRESS_COMPLETION_ALREADY_RECORDED,STALE_TASK,STALE_DRAFT,STALE_SUBJECT,SUPERVISOR_UNRESOLVED,TASK_PRECONDITION_REQUIRED,RATE_LIMITED,INTERNAL_ERROR,SERVICE_UNAVAILABLE"));
        errors.put("assignLead", errors("VALIDATION_FAILED,IDEMPOTENCY_KEY_REQUIRED,IDEMPOTENCY_KEY_INVALID,UNAUTHENTICATED,NOT_AUTHORIZED,APPOINTMENT_INACTIVE,NOT_FOUND,COMMAND_PAYLOAD_CONFLICT,TASK_NOT_OPEN,TASK_ALREADY_COMPLETED,DRAFT_DIGEST_MISMATCH,STALE_TASK,STALE_DRAFT,STALE_SUBJECT,TASK_PRECONDITION_REQUIRED,RATE_LIMITED,INTERNAL_ERROR,SERVICE_UNAVAILABLE"));
        errors.put("recordRoutingDisposition", errors("VALIDATION_FAILED,IDEMPOTENCY_KEY_REQUIRED,IDEMPOTENCY_KEY_INVALID,UNAUTHENTICATED,NOT_AUTHORIZED,APPOINTMENT_INACTIVE,NOT_FOUND,COMMAND_PAYLOAD_CONFLICT,TASK_NOT_OPEN,TASK_ALREADY_COMPLETED,DRAFT_DIGEST_MISMATCH,STALE_TASK,STALE_DRAFT,STALE_SUBJECT,SOURCE_INTAKE_OWNER_UNRESOLVED,TASK_PRECONDITION_REQUIRED,RATE_LIMITED,INTERNAL_ERROR,SERVICE_UNAVAILABLE"));
        errors.put("acknowledgeSourceIntakeStopRequest", errors("VALIDATION_FAILED,IDEMPOTENCY_KEY_REQUIRED,IDEMPOTENCY_KEY_INVALID,UNAUTHENTICATED,NOT_AUTHORIZED,APPOINTMENT_INACTIVE,NOT_FOUND,COMMAND_PAYLOAD_CONFLICT,TASK_NOT_OPEN,TASK_ALREADY_COMPLETED,DRAFT_DIGEST_MISMATCH,STALE_TASK,STALE_DRAFT,STALE_SUBJECT,TASK_PRECONDITION_REQUIRED,RATE_LIMITED,INTERNAL_ERROR,SERVICE_UNAVAILABLE"));
        errors.put("recordContactResult", errors("VALIDATION_FAILED,IDEMPOTENCY_KEY_REQUIRED,IDEMPOTENCY_KEY_INVALID,UNAUTHENTICATED,NOT_AUTHORIZED,APPOINTMENT_INACTIVE,NOT_FOUND,COMMAND_PAYLOAD_CONFLICT,TASK_NOT_OPEN,TASK_ALREADY_COMPLETED,DRAFT_DIGEST_MISMATCH,STALE_TASK,STALE_DRAFT,STALE_SUBJECT,SUPERVISOR_UNRESOLVED,TASK_PRECONDITION_REQUIRED,RATE_LIMITED,INTERNAL_ERROR,SERVICE_UNAVAILABLE"));
        errors.put("reviewLeadValidity", errors("VALIDATION_FAILED,IDEMPOTENCY_KEY_REQUIRED,IDEMPOTENCY_KEY_INVALID,UNAUTHENTICATED,NOT_AUTHORIZED,APPOINTMENT_INACTIVE,NOT_FOUND,COMMAND_PAYLOAD_CONFLICT,TASK_NOT_OPEN,TASK_ALREADY_COMPLETED,DRAFT_DIGEST_MISMATCH,STALE_TASK,STALE_DRAFT,STALE_SUBJECT,TASK_PRECONDITION_REQUIRED,RATE_LIMITED,INTERNAL_ERROR,SERVICE_UNAVAILABLE"));
        errors.put("getCommandReceipt", errors("UNAUTHENTICATED,NOT_AUTHORIZED,NOT_FOUND,RATE_LIMITED,INTERNAL_ERROR,SERVICE_UNAVAILABLE"));
        errors.put("reopenDueContactTasks", errors("VALIDATION_FAILED,IDEMPOTENCY_KEY_REQUIRED,IDEMPOTENCY_KEY_INVALID,UNAUTHENTICATED,NOT_AUTHORIZED,COMMAND_PAYLOAD_CONFLICT,RATE_LIMITED,INTERNAL_ERROR,SERVICE_UNAVAILABLE"));
        return Map.copyOf(errors);
    }

    private static Map<String, ErrorRule> errorRules() {
        Map<String, ErrorRule> rules = new LinkedHashMap<>();
        rules.put("VALIDATION_FAILED", errorRule(400, "SAME_KEY_AFTER_FIX", null, false, true, false));
        rules.put("IDEMPOTENCY_KEY_REQUIRED", errorRule(400, "SAME_KEY_AFTER_FIX", null, false, false, false));
        rules.put("IDEMPOTENCY_KEY_INVALID", errorRule(400, "SAME_KEY_AFTER_FIX", null, false, false, false));
        rules.put("UNAUTHENTICATED", errorRule(401, "SAME_KEY_AFTER_REAUTH", null, false, false, false));
        rules.put("NOT_AUTHORIZED", errorRule(403, "NO", null, false, false, false));
        rules.put("APPOINTMENT_INACTIVE", errorRule(403, "NO", null, false, false, false));
        rules.put("NOT_FOUND", errorRule(404, "NO", null, false, false, false));
        rules.put("COMMAND_PAYLOAD_CONFLICT", errorRule(409, "NO", null, false, false, true));
        rules.put("TASK_NOT_OPEN", errorRule(409, "NO", "TASK", true, false, false));
        rules.put("TASK_ALREADY_COMPLETED", errorRule(409, "NO", "TASK", true, false, false));
        rules.put("DRAFT_DIGEST_MISMATCH", errorRule(409, "NEW_KEY_AFTER_REFRESH", "DRAFT", true, false, false));
        rules.put("INGRESS_COMPLETION_ALREADY_RECORDED", errorRule(409, "NO", "SUBJECT", true, false, false));
        rules.put("STALE_TASK", errorRule(412, "NEW_KEY_AFTER_REFRESH", "TASK", true, false, false));
        rules.put("STALE_DRAFT", errorRule(412, "NEW_KEY_AFTER_REFRESH", "DRAFT", true, false, false));
        rules.put("STALE_SUBJECT", errorRule(412, "NEW_KEY_AFTER_REFRESH", "SUBJECT", true, false, false));
        rules.put("SUPERVISOR_UNRESOLVED", errorRule(422, "NEW_KEY_AFTER_ADMIN_FIX", null, false, false, false));
        rules.put("SOURCE_INTAKE_OWNER_UNRESOLVED",
                errorRule(422, "NEW_KEY_AFTER_ADMIN_FIX", null, false, false, false));
        rules.put("DRAFT_PRECONDITION_REQUIRED",
                errorRule(428, "SAME_KEY_AFTER_FIX", "DRAFT", false, false, false));
        rules.put("TASK_PRECONDITION_REQUIRED", errorRule(428, "SAME_KEY_AFTER_FIX", "TASK", true, false, false));
        rules.put("RATE_LIMITED", errorRule(429, "SAME_KEY_AFTER_BACKOFF", null, false, false, false));
        rules.put("INTERNAL_ERROR", errorRule(500, "SAME_KEY_AFTER_BACKOFF", null, false, false, false));
        rules.put("SERVICE_UNAVAILABLE", errorRule(503, "SAME_KEY_AFTER_BACKOFF", null, false, false, false));
        return Map.copyOf(rules);
    }

    private static ErrorRule errorRule(
            int status,
            String retryPolicy,
            String currentEtagKind,
            boolean currentEtagRequired,
            boolean fieldErrorsRequired,
            boolean receiptRefRequired
    ) {
        return new ErrorRule(
                status,
                retryPolicy,
                currentEtagKind,
                currentEtagRequired,
                fieldErrorsRequired,
                receiptRefRequired
        );
    }

    private static Map<String, Set<String>> expectedErrorsByStatus(String operationId) {
        Map<String, Set<String>> mutable = new LinkedHashMap<>();
        for (String code : ERROR_CODES_BY_OPERATION.get(operationId)) {
            ErrorRule rule = ERROR_RULES.get(code);
            if (rule == null) {
                throw new IllegalStateException("Missing error rule for " + code);
            }
            String status = Integer.toString(rule.status());
            Set<String> existing = mutable.getOrDefault(status, Set.of());
            Set<String> combined = new HashSet<>(existing);
            combined.add(code);
            mutable.put(status, Set.copyOf(combined));
        }
        return Map.copyOf(mutable);
    }

    private static String errorResponseComponent(String status, boolean internalOperation) {
        return switch (status) {
            case "400" -> "BadRequestProblem";
            case "401" -> internalOperation ? "InternalUnauthorizedProblem" : "PublicUnauthorizedProblem";
            case "403" -> "ForbiddenProblem";
            case "404" -> "NotFoundProblem";
            case "409" -> "ConflictProblem";
            case "412" -> "PreconditionFailedProblem";
            case "422" -> "UnprocessableProblem";
            case "428" -> "PreconditionRequiredProblem";
            case "429" -> "RateLimitedProblem";
            case "500" -> "InternalProblem";
            case "503" -> "UnavailableProblem";
            default -> throw new IllegalArgumentException("No frozen error response for HTTP " + status);
        };
    }

    private static Map<String, String> expectedErrorHeaders(String status, boolean internalOperation) {
        if (status.equals("401") && !internalOperation) {
            return headers("WWW-Authenticate", "PublicBearerChallenge");
        }
        if (status.equals("429") || status.equals("503")) {
            return headers("Retry-After", "RetryAfter");
        }
        return Map.of();
    }

    private static Set<String> errors(String commaSeparated) {
        return Set.of(commaSeparated.split(","));
    }

    private static OperationKey operationKey(String operationId) {
        return REQUIRED_OPERATIONS.entrySet().stream()
                .filter(entry -> entry.getValue().equals(operationId))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow();
    }

    private static JsonNode operationNode(OperationKey key) {
        return document.path("paths").path(key.path()).path(key.method().toLowerCase(Locale.ROOT));
    }

    private static JsonNode requiredParameter(OperationKey key, String location, String name) {
        List<JsonNode> matches = parameters(key).stream()
                .map(OpenApiContractTest::dereference)
                .filter(parameter -> location.equals(parameter.path("in").asText()))
                .filter(parameter -> name.equalsIgnoreCase(parameter.path("name").asText()))
                .toList();
        assertEquals(1, matches.size(), () -> REQUIRED_OPERATIONS.get(key) + " must declare exactly one " + name);
        return matches.get(0);
    }

    private static void assertParameterAbsent(OperationKey key, String name) {
        boolean present = parameters(key).stream()
                .map(OpenApiContractTest::dereference)
                .anyMatch(parameter -> name.equalsIgnoreCase(parameter.path("name").asText()));
        assertFalse(present, () -> REQUIRED_OPERATIONS.get(key) + " must not declare " + name);
    }

    private static void assertPathUuidParameter(String componentName, String wireName) {
        JsonNode parameter = document.path("components").path("parameters").path(componentName);
        assertFalse(parameter.isMissingNode(), "Missing parameter component " + componentName);
        assertEquals(Set.of("name", "in", "required", "schema"), fieldNames(parameter),
                componentName + " exact fields");
        assertEquals(wireName, parameter.path("name").asText(), componentName + " wire name");
        assertEquals("path", parameter.path("in").asText(), componentName + " location");
        assertTrue(parameter.path("required").asBoolean(), componentName + " must be required");
        assertEquals(schemaRef("Uuid"), parameter.path("schema").path("$ref").asText(),
                componentName + " UUID schema");
    }

    private static List<JsonNode> parameters(OperationKey key) {
        List<JsonNode> parameters = new ArrayList<>();
        rawParameters(key).stream().map(OpenApiContractTest::dereference).forEach(parameters::add);
        return parameters;
    }

    private static List<JsonNode> rawParameters(OperationKey key) {
        List<JsonNode> parameters = new ArrayList<>();
        JsonNode pathNode = document.path("paths").path(key.path());
        pathNode.path("parameters").forEach(parameters::add);
        operationNode(key).path("parameters").forEach(parameters::add);
        return parameters;
    }

    private static boolean isSuccessStatus(String status) {
        return status.startsWith("2") || status.equals("304");
    }

    private static JsonNode schema(String name) {
        JsonNode schema = document.path("components").path("schemas").path(name);
        assertFalse(schema.isMissingNode(), () -> "Missing component schema: " + name);
        return dereference(schema);
    }

    private static JsonNode dereference(JsonNode node) {
        JsonNode current = node;
        Set<String> visited = new HashSet<>();
        while (current != null && current.isObject() && current.hasNonNull("$ref")) {
            String reference = current.path("$ref").asText();
            assertTrue(reference.startsWith("#/"), () -> "Only local reusable component refs are supported here: " + reference);
            assertTrue(visited.add(reference), () -> "Circular OpenAPI ref: " + reference);
            current = document.at(reference.substring(1));
            assertFalse(current.isMissingNode(), () -> "Unresolved OpenAPI ref: " + reference);
        }
        return current;
    }

    private static Set<String> fieldNames(JsonNode object) {
        Set<String> names = new HashSet<>();
        if (object != null && object.isObject()) {
            object.fieldNames().forEachRemaining(names::add);
        }
        return names;
    }

    private static Set<String> stringSet(JsonNode array) {
        Set<String> values = new HashSet<>();
        if (array != null && array.isArray()) {
            array.forEach(value -> values.add(value.asText()));
        }
        return values;
    }

    private static String schemaRef(String componentName) {
        return SCHEMA_REF_PREFIX + componentName;
    }

    private static String responseRef(String componentName) {
        return RESPONSE_REF_PREFIX + componentName;
    }

    private static ActionVariant actionVariant(String actionCode) {
        return ACTION_VARIANTS.stream()
                .filter(variant -> variant.actionCode().equals(actionCode))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing frozen action variant: " + actionCode));
    }

    private static void assertClosedComponent(String componentName) {
        JsonNode component = schema(componentName);
        assertEquals("object", component.path("type").asText(), componentName + " type");
        assertTrue(component.path("properties").isObject(), componentName + " properties must be direct");
        assertTrue(component.path("additionalProperties").isBoolean(),
                componentName + " must declare additionalProperties");
        assertFalse(component.path("additionalProperties").asBoolean(), componentName + " must be closed");
    }

    private static void assertClosedObjectShape(
            JsonNode objectSchema,
            Set<String> expectedProperties,
            Set<String> expectedRequired,
            String context
    ) {
        assertEquals("object", objectSchema.path("type").asText(), context + " type");
        assertEquals(expectedProperties, fieldNames(objectSchema.path("properties")), context + " direct properties");
        assertEquals(expectedRequired, stringSet(objectSchema.path("required")), context + " required fields");
        assertTrue(objectSchema.path("additionalProperties").isBoolean(),
                context + " must declare additionalProperties");
        assertFalse(objectSchema.path("additionalProperties").asBoolean(), context + " must be closed");
    }

    private static void assertPropertyRef(
            JsonNode objectSchema,
            String propertyName,
            String componentName,
            String context
    ) {
        assertEquals(
                schemaRef(componentName),
                objectSchema.path("properties").path(propertyName).path("$ref").asText(),
                context + " " + propertyName
        );
    }

    private static void assertActionBinding(
            String componentName,
            String actionCode,
            String valuesComponentName,
            Set<String> expectedProperties
    ) {
        JsonNode binding = schema(componentName);
        assertClosedObjectShape(binding, expectedProperties, expectedProperties, componentName);

        JsonNode actionCodeSchema = binding.path("properties").path("actionCode");
        assertEquals(schemaRef("ActionCode"), actionCodeSchema.path("$ref").asText(),
                componentName + " actionCode must reuse ActionCode");
        assertSchemaAcceptsOnlyLiteral(actionCodeSchema, actionCode);
        assertEquals(
                schemaRef("SchemaVersionV1"),
                binding.path("properties").path("schemaVersion").path("$ref").asText(),
                componentName + " schemaVersion"
        );
        assertEquals(
                schemaRef(valuesComponentName),
                binding.path("properties").path("values").path("$ref").asText(),
                componentName + " values binding"
        );
    }

    private static void assertExactOneOfRefs(JsonNode union, Set<String> expectedRefs, String context) {
        JsonNode oneOf = union.path("oneOf");
        assertTrue(oneOf.isArray(), context + " must declare oneOf directly");
        List<String> references = new ArrayList<>();
        oneOf.forEach(branch -> {
            assertTrue(branch.hasNonNull("$ref"), context + " branches must be named component refs");
            references.add(branch.path("$ref").asText());
        });
        assertEquals(expectedRefs.size(), references.size(), context + " branch count");
        assertEquals(expectedRefs.size(), new HashSet<>(references).size(), context + " branches must be unique");
        assertEquals(expectedRefs, new HashSet<>(references), context + " exact branch refs");
    }

    private static void assertNullableOneOfRef(JsonNode union, String expectedRef, String context) {
        JsonNode oneOf = union.path("oneOf");
        assertTrue(oneOf.isArray(), context + " must use an OpenAPI 3.1 oneOf null union");
        assertEquals(2, oneOf.size(), context + " must contain exactly the concrete type and null");

        int concreteBranches = 0;
        int nullBranches = 0;
        for (JsonNode branch : oneOf) {
            if (expectedRef.equals(branch.path("$ref").asText())) {
                concreteBranches++;
            }
            if ("null".equals(branch.path("type").asText())) {
                nullBranches++;
            }
        }
        assertEquals(1, concreteBranches, context + " concrete branch");
        assertEquals(1, nullBranches, context + " null branch");
        assertFalse(union.has("nullable"), context + " must not use the ignored OpenAPI 3.0 nullable keyword");
    }

    private static void assertDiscriminatorAndBranches(
            JsonNode union,
            String propertyName,
            Map<String, String> expectedMapping,
            Set<String> expectedRefs,
            String context
    ) {
        JsonNode discriminator = union.path("discriminator");
        assertEquals(
                propertyName,
                discriminator.path("propertyName").asText(),
                context + " discriminator property"
        );
        assertEquals(expectedMapping, stringMap(discriminator.path("mapping")), context + " discriminator mapping");
        assertExactOneOfRefs(union, expectedRefs, context);
    }

    private static Map<String, String> stringMap(JsonNode object) {
        Map<String, String> values = new LinkedHashMap<>();
        object.fields().forEachRemaining(entry -> values.put(entry.getKey(), entry.getValue().asText()));
        return values;
    }

    private static JsonNode findResultCodeConditional(JsonNode node) {
        JsonNode resolved = dereference(node);
        if (resolved.isObject()) {
            JsonNode resultCode = resolved.path("if").path("properties").path("resultCode");
            boolean connectedValid = "CONNECTED_VALID".equals(resultCode.path("const").asText())
                    || (resultCode.path("enum").size() == 1
                    && "CONNECTED_VALID".equals(resultCode.path("enum").get(0).asText()));
            if (connectedValid && resolved.has("then") && resolved.has("else")) {
                return resolved;
            }
            for (JsonNode child : resolved) {
                JsonNode found = findResultCodeConditional(child);
                if (found != null) {
                    return found;
                }
            }
        } else if (resolved.isArray()) {
            for (JsonNode child : resolved) {
                JsonNode found = findResultCodeConditional(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void collectForbiddenNames(JsonNode node, String location, List<String> violations) {
        if (node.isObject()) {
            JsonNode properties = node.path("properties");
            if (properties.isObject()) {
                properties.fieldNames().forEachRemaining(name -> {
                    if (FORBIDDEN_PUBLIC_NAMES.contains(name.toLowerCase(Locale.ROOT))) {
                        violations.add(location + "/properties/" + name);
                    }
                });
            }
            if ("parameter".equals(node.path("in").asText()) || node.has("in")) {
                String name = node.path("name").asText().toLowerCase(Locale.ROOT);
                if (FORBIDDEN_PUBLIC_NAMES.contains(name)) {
                    violations.add(location + "/name=" + name);
                }
            }
            node.fields().forEachRemaining(entry ->
                    collectForbiddenNames(entry.getValue(), location + "/" + entry.getKey(), violations));
        } else if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                collectForbiddenNames(node.get(index), location + "/" + index, violations);
            }
        }
    }

    private static void assertResponseEtag(String operationId, String status, String kind) {
        OperationKey key = operationKey(operationId);
        JsonNode header = operationNode(key).path("responses").path(status).path("headers").path("ETag");
        assertFalse(header.isMissingNode(), () -> operationId + " " + status + " must return ETag");
        header = dereference(header);
        assertStrongEtagSchema(dereference(header.path("schema")), kind, operationId + " " + status + " ETag");
    }

    private static void assertStrongEtagSchema(JsonNode schema, String kind, String context) {
        String regex = schema.path("pattern").asText();
        assertFalse(regex.isBlank(), () -> context + " requires a schema pattern");
        Pattern pattern = Pattern.compile(regex);
        String digest = "A".repeat(43);
        String sample = "\"" + kind + "." + digest + "\"";
        assertTrue(pattern.matcher(sample).matches(), () -> context + " must accept " + sample);
        assertFalse(pattern.matcher("W/" + sample).matches(), () -> context + " must reject weak ETags");
        assertFalse(pattern.matcher(kind + "." + digest).matches(), () -> context + " must require quotes");
        assertFalse(pattern.matcher("\"" + kind + "." + "A".repeat(42) + "\"").matches(),
                () -> context + " must require exactly 43 digest characters");
        String otherKind = kind.equals("task") ? "draft" : "task";
        assertFalse(pattern.matcher("\"" + otherKind + "." + digest + "\"").matches(),
                () -> context + " must be resource-kind specific");
    }

    private static void assertSchemaAcceptsOnlyLiteral(JsonNode schema, String literal) {
        if (schema.path("const").isTextual()) {
            assertEquals(literal, schema.path("const").asText());
            return;
        }
        if (schema.path("enum").isArray()) {
            assertEquals(Set.of(literal), stringSet(schema.path("enum")));
            return;
        }
        String regex = schema.path("pattern").asText();
        assertFalse(regex.isBlank(), "Literal header schema must use const, singleton enum, or a closed pattern");
        Pattern pattern = Pattern.compile(regex);
        assertTrue(pattern.matcher(literal).matches());
        assertFalse(pattern.matcher(literal + literal).matches());
        assertFalse(pattern.matcher("").matches());
    }

    private static void assertBearerChallengeSchema(JsonNode schema) {
        if (schema.path("const").isTextual() || schema.path("enum").isArray()) {
            assertSchemaAcceptsOnlyLiteral(schema, "Bearer");
            return;
        }
        String regex = schema.path("pattern").asText();
        assertFalse(regex.isBlank(), "Bearer challenge requires const, singleton enum, or pattern");
        Pattern pattern = Pattern.compile(regex);
        assertTrue(pattern.matcher("Bearer").matches(), "Bearer challenge schema must accept Bearer");
        assertFalse(pattern.matcher("Basic").matches(), "Bearer challenge schema must reject Basic");
    }

    private static Map<String, Object> validProblem(String code, ErrorRule rule) {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", "https://api.ontology-law.example/problems/" + code.toLowerCase(Locale.ROOT));
        problem.put("title", "Safe public error");
        problem.put("status", rule.status());
        problem.put("code", code);
        problem.put("detail", "Safe public detail");
        problem.put("instance", "/problems/occurrences/01J6YYC7Q8M4J5TPNX2ZK7J1AR");
        problem.put("retryPolicy", rule.retryPolicy());
        if (rule.fieldErrorsRequired()) {
            problem.put("fieldErrors", validFieldErrors());
        }
        if (rule.currentEtagKind() != null) {
            problem.put("currentETag", currentEtag(rule.currentEtagKind()));
        }
        if (rule.receiptRefRequired()) {
            problem.put("receiptRef", receiptRef());
        }
        return problem;
    }

    private static List<Map<String, Object>> validFieldErrors() {
        return List.of(Map.of(
                "pointer", "/values/legalNeed",
                "code", "REQUIRED",
                "detail", "This field is required"
        ));
    }

    private static Map<String, Object> currentEtag(String resourceKind) {
        String prefix = switch (resourceKind) {
            case "WORKBENCH" -> "wb";
            case "TASK" -> "task";
            case "DRAFT" -> "draft";
            case "SUBJECT" -> "subject";
            default -> throw new IllegalArgumentException("Unknown resource kind: " + resourceKind);
        };
        return Map.of(
                "resourceKind", resourceKind,
                "value", "\"" + prefix + "." + "A".repeat(43) + "\""
        );
    }

    private static String otherResourceKind(String resourceKind) {
        return resourceKind.equals("TASK") ? "DRAFT" : "TASK";
    }

    private static Map<String, Object> receiptRef() {
        String commandId = "01955c2a-7b14-7a12-8f45-4f6e8c9a1013";
        return Map.of(
                "commandId", commandId,
                "href", "/api/v1/commands/" + commandId + "/receipt"
        );
    }

    private static Map<String, Object> copy(Map<String, Object> source) {
        return new LinkedHashMap<>(source);
    }

    private static void collectExampleRefLocations(
            JsonNode node,
            String pointer,
            Map<String, List<String>> locations
    ) {
        if (node.isObject()) {
            if (node.path("$ref").asText().startsWith("#/components/examples/")) {
                String reference = node.path("$ref").asText();
                locations.computeIfAbsent(reference, ignored -> new ArrayList<>()).add(pointer + "/$ref");
            }
            node.fields().forEachRemaining(entry -> collectExampleRefLocations(
                    entry.getValue(),
                    pointer + "/" + jsonPointerToken(entry.getKey()),
                    locations
            ));
        } else if (node.isArray()) {
            int index = 0;
            for (JsonNode child : node) {
                collectExampleRefLocations(child, pointer + "/" + index, locations);
                index++;
            }
        }
    }

    private static String jsonPointerToken(String token) {
        return token.replace("~", "~0").replace("/", "~1");
    }

    private static void assertSchemaAccepts(Object instance, JsonNode schema, String context) {
        List<String> violations = schemaViolations(JsonNode.wrap(instance), schema, "$", new HashSet<>());
        assertEquals(List.of(), violations, () -> context + " must be accepted: " + violations);
    }

    private static void assertSchemaRejects(Object instance, JsonNode schema, String context) {
        List<String> violations = schemaViolations(JsonNode.wrap(instance), schema, "$", new HashSet<>());
        assertFalse(violations.isEmpty(), () -> context + " must be rejected");
    }

    private static List<String> schemaViolations(
            JsonNode instance,
            JsonNode schema,
            String instancePath,
            Set<String> referenceStack
    ) {
        List<String> violations = new ArrayList<>();
        if (schema == null || schema.isMissingNode()) {
            violations.add(instancePath + ": missing schema");
            return violations;
        }
        if (schema.isBoolean()) {
            if (!schema.asBoolean()) {
                violations.add(instancePath + ": rejected by false schema");
            }
            return violations;
        }

        if (schema.hasNonNull("$ref")) {
            String reference = schema.path("$ref").asText();
            if (!reference.startsWith("#/")) {
                violations.add(instancePath + ": unsupported external schema ref " + reference);
                return violations;
            }
            if (!referenceStack.add(reference)) {
                violations.add(instancePath + ": circular schema ref " + reference);
                return violations;
            }
            JsonNode target = document.at(reference.substring(1));
            violations.addAll(schemaViolations(instance, target, instancePath, referenceStack));
            referenceStack.remove(reference);
        }

        for (JsonNode member : schema.path("allOf")) {
            violations.addAll(schemaViolations(instance, member, instancePath, new HashSet<>(referenceStack)));
        }
        if (schema.path("anyOf").isArray()) {
            int matches = matchingBranches(instance, schema.path("anyOf"), instancePath, referenceStack);
            if (matches == 0) {
                violations.add(instancePath + ": matched no anyOf branch");
            }
        }
        if (schema.path("oneOf").isArray()) {
            int matches = matchingBranches(instance, schema.path("oneOf"), instancePath, referenceStack);
            if (matches != 1) {
                violations.add(instancePath + ": matched " + matches + " oneOf branches");
            }
        }
        if (schema.has("not")
                && schemaViolations(instance, schema.path("not"), instancePath, new HashSet<>(referenceStack)).isEmpty()) {
            violations.add(instancePath + ": matched forbidden schema");
        }
        if (schema.has("if")) {
            boolean conditionMatches = schemaViolations(
                    instance,
                    schema.path("if"),
                    instancePath,
                    new HashSet<>(referenceStack)
            ).isEmpty();
            String branch = conditionMatches ? "then" : "else";
            if (schema.has(branch)) {
                violations.addAll(schemaViolations(
                        instance,
                        schema.path(branch),
                        instancePath,
                        new HashSet<>(referenceStack)
                ));
            }
        }

        if (schema.has("const") && !Objects.equals(instance.value, schema.path("const").value)) {
            violations.add(instancePath + ": value differs from const");
        }
        if (schema.path("enum").isArray()) {
            boolean enumMatch = false;
            for (JsonNode candidate : schema.path("enum")) {
                enumMatch |= Objects.equals(instance.value, candidate.value);
            }
            if (!enumMatch) {
                violations.add(instancePath + ": value is outside enum");
            }
        }

        JsonNode type = schema.path("type");
        if (!type.isMissingNode() && !matchesDeclaredType(instance, type)) {
            violations.add(instancePath + ": value has the wrong JSON type");
            return violations;
        }

        if (instance.isObject()) {
            JsonNode properties = schema.path("properties");
            for (String required : stringSet(schema.path("required"))) {
                if (!instance.has(required)) {
                    violations.add(instancePath + ": missing required property " + required);
                }
            }
            if (schema.path("additionalProperties").isBoolean()
                    && !schema.path("additionalProperties").asBoolean()) {
                Set<String> extras = fieldNames(instance);
                extras.removeAll(fieldNames(properties));
                for (String extra : extras) {
                    violations.add(instancePath + ": undeclared property " + extra);
                }
            }
            properties.fields().forEachRemaining(property -> {
                if (instance.has(property.getKey())) {
                    violations.addAll(schemaViolations(
                            instance.path(property.getKey()),
                            property.getValue(),
                            instancePath + "/" + property.getKey(),
                            new HashSet<>(referenceStack)
                    ));
                }
            });
        }

        if (instance.isArray()) {
            int size = instance.size();
            if (schema.path("minItems").isNumber() && size < schema.path("minItems").asLong()) {
                violations.add(instancePath + ": array is shorter than minItems");
            }
            if (schema.path("maxItems").isNumber() && size > schema.path("maxItems").asLong()) {
                violations.add(instancePath + ": array is longer than maxItems");
            }
            if (schema.path("uniqueItems").asBoolean()) {
                Set<Object> unique = new HashSet<>();
                for (JsonNode item : instance) {
                    unique.add(item.value);
                }
                if (unique.size() != size) {
                    violations.add(instancePath + ": array items are not unique");
                }
            }
            if (schema.has("items")) {
                int index = 0;
                for (JsonNode item : instance) {
                    violations.addAll(schemaViolations(
                            item,
                            schema.path("items"),
                            instancePath + "/" + index,
                            new HashSet<>(referenceStack)
                    ));
                    index++;
                }
            }
        }

        if (instance.isTextual()) {
            String value = instance.asText();
            int length = value.codePointCount(0, value.length());
            if (schema.path("minLength").isNumber() && length < schema.path("minLength").asLong()) {
                violations.add(instancePath + ": string is shorter than minLength");
            }
            if (schema.path("maxLength").isNumber() && length > schema.path("maxLength").asLong()) {
                violations.add(instancePath + ": string is longer than maxLength");
            }
            if (schema.path("pattern").isTextual()
                    && !Pattern.compile(schema.path("pattern").asText()).matcher(value).find()) {
                violations.add(instancePath + ": string does not match pattern");
            }
            if (schema.path("format").isTextual() && !matchesFormat(value, schema.path("format").asText())) {
                violations.add(instancePath + ": string does not match format " + schema.path("format").asText());
            }
        }

        if (instance.isNumber()) {
            double value = instance.asDouble();
            if (schema.path("minimum").isNumber() && value < schema.path("minimum").asDouble()) {
                violations.add(instancePath + ": number is below minimum");
            }
            if (schema.path("maximum").isNumber() && value > schema.path("maximum").asDouble()) {
                violations.add(instancePath + ": number is above maximum");
            }
        }
        return violations;
    }

    private static int matchingBranches(
            JsonNode instance,
            JsonNode branches,
            String instancePath,
            Set<String> referenceStack
    ) {
        int matches = 0;
        for (JsonNode branch : branches) {
            if (schemaViolations(instance, branch, instancePath, new HashSet<>(referenceStack)).isEmpty()) {
                matches++;
            }
        }
        return matches;
    }

    private static boolean matchesDeclaredType(JsonNode instance, JsonNode type) {
        if (type.isArray()) {
            for (JsonNode candidate : type) {
                if (matchesType(instance, candidate.asText())) {
                    return true;
                }
            }
            return false;
        }
        return matchesType(instance, type.asText());
    }

    private static boolean matchesType(JsonNode instance, String type) {
        return switch (type) {
            case "null" -> instance.isNull();
            case "object" -> instance.isObject();
            case "array" -> instance.isArray();
            case "string" -> instance.isTextual();
            case "integer" -> instance.isInteger();
            case "number" -> instance.isNumber();
            case "boolean" -> instance.isBoolean();
            default -> false;
        };
    }

    private static boolean matchesFormat(String value, String format) {
        try {
            return switch (format) {
                case "uuid" -> value.length() == 36 && UUID.fromString(value).toString().equalsIgnoreCase(value);
                case "date-time" -> {
                    OffsetDateTime.parse(value);
                    yield true;
                }
                case "email" -> {
                    int separator = value.indexOf('@');
                    yield separator > 0 && separator == value.lastIndexOf('@') && separator < value.length() - 1;
                }
                case "uri" -> new URI(value).isAbsolute();
                case "uri-reference" -> {
                    new URI(value);
                    yield true;
                }
                case "json-pointer" -> value.matches("(?:/(?:[^~/]|~[01])*)+");
                default -> true;
            };
        } catch (IllegalArgumentException | DateTimeParseException | URISyntaxException ignored) {
            return false;
        }
    }

    /**
     * Minimal raw tree facade over SnakeYAML values. The semantic parser validates the
     * document as OpenAPI, while this tree preserves exact 3.1 keywords for mechanical
     * contract assertions without coupling those assertions to parser model coercions.
     */
    private static final class JsonNode implements Iterable<JsonNode> {
        private static final Object MISSING = new Object();
        private static final JsonNode MISSING_NODE = new JsonNode(MISSING);

        private final Object value;

        private JsonNode(Object value) {
            this.value = value;
        }

        static JsonNode wrap(Object value) {
            return value == MISSING ? MISSING_NODE : new JsonNode(value);
        }

        JsonNode path(String name) {
            if (!(value instanceof Map<?, ?> map) || !map.containsKey(name)) {
                return MISSING_NODE;
            }
            return wrap(map.get(name));
        }

        JsonNode at(String pointer) {
            if (pointer == null || pointer.isEmpty()) {
                return this;
            }
            if (!pointer.startsWith("/")) {
                return MISSING_NODE;
            }
            JsonNode current = this;
            for (String rawToken : pointer.substring(1).split("/", -1)) {
                String token = rawToken.replace("~1", "/").replace("~0", "~");
                if (current.isArray()) {
                    try {
                        current = current.get(Integer.parseInt(token));
                    } catch (NumberFormatException ignored) {
                        return MISSING_NODE;
                    }
                } else {
                    current = current.path(token);
                }
                if (current.isMissingNode()) {
                    return current;
                }
            }
            return current;
        }

        JsonNode get(String name) {
            return path(name);
        }

        JsonNode get(int index) {
            if (value instanceof List<?> list && index >= 0 && index < list.size()) {
                return wrap(list.get(index));
            }
            return MISSING_NODE;
        }

        boolean has(String name) {
            return value instanceof Map<?, ?> map && map.containsKey(name);
        }

        boolean hasNonNull(String name) {
            return value instanceof Map<?, ?> map && map.containsKey(name) && map.get(name) != null;
        }

        boolean isObject() {
            return value instanceof Map<?, ?>;
        }

        boolean isArray() {
            return value instanceof List<?>;
        }

        boolean isBoolean() {
            return value instanceof Boolean;
        }

        boolean isNull() {
            return value == null;
        }

        boolean isNumber() {
            return value instanceof Number;
        }

        boolean isInteger() {
            return value instanceof Byte
                    || value instanceof Short
                    || value instanceof Integer
                    || value instanceof Long
                    || value instanceof java.math.BigInteger;
        }

        boolean isTextual() {
            return value instanceof String;
        }

        boolean isMissingNode() {
            return value == MISSING;
        }

        int size() {
            if (value instanceof Map<?, ?> map) {
                return map.size();
            }
            if (value instanceof List<?> list) {
                return list.size();
            }
            return 0;
        }

        String asText() {
            return value == null || value == MISSING ? "" : String.valueOf(value);
        }

        boolean asBoolean() {
            return value instanceof Boolean flag ? flag : Boolean.parseBoolean(asText());
        }

        long asLong() {
            return value instanceof Number number ? number.longValue() : 0L;
        }

        double asDouble() {
            return value instanceof Number number ? number.doubleValue() : Double.NaN;
        }

        Iterator<String> fieldNames() {
            if (!(value instanceof Map<?, ?> map)) {
                return List.<String>of().iterator();
            }
            return map.keySet().stream().map(String::valueOf).iterator();
        }

        Iterator<Map.Entry<String, JsonNode>> fields() {
            if (!(value instanceof Map<?, ?> map)) {
                return List.<Map.Entry<String, JsonNode>>of().iterator();
            }
            return map.entrySet().stream()
                    .map(entry -> Map.entry(String.valueOf(entry.getKey()), wrap(entry.getValue())))
                    .iterator();
        }

        @Override
        public Iterator<JsonNode> iterator() {
            if (value instanceof List<?> list) {
                return list.stream().map(JsonNode::wrap).iterator();
            }
            if (value instanceof Map<?, ?> map) {
                return map.values().stream().map(JsonNode::wrap).iterator();
            }
            return List.<JsonNode>of().iterator();
        }

        @Override
        public String toString() {
            return String.valueOf(value);
        }
    }

    private record OperationKey(String method, String path) {
        private OperationKey {
            method = method.toUpperCase(Locale.ROOT);
        }
    }

    private record ActionVariant(
            String actionCode,
            String fullValuesSchema,
            String partialValuesSchema,
            String saveBindingSchema,
            String commandFormSchema,
            String actionDraftSchema
    ) {}

    private record CurrentCardVariant(String taskType, String currentCardSchema, String actionCode) {}

    private record OperationContract(
            String requestSchema,
            Set<String> parameterRefs,
            Map<String, SuccessResponse> successResponses
    ) {}

    private record SuccessResponse(
            String status,
            String responseComponent,
            String responseSchema,
            Map<String, String> headerComponents
    ) {}

    private record ErrorRule(
            int status,
            String retryPolicy,
            String currentEtagKind,
            boolean currentEtagRequired,
            boolean fieldErrorsRequired,
            boolean receiptRefRequired
    ) {}

    private record SuccessfulReceiptContract(String factSchema, String factType, String versionField) {}

    private record ExampleContract(
            String fileName,
            String schemaName,
            String exampleKey,
            String mediaPointer
    ) {}
}
