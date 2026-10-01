package io.github.windyzhu3.ontologylaw.audit;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationSnapshot;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Path;
import java.sql.*;
import java.util.*;

/** Append-only owner port. Writes on the caller's active AUDIT capability connection. */
public interface AuditAppender {
    record ManagementDisclosureEntry(UUID id,UUID correlationId,Subject disclosedSource,AuthorizationSnapshot authorization){
        public ManagementDisclosureEntry{var r=authorization.request();if(r.actor().principalKind()!=io.github.windyzhu3.ontologylaw.identity.AuthorizationService.PrincipalKind.HUMAN||r.actor().onBehalfAppointmentId()!=null||!authorization.allowed()||!disclosedSource.equals(r.subject())||r.requirement().path()!=Path.DIRECT||!(Set.of("TEAM_TASK_READ","LEAD_MANAGEMENT_READ").contains(r.requirement().authorityCode())?Set.of("SOURCE_INTAKE_OWNER","ROUTING_SUPERVISOR","ASSIGNMENT_OWNER","OPPORTUNITY_OWNER").contains(r.requirement().slot()):"OPPORTUNITY_OWNER".equals(r.requirement().slot())&&Set.of("PAYMENT_LEDGER_READ","TRANSFER_LEDGER_READ").contains(r.requirement().authorityCode())))throw new IllegalArgumentException("Invalid management disclosure");}
        public String summary(){var s=disclosedSource;return io.github.windyzhu3.ontologylaw.audit.internal.ReceiptAuditJson.encode(Map.of("profile","R2_MANAGEMENT_DISCLOSURE_V1","version",1,"disclosedSource",s.revision()!=null?Map.of("type",s.type(),"id",s.id().toString(),"revision",s.revision()):Map.of("type",s.type(),"id",s.id().toString(),"hash",s.hash())));}
        public byte[] digest(){return io.github.windyzhu3.ontologylaw.audit.internal.ReceiptAuditJson.digest(summary());}
    }
    default void appendManagement(Connection c,List<ManagementDisclosureEntry> entries)throws SQLException{throw new SQLException("Management disclosure unsupported","0A000");}
    /** Synchronous and transaction-local; any failure must still prevent the read response. */
    default void appendWorkcards(Connection c,List<ReadDisclosureEntry> entries)throws SQLException {
        for(var entry:entries)append(c,entry);
    }
    default void appendContracts(Connection c,List<ContractDisclosureEntry> entries)throws SQLException {
        for(var entry:entries)append(c,entry);
    }
    default void appendOpportunityLedger(Connection c,List<OpportunityLedgerDisclosureEntry> entries,List<QuoteDisclosureEntry> quotes)throws SQLException {
        for(var entry:entries)append(c,entry);
        for(var entry:quotes)append(c,entry);
    }
    record ContractDisclosureEntry(UUID id,UUID correlationId,Subject disclosedSource,AuthorizationSnapshot authorization,String mode){
        public ContractDisclosureEntry{var r=authorization.request();if(!Set.of("BODY","DOWNLOAD","HISTORY").contains(mode)||r.actor().principalKind()!=io.github.windyzhu3.ontologylaw.identity.AuthorizationService.PrincipalKind.HUMAN||r.actor().onBehalfAppointmentId()!=null||!authorization.allowed()||!disclosedSource.equals(r.subject())||!"OPPORTUNITY_OWNER".equals(r.requirement().slot())||r.requirement().path()!=Path.DIRECT||!Set.of("CONTRACT_READ").contains(r.requirement().authorityCode()))throw new IllegalArgumentException("Invalid contract disclosure");}
        public String summary(){var s=disclosedSource;return io.github.windyzhu3.ontologylaw.audit.internal.ReceiptAuditJson.encode(Map.of("profile","R2_CONTRACT_DISCLOSURE_V1","version",1,"responseMode",mode,"disclosedSource",s.revision()!=null?Map.of("type",s.type(),"id",s.id().toString(),"revision",s.revision()):Map.of("type",s.type(),"id",s.id().toString(),"hash",s.hash())));}
        public byte[] digest(){return io.github.windyzhu3.ontologylaw.audit.internal.ReceiptAuditJson.digest(summary());}
    }
    default void append(Connection c,ContractDisclosureEntry e)throws SQLException{throw new SQLException("Contract disclosure unsupported","0A000");}
    record FollowupAttemptDisclosureEntry(UUID id,UUID correlationId,Subject disclosedSource,AuthorizationSnapshot authorization,String mode){
        public FollowupAttemptDisclosureEntry{var r=authorization.request();if(!Set.of("BODY").contains(mode)||r.actor().principalKind()!=io.github.windyzhu3.ontologylaw.identity.AuthorizationService.PrincipalKind.HUMAN||r.actor().onBehalfAppointmentId()!=null||!authorization.allowed()||!disclosedSource.equals(r.subject())||!"OPPORTUNITY_OWNER".equals(r.requirement().slot())||r.requirement().path()!=Path.DIRECT||!Set.of("SALES_OPPORTUNITY_OWNER","QUOTE_RESPONSE","OPPORTUNITY_LEDGER_READ","QUOTE_READ").contains(r.requirement().authorityCode()))throw new IllegalArgumentException("Invalid followup attempt disclosure");}
        public String summary(){var s=disclosedSource;return io.github.windyzhu3.ontologylaw.audit.internal.ReceiptAuditJson.encode(Map.of("profile","R2_FOLLOWUP_ATTEMPT_DISCLOSURE_V1","version",1,"responseMode",mode,"disclosedSource",s.revision()!=null?Map.of("type",s.type(),"id",s.id().toString(),"revision",s.revision()):Map.of("type",s.type(),"id",s.id().toString(),"hash",s.hash())));}
        public byte[] digest(){return io.github.windyzhu3.ontologylaw.audit.internal.ReceiptAuditJson.digest(summary());}
    }
    default void append(Connection c,FollowupAttemptDisclosureEntry e)throws SQLException{throw new SQLException("Followup attempt disclosure unsupported","0A000");}
    record QuoteDisclosureEntry(UUID id,UUID correlationId,Subject disclosedSource,AuthorizationSnapshot authorization,String mode){
        public QuoteDisclosureEntry{var r=authorization.request();if(!Set.of("BODY","DOWNLOAD","HISTORY").contains(mode)||r.actor().principalKind()!=io.github.windyzhu3.ontologylaw.identity.AuthorizationService.PrincipalKind.HUMAN||r.actor().onBehalfAppointmentId()!=null||!authorization.allowed()||!disclosedSource.equals(r.subject())||!"OPPORTUNITY_OWNER".equals(r.requirement().slot())||r.requirement().path()!=Path.DIRECT||!Set.of("QUOTE_READ").contains(r.requirement().authorityCode()))throw new IllegalArgumentException("Invalid quote disclosure");}
        public String summary(){var s=disclosedSource;return io.github.windyzhu3.ontologylaw.audit.internal.ReceiptAuditJson.encode(Map.of("profile","R2_QUOTE_DISCLOSURE_V1","version",1,"responseMode",mode,"disclosedSource",s.revision()!=null?Map.of("type",s.type(),"id",s.id().toString(),"revision",s.revision()):Map.of("type",s.type(),"id",s.id().toString(),"hash",s.hash())));}
        public byte[] digest(){return io.github.windyzhu3.ontologylaw.audit.internal.ReceiptAuditJson.digest(summary());}
    }
    default void append(Connection c,QuoteDisclosureEntry e)throws SQLException{throw new SQLException("Quote disclosure unsupported","0A000");}
    record MaterialDisclosureEntry(UUID id,UUID correlationId,Subject disclosedSource,AuthorizationSnapshot authorization,String mode){
        public MaterialDisclosureEntry{var r=authorization.request();if(!Set.of("BODY","DOWNLOAD","UPLOAD_STATUS").contains(mode)||r.actor().principalKind()!=io.github.windyzhu3.ontologylaw.identity.AuthorizationService.PrincipalKind.HUMAN||r.actor().onBehalfAppointmentId()!=null||!authorization.allowed()||!disclosedSource.equals(r.subject())||!"OPPORTUNITY_OWNER".equals(r.requirement().slot())||r.requirement().path()!=Path.DIRECT||!Set.of("MATERIALS_MANAGE","MATERIALS_READ").contains(r.requirement().authorityCode()))throw new IllegalArgumentException("Invalid material disclosure");}
        public String summary(){var s=disclosedSource;return io.github.windyzhu3.ontologylaw.audit.internal.ReceiptAuditJson.encode(Map.of("profile","R2_MATERIAL_DISCLOSURE_V1","version",1,"responseMode",mode,"disclosedSource",s.revision()!=null?Map.of("type",s.type(),"id",s.id().toString(),"revision",s.revision()):Map.of("type",s.type(),"id",s.id().toString(),"hash",s.hash())));}
        public byte[] digest(){return io.github.windyzhu3.ontologylaw.audit.internal.ReceiptAuditJson.digest(summary());}
    }
    default void append(Connection c,MaterialDisclosureEntry e)throws SQLException{throw new SQLException("Material disclosure unsupported","0A000");}
    record CustomerRequirementsDisclosureEntry(UUID id,UUID correlationId,Subject disclosedSource,AuthorizationSnapshot authorization){
        public CustomerRequirementsDisclosureEntry{var r=authorization.request();if(!Set.of("opportunity.opportunity","opportunity.responsibility_handoff","opportunity.opportunity_progress","opportunity.followup_attempt","opportunity.quote_response","opportunity.customer_requirement_draft","opportunity.customer_requirement_draft_party","opportunity.customer_requirement_confirmation","opportunity.customer_requirement_participant","lead.lead","lead.lead_assignment","lead.lead_contact_result","party.party","party.profile_version","responsibility.task_occurrence","responsibility.wait_receipt","responsibility.action_draft","responsibility.decision_record","identity.appointment","identity.principal","identity.organization_unit").contains(disclosedSource.type())||r.actor().principalKind()!=io.github.windyzhu3.ontologylaw.identity.AuthorizationService.PrincipalKind.HUMAN||r.actor().onBehalfAppointmentId()!=null||!authorization.allowed()||!disclosedSource.equals(r.subject())||!"OPPORTUNITY_OWNER".equals(r.requirement().slot())||r.requirement().path()!=Path.DIRECT||!Set.of("SALES_OPPORTUNITY_OWNER","OPPORTUNITY_LEDGER_READ","CUSTOMER_REQUIREMENTS_MANAGE","PARTY_PROFILE_MANAGE").contains(r.requirement().authorityCode()))throw new IllegalArgumentException("Invalid customer disclosure");}
        public String summary(){var s=disclosedSource;return io.github.windyzhu3.ontologylaw.audit.internal.ReceiptAuditJson.encode(Map.of("profile","R2_CUSTOMER_REQUIREMENTS_DISCLOSURE_V1","version",1,"responseMode","BODY","disclosedSource",s.revision()!=null?Map.of("type",s.type(),"id",s.id().toString(),"revision",s.revision()):Map.of("type",s.type(),"id",s.id().toString(),"hash",s.hash())));}
        public byte[] digest(){return io.github.windyzhu3.ontologylaw.audit.internal.ReceiptAuditJson.digest(summary());}
    }
    default void append(Connection c,CustomerRequirementsDisclosureEntry e)throws SQLException{throw new SQLException("Customer disclosure unsupported","0A000");}

    record OpportunityClosureDisclosureEntry(UUID id,UUID correlationId,Subject disclosedSource,AuthorizationSnapshot authorization) {
        public OpportunityClosureDisclosureEntry {
            var r=authorization.request();
            if(!"OPPORTUNITY_OWNER".equals(r.requirement().slot())||!Set.of("opportunity.closure","opportunity.quote_workflow","opportunity.quote_revision","contract.contract","contract.preparation_request","contract.preparation_workflow","transfer.transfer_request","opportunity.opportunity","opportunity.responsibility_handoff","opportunity.opportunity_progress","lead.lead","lead.lead_assignment","lead.lead_contact_result","party.party","responsibility.task_occurrence","responsibility.wait_receipt","responsibility.action_draft","responsibility.decision_record","identity.appointment","identity.principal","identity.organization_unit").contains(disclosedSource.type())||!authorization.allowed()||!disclosedSource.equals(r.subject())||r.actor().principalKind()!=io.github.windyzhu3.ontologylaw.identity.AuthorizationService.PrincipalKind.HUMAN||r.actor().onBehalfAppointmentId()!=null||r.requirement().path()!=Path.DIRECT||!Set.of("SALES_OPPORTUNITY_OWNER","OPPORTUNITY_LEDGER_READ","OPPORTUNITY_CLOSE").contains(r.requirement().authorityCode()))throw new IllegalArgumentException("Invalid closure disclosure");
        }
        public String summary(){var s=disclosedSource;return io.github.windyzhu3.ontologylaw.audit.internal.ReceiptAuditJson.encode(Map.of("profile","R2_OPPORTUNITY_CLOSURE_DISCLOSURE_V1","version",1,"responseMode","BODY","disclosedSource",s.revision()!=null?Map.of("type",s.type(),"id",s.id().toString(),"revision",s.revision()):Map.of("type",s.type(),"id",s.id().toString(),"hash",s.hash())));}
        public byte[] digest(){return io.github.windyzhu3.ontologylaw.audit.internal.ReceiptAuditJson.digest(summary());}
    }
    default void append(Connection c,OpportunityClosureDisclosureEntry e)throws SQLException{throw new SQLException("Closure disclosure unsupported","0A000");}

    record OpportunityLedgerDisclosureEntry(UUID id,UUID correlationId,Subject disclosedSource,AuthorizationSnapshot authorization) {
        public OpportunityLedgerDisclosureEntry {
            var r=authorization.request();
            if(!"OPPORTUNITY_OWNER".equals(r.requirement().slot())||!Set.of("opportunity.opportunity","opportunity.responsibility_handoff","opportunity.opportunity_progress","opportunity.followup_attempt","lead.lead","lead.lead_assignment","lead.lead_contact_result","party.party","responsibility.task_occurrence","responsibility.wait_receipt","responsibility.action_draft","responsibility.decision_record","identity.appointment","identity.principal","identity.organization_unit").contains(disclosedSource.type())||!authorization.allowed()||!disclosedSource.equals(r.subject())||r.actor().principalKind()!=io.github.windyzhu3.ontologylaw.identity.AuthorizationService.PrincipalKind.HUMAN||r.actor().onBehalfAppointmentId()!=null||r.requirement().path()!=Path.DIRECT||!Set.of("SALES_OPPORTUNITY_OWNER","OPPORTUNITY_LEDGER_READ").contains(r.requirement().authorityCode()))throw new IllegalArgumentException("Invalid ledger disclosure");
        }
        public String summary(){var s=disclosedSource;return io.github.windyzhu3.ontologylaw.audit.internal.ReceiptAuditJson.encode(Map.of("profile","R2_OPPORTUNITY_LEDGER_DISCLOSURE_V1","version",1,"responseMode","BODY","disclosedSource",s.revision()!=null?Map.of("type",s.type(),"id",s.id().toString(),"revision",s.revision()):Map.of("type",s.type(),"id",s.id().toString(),"hash",s.hash())));}
        public byte[] digest(){return io.github.windyzhu3.ontologylaw.audit.internal.ReceiptAuditJson.digest(summary());}
    }
    default void append(Connection c,OpportunityLedgerDisclosureEntry e)throws SQLException{throw new SQLException("Ledger disclosure unsupported","0A000");}

    record OwnerExceptionDisclosureEntry(UUID id,UUID correlationId,Subject disclosedSource,Subject authorizationAnchor,AuthorizationSnapshot authorization) {
        public OwnerExceptionDisclosureEntry {
            if(!authorization.allowed()||!authorizationAnchor.equals(authorization.request().subject())||!"OPPORTUNITY_OWNER_EXCEPTION_READ".equals(authorization.request().requirement().authorityCode())||authorization.request().actor().onBehalfAppointmentId()!=null||authorization.request().actor().principalKind()!=io.github.windyzhu3.ontologylaw.identity.AuthorizationService.PrincipalKind.HUMAN||!"lead.lead".equals(authorizationAnchor.type())||!Set.of("lead.lead","identity.appointment","identity.principal","identity.organization_unit").contains(disclosedSource.type())||disclosedSource.revision()==null||("lead.lead".equals(disclosedSource.type())&&!disclosedSource.equals(authorizationAnchor)))throw new IllegalArgumentException("Invalid exception label disclosure");
        }
        public String summary(){return io.github.windyzhu3.ontologylaw.audit.internal.ReceiptAuditJson.encode(Map.of("profile","R2_OWNER_EXCEPTION_DISCLOSURE_V1","version",1,"responseMode","BODY","fieldGroups",List.of("OWNER_EXCEPTION_LABELS"),"disclosedSource",selector(disclosedSource),"authorizationAnchor",selector(authorizationAnchor)));}
        private static Map<String,Object> selector(Subject s){return Map.of("type",s.type(),"id",s.id().toString(),"revision",s.revision());}
        public byte[] digest(){return io.github.windyzhu3.ontologylaw.audit.internal.ReceiptAuditJson.digest(summary());}
    }
    default void append(Connection c,OwnerExceptionDisclosureEntry entry)throws SQLException{throw new SQLException("Exception disclosure audit unsupported","0A000");}
    record OwnerValidationEntry(UUID id,UUID correlationId,AuthorizationSnapshot authorization,Subject opportunity,Subject basis,UUID owner,java.time.Instant observedAt,boolean validated,List<String> reasons,List<String> ownerEvidence) {
        public OwnerValidationEntry {reasons=List.copyOf(reasons);ownerEvidence=List.copyOf(ownerEvidence);if(!authorization.allowed()||!"OPPORTUNITY_OWNER_EXCEPTION_DISCOVER".equals(authorization.request().requirement().authorityCode())||authorization.request().actor().principalKind()!=io.github.windyzhu3.ontologylaw.identity.AuthorizationService.PrincipalKind.SERVICE||!opportunity.equals(authorization.request().subject()))throw new IllegalArgumentException("Invalid owner observation audit");}
        public String summary(){return io.github.windyzhu3.ontologylaw.audit.internal.ReceiptAuditJson.encode(Map.of("profile","R2_OPPORTUNITY_OWNER_VALIDATION_V1","opportunity",selector(opportunity),"basis",selector(basis),"ownerAppointmentId",owner.toString(),"observedAt",observedAt.toString(),"validated",validated,"reasonCodes",reasons,"ownerAuthorizationEvidence",ownerEvidence));}
        private static Map<String,Object> selector(Subject s){return s.revision()!=null?Map.of("type",s.type(),"id",s.id().toString(),"revision",s.revision()):Map.of("type",s.type(),"id",s.id().toString(),"hash",s.hash());}
        public byte[] digest(){return io.github.windyzhu3.ontologylaw.audit.internal.ReceiptAuditJson.digest(summary());}
        public Subject selector(){return new Subject("audit.audit_entry",id,null,Base64.getUrlEncoder().withoutPadding().encodeToString(digest()));}
    }
    default void append(Connection c,OwnerValidationEntry entry)throws SQLException{throw new SQLException("Owner validation audit unsupported","0A000");}
    record IdentityEntry(UUID id,UUID commandId,String commandType,UUID correlationId,String outcome,AuthorizationSnapshot authorization,String summary) {
        public IdentityEntry {
            if(!io.github.windyzhu3.ontologylaw.identity.IdentityCommands.registered(commandType)||!authorization.allowed()||authorization.request().actor().onBehalfAppointmentId()!=null||summary.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>8192)throw new IllegalArgumentException("Invalid Identity audit");
        }
        public byte[] digest(){return io.github.windyzhu3.ontologylaw.audit.internal.ReceiptAuditJson.digest(summary);}
    }
    default void append(Connection c,IdentityEntry entry)throws SQLException{throw new SQLException("Identity audit unsupported","0A000");}
    record IdentityDisclosureEntry(UUID id,UUID correlationId,String operationId,AuthorizationSnapshot authorization,int resultCount,List<Subject> sources) {
        public IdentityDisclosureEntry{sources=List.copyOf(sources);if(!authorization.allowed()||authorization.request().actor().onBehalfAppointmentId()!=null||resultCount<0||resultCount>50||sources.size()>50||new HashSet<>(sources).size()!=sources.size()||sources.stream().anyMatch(s->s.revision()==null||!Set.of("identity.principal","identity.organization_unit","identity.appointment","identity.authority_grant").contains(s.type())))throw new IllegalArgumentException("Invalid Identity disclosure");}
        public String summary(){return io.github.windyzhu3.ontologylaw.audit.internal.ReceiptAuditJson.encode(Map.of("profile","R1_IDENTITY_DISCLOSURE_V1","version",1,"operationId",operationId,"responseMode","BODY","resultCount",resultCount,"disclosedSources",sources.stream().map(s->Map.of("type",s.type(),"id",s.id().toString(),"revision",s.revision())).toList()));}
        public byte[] digest(){return io.github.windyzhu3.ontologylaw.audit.internal.ReceiptAuditJson.digest(summary());}
    }
    default void append(Connection c,IdentityDisclosureEntry entry)throws SQLException{throw new SQLException("Identity disclosure unsupported","0A000");}
    record BootstrapEntry(UUID id,UUID commandId,UUID correlationId,String manifestDigest,String operatorAssertion,
            io.github.windyzhu3.ontologylaw.identity.IdentityBootstrapService.Facts facts) {
        public BootstrapEntry{Objects.requireNonNull(id);Objects.requireNonNull(commandId);Objects.requireNonNull(correlationId);Objects.requireNonNull(facts);if(manifestDigest==null||!manifestDigest.matches("[0-9a-f]{64}")||operatorAssertion==null||operatorAssertion.isBlank())throw new IllegalArgumentException("Invalid bootstrap audit");}
        public String summary(){return io.github.windyzhu3.ontologylaw.audit.internal.ReceiptAuditJson.encode(Map.of("profile","R1_IDENTITY_BOOTSTRAP_V1","version",1,"manifestDigest",manifestDigest,"operatorAssertion",operatorAssertion,"founderPrincipalId",facts.principal().toString(),"rootOrganizationId",facts.root().toString(),"appointmentId",facts.appointment().toString(),"grantIds",facts.grants().stream().map(UUID::toString).toList()));}
        public byte[] digest(){return io.github.windyzhu3.ontologylaw.audit.internal.ReceiptAuditJson.digest(summary());}
    }
    default void append(Connection c,BootstrapEntry entry)throws SQLException{throw new SQLException("Bootstrap audit unsupported","0A000");}
    default BootstrapEntry bootstrapOriginal(Connection c,UUID tenant,UUID command)throws SQLException{throw new SQLException("Bootstrap audit unsupported","0A000");}
    record SelfDisclosureEntry(UUID id,UUID correlationId,UUID tenantId,Subject principal,UUID ownAppointment,
            java.time.Instant checkedAt,List<Subject> sources) {
        public SelfDisclosureEntry {
            Objects.requireNonNull(id);Objects.requireNonNull(correlationId);Objects.requireNonNull(tenantId);Objects.requireNonNull(checkedAt);
            sources=List.copyOf(sources);
            if(!"identity.principal".equals(principal.type())||sources.isEmpty()||sources.size()>101||!sources.contains(principal)
                    ||sources.stream().anyMatch(s->s.revision()==null||!s.equals(principal)&&!"identity.appointment".equals(s.type()))
                    ||new HashSet<>(sources).size()!=sources.size())throw new IllegalArgumentException("Invalid self disclosure");
        }
        public String summary(){return io.github.windyzhu3.ontologylaw.audit.internal.ReceiptAuditJson.encode(Map.of("profile","R1_IDENTITY_SELF_DISCLOSURE_V1","version",1,"operationId","getSessionContext","responseMode","BODY","resultCount",sources.size(),"disclosedSources",sources.stream().map(s->Map.of("type",s.type(),"id",s.id().toString(),"revision",s.revision())).toList()));}
        public byte[] digest(){return io.github.windyzhu3.ontologylaw.audit.internal.ReceiptAuditJson.digest(summary());}
    }
    default void append(Connection c,SelfDisclosureEntry entry)throws SQLException{throw new SQLException("Self disclosure unsupported","0A000");}
    record Entry(UUID id,UUID commandId,String commandType,UUID correlationId,String result,
            AuthorizationSnapshot authorization,String summary,byte[] summaryDigest,int schemaVersion) {
        public Entry(UUID id,UUID commandId,String commandType,UUID correlationId,String result,
                AuthorizationSnapshot authorization,String summary,byte[] summaryDigest) {
            this(id,commandId,commandType,correlationId,result,authorization,summary,summaryDigest,1);
        }
        public Entry {summaryDigest=summaryDigest.clone();if(schemaVersion!=1&&schemaVersion!=2)throw new IllegalArgumentException("Unsupported command Audit schema");}
        @Override public byte[] summaryDigest(){return summaryDigest.clone();}
    }
    void append(Connection connection,Entry entry) throws SQLException;
    record ReceiptDisclosureEntry(UUID id,UUID correlationId,UUID commandId,UUID receiptId,
            Subject disclosedSource,Subject authorizationAnchor,AuthorizationSnapshot authorization) {
        public ReceiptDisclosureEntry {
            Objects.requireNonNull(id);Objects.requireNonNull(correlationId);Objects.requireNonNull(commandId);Objects.requireNonNull(receiptId);
            if(!"execution.command_receipt".equals(disclosedSource.type())||disclosedSource.hash()==null||!receiptId.equals(disclosedSource.id())
                    ||!authorization.allowed()||!authorizationAnchor.equals(authorization.request().subject()))throw new IllegalArgumentException("Invalid receipt disclosure");
        }
        public String summary() {
            return io.github.windyzhu3.ontologylaw.audit.internal.ReceiptAuditJson.encode(Map.of("profile","R1_COMMAND_RECEIPT_DISCLOSURE_V1","version",1,"responseMode","BODY",
                    "commandId",commandId.toString(),"receiptId",receiptId.toString(),"disclosedSource",selector(disclosedSource),"authorizationAnchor",selector(authorizationAnchor)));
        }
        private static Map<String,Object> selector(Subject s) {return s.revision()==null?Map.of("type",s.type(),"id",s.id().toString(),"hash",s.hash()):Map.of("type",s.type(),"id",s.id().toString(),"revision",s.revision());}
        public byte[] summaryDigest(){return io.github.windyzhu3.ontologylaw.audit.internal.ReceiptAuditJson.digest(summary());}
    }
    default void append(Connection connection,ReceiptDisclosureEntry entry)throws SQLException {throw new SQLException("Receipt disclosure unsupported","0A000");}
    enum ResponseMode { BODY, CACHE_REVALIDATED }
    record ReadDisclosureEntry(UUID id,UUID correlationId,Subject disclosedSource,Subject authorizationAnchor,
            AuthorizationSnapshot authorization,ResponseMode responseMode) {
        public ReadDisclosureEntry {
            Objects.requireNonNull(id);Objects.requireNonNull(correlationId);Objects.requireNonNull(disclosedSource);
            Objects.requireNonNull(authorizationAnchor);Objects.requireNonNull(authorization);Objects.requireNonNull(responseMode);
            if(!authorization.allowed()||!authorizationAnchor.equals(authorization.request().subject()))throw new IllegalArgumentException("Authorized anchor required");
            Set<String> anchors=switch(disclosedSource.type()) {
                case "opportunity.opportunity","opportunity.responsibility_handoff","transfer.workflow","transfer.transfer_request" -> {
                    if(!opportunityDisclosure(authorization))throw new IllegalArgumentException("Opportunity disclosure authority required");
                    yield Set.of(disclosedSource.type());
                }
                case "responsibility.wait_receipt","lead.lead","party.party","lead.lead_assignment","responsibility.task_occurrence","responsibility.decision_record","evidence.evidence_submission","evidence.evidence_binding" -> Set.of(disclosedSource.type());
                case "responsibility.action_draft" -> opportunityDisclosure(authorization)?Set.of("responsibility.action_draft"):Set.of("responsibility.task_occurrence");
                case "lead.lead_contact_result" -> Set.of("lead.lead");
                case "identity.appointment","identity.principal","identity.organization_unit" -> Set.of("responsibility.task_occurrence","lead.lead");
                default -> throw new IllegalArgumentException("Unregistered disclosed source");
            };
            if(!anchors.contains(authorizationAnchor.type()))throw new IllegalArgumentException("Unregistered source binding");
            if(anchors.contains(disclosedSource.type())&&!disclosedSource.equals(authorizationAnchor))throw new IllegalArgumentException("Exact direct source required");
        }
        private static boolean opportunityDisclosure(AuthorizationSnapshot snapshot) {
            var request=snapshot.request();var requirement=request.requirement();
            return "OPPORTUNITY_OWNER".equals(requirement.slot())&&Set.of("SALES_OPPORTUNITY_OWNER","QUOTE_PREPARE","QUOTE_APPROVE","QUOTE_DELIVER","QUOTE_RESPONSE","CONTRACT_PREPARE","CONTRACT_PREPARATION_DECIDE","CONTRACT_REVIEW","CONTRACT_APPROVE","CONTRACT_SIGNATURE_VERIFY","CONTRACT_TERMINATION_REVIEW","CONTRACT_EXECUTION_VERIFY","PAYMENT_CONFIRM","PAYMENT_SUBMIT","TRANSFER_SUBMIT","TRANSFER_REVIEW","TRANSFER_ACCEPT","MATTER_CLASSIFY").contains(requirement.authorityCode())
                &&requirement.path()==Path.DIRECT&&request.actor().onBehalfAppointmentId()==null;
        }
        public String schemaCode(){return opportunityDisclosure(authorization)?"R2_CURRENT_WORKCARD_DISCLOSURE_AUDIT_V1":"R1_CURRENT_WORKCARD_DISCLOSURE_AUDIT_V1";}
        public String summary() {
            String profile=opportunityDisclosure(authorization)?"R2_CURRENT_WORKCARD_DISCLOSURE_V1":"R1_CURRENT_WORKCARD_DISCLOSURE_V1";
            return "{\"profile\":\""+profile+"\",\"version\":1,\"responseMode\":\""+responseMode
                +"\",\"fieldGroups\":[\"CURRENT_WORKCARD\"],\"disclosedSource\":"+selector(disclosedSource)+",\"authorizationAnchor\":"+selector(authorizationAnchor)+"}";
        }
        private static String selector(Subject s) {
            return "{\"type\":\""+s.type()+"\",\"id\":\""+s.id()+"\",\"revision\":"+s.revision()+",\"hash\":"+(s.hash()==null?"null":"\""+s.hash()+"\"")+"}";
        }
        public byte[] summaryDigest() {
            try{return java.security.MessageDigest.getInstance("SHA-256").digest(summary().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
            catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
        }
    }
    default void append(Connection connection,ReadDisclosureEntry entry)throws SQLException {
        throw new SQLException("Read disclosure append not supported","0A000");
    }
    static AuditAppender databaseBacked(String executionNodeCode){return new io.github.windyzhu3.ontologylaw.audit.internal.persistence.JooqAuditAppender(executionNodeCode);}
}
