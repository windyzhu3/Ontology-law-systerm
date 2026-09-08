package io.github.windyzhu3.ontologylaw.execution;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Immutable execution facts, not an authorization or disclosure API. */
public interface CommandReceiptReader {
    record Receipt(UUID commandId,String commandType,String envelope,byte[] scopeDigest,CommandOutcome outcome,Instant completedAt,Subject selector) {
        public Receipt {scopeDigest=scopeDigest.clone();}
        @Override public byte[] scopeDigest(){return scopeDigest.clone();}
        public Map<String,Object> projection(Actor actor) {
            var body=new TreeMap<String,Object>();body.put("commandId",commandId.toString());body.put("receiptId",outcome.receiptId().toString());
            body.put("outcome",outcome.status().name());body.put("completedAt",completedAt.toString());
            if(outcome.resultFact()!=null) {
                var source=outcome.resultFact();var fact=new TreeMap<String,Object>();
                String type=switch(source.type()) {case "lead.lead"->"LEAD";case "responsibility.action_draft"->"ACTION_DRAFT";case "responsibility.task_occurrence"->"TASK_OCCURRENCE";case "responsibility.decision_record"->"DECISION_RECORD";case "lead.lead_assignment"->"LEAD_ASSIGNMENT";case "lead.lead_contact_result"->"LEAD_CONTACT_RESULT";default->throw new IllegalArgumentException("Unsupported receipt fact");};
                var scope=new TreeMap<String,Object>();scope.put("profile","R1_PUBLIC_FACT_REF_V1");scope.put("tenant",actor.tenantId().toString());scope.put("principal",actor.principalId().toString());scope.put("appointment",actor.appointmentId().toString());
                scope.put("onBehalfPrincipal",actor.onBehalfPrincipalId()==null?null:actor.onBehalfPrincipalId().toString());scope.put("onBehalfAppointment",actor.onBehalfAppointmentId()==null?null:actor.onBehalfAppointmentId().toString());scope.put("kind",actor.principalKind().name());scope.put("type",source.type());scope.put("id",source.id().toString());
                fact.put("factType",type);fact.put("factRef",Base64.getUrlEncoder().withoutPadding().encodeToString(CanonicalJson.digest(CanonicalJson.encode(scope))));
                if(source.revision()!=null)fact.put("revision",source.revision());else fact.put("digest",source.hash());body.put("resultFact",Collections.unmodifiableMap(fact));
            } else body.put("rejectionCode",outcome.rejectionCode());
            return Collections.unmodifiableMap(body);
        }
    }
    Receipt read(Connection c,UUID tenant,UUID commandId) throws SQLException;
    static CommandReceiptReader databaseBacked(){return new io.github.windyzhu3.ontologylaw.execution.internal.persistence.JooqCommandReceiptReader();}
}
