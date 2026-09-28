package io.github.windyzhu3.ontologylaw.contract.internal.persistence;

import io.github.windyzhu3.ontologylaw.contract.ContractWorkflowService;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.util.*;

/** Exact metadata only; no decrypted document is needed to prove command provenance. */
public final class JdbcContractWorkflowMetadata {
    private JdbcContractWorkflowMetadata() {}
    public static ContractWorkflowService.Metadata read(Connection c,UUID tenant,Subject exact)throws SQLException {
        String table=exact.type().startsWith("contract.")?exact.type().substring(9):"";
        String join="", opportunity="f.opportunity_id",actor;
        switch(table) {
            case "payment_request","payment_review","payment_workflow","execution_workflow","execution_verification","negotiation_disposition" -> actor="f.recorded_by";
            case "termination_review_assignment" -> {actor="f.recorded_by";join=" join contract.negotiation_disposition p on p.tenant_id=f.tenant_id and p.negotiation_disposition_id=f.request_id";opportunity="p.opportunity_id";}
            case "preparation_request","preparation_draft" -> actor="f.owner_appointment_id";
            case "signature_arrangement" -> actor="f.registered_by_appointment_id";
            case "signature_draft" -> actor="f.saved_by_appointment_id";
            case "signature_submission" -> actor="f.submitted_by_appointment_id";
            case "signature_verification" -> actor="f.verified_by_appointment_id";
            case "signature_archive" -> actor="f.archived_by_appointment_id";
            case "signature_revision_return","signature_workflow" -> actor="f.created_by_appointment_id";
            case "preparation_workflow" -> actor="f.created_by_appointment_id";
            case "preparation_decision" -> {actor="f.decided_by_appointment_id";join=" join contract.preparation_request p on p.tenant_id=f.tenant_id and p.preparation_request_id=f.preparation_request_id";opportunity="p.opportunity_id";}
            case "contract" -> actor="f.created_by_appointment_id";
            case "contract_revision" -> {actor="f.created_by_appointment_id";join=" join contract.contract root on root.tenant_id=f.tenant_id and root.contract_id=f.contract_id";opportunity="root.opportunity_id";}
            case "revision_review_request","revision_approval_request" -> {actor="f.requested_by_appointment_id";join=" join contract.contract_revision v on v.tenant_id=f.tenant_id and v.contract_revision_id=f.contract_revision_id";join+=" join contract.contract root on root.tenant_id=v.tenant_id and root.contract_id=v.contract_id";opportunity="root.opportunity_id";}
            case "revision_review_decision" -> {actor="f.decided_by_appointment_id";join=" join contract.revision_review_request p on p.tenant_id=f.tenant_id and p.revision_review_request_id=f.request_id join contract.contract_revision v on v.tenant_id=p.tenant_id and v.contract_revision_id=p.contract_revision_id";join+=" join contract.contract root on root.tenant_id=v.tenant_id and root.contract_id=v.contract_id";opportunity="root.opportunity_id";}
            case "revision_approval_decision" -> {actor="f.decided_by_appointment_id";join=" join contract.revision_approval_requirement p on p.tenant_id=f.tenant_id and p.revision_approval_requirement_id=f.requirement_id join contract.contract_revision v on v.tenant_id=p.tenant_id and v.contract_revision_id=p.contract_revision_id";join+=" join contract.contract root on root.tenant_id=v.tenant_id and root.contract_id=v.contract_id";opportunity="root.opportunity_id";}
            default -> {return null;}
        }
        boolean version=table.equals("contract_revision");
        String query="select "+opportunity+" oid,"+actor+" actor,f.created_at,"+(version?"null::bigint revision,f.content_digest digest":"f.revision,null::bytea digest")+",f.created_in_transaction=pg_current_xact_id() fresh from contract."+table+" f"+join+" where f.tenant_id=? and f."+table+"_id=?";
        try(var p=c.prepareStatement(query)){p.setObject(1,tenant);p.setObject(2,exact.id());try(var r=p.executeQuery()){
            if(!r.next())return null;
            byte[] digest=r.getBytes("digest");
            if(!Objects.equals(r.getObject("revision"),exact.revision())||!Objects.equals(digest==null?null:Base64.getUrlEncoder().withoutPadding().encodeToString(digest),exact.hash()))return null;
            return new ContractWorkflowService.Metadata(r.getObject("oid",UUID.class),r.getObject("actor",UUID.class),r.getTimestamp("created_at").toInstant(),r.getBoolean("fresh"));
        }}
    }
}


