package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.api.security.*;
import io.github.windyzhu3.ontologylaw.testing.KeycloakFixture;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Real Code+PKCE login dynamically resolves the actual delegate; seeded business facts are compatibility fixtures. */
class R1DynamicHumanReceiptHttpIT extends R1HttpFixture {
    KeycloakFixture idp;
    @BeforeAll void identity()throws Exception{idp=new KeycloakFixture().start();}
    @AfterAll void stopIdentity(){if(idp!=null)idp.close();}
    @ParameterizedTest @ValueSource(strings={"DELEGATION","SOURCE","DENY_ACTUAL","DENY_REPRESENTED"})
    void dynamically_selected_delegate_executes_and_only_complete_original_actor_recovers_with_current_authorization(String defect)throws Exception {
        setupContact();UUID command=UUID.randomUUID();var values=contact("CONNECTED_VALID");var login=idp.login();
        var delegate=credentialActor(PrincipalKind.HUMAN,login.subject(),"LEAD_CAPTURE");
        UUID delegation=UUID.randomUUID();
        mutate("insert into identity.delegation_grant (tenant_id,delegation_grant_id,source_authority_grant_id,delegator_appointment_id,delegate_appointment_id,scope_organization_unit_id,valid_from,state,created_at) values (?,?,?,?,?,?,clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),delegation,seed.grant(),seed.appointment(),delegate.appointmentId(),seed.org());
        realHumanResolver=new ActorContextResolver(database::apiConnection,new ExternalSubjectProtection(credentialKeys::get),HumanCredentialVerifier.isolatedLoopback(List.of(new HumanCredentialVerifier.Trust(idp.issuer(),KeycloakFixture.AUDIENCE,"FIXTURE",seed.tenant(),KeycloakFixture.AUDIENCE,idp.introspectionSecret))));realHumanToken=login.accessToken();
        var selection=Map.of("X-Appointment-Id",delegate.appointmentId().toString(),"X-On-Behalf-Appointment-Id",seed.appointment().toString());
        try(var http=new HttpHarness()) {
            var read=http.request("GET","/api/v1/workcards/current",null,selection);assertEquals(200,read.statusCode());assertEquals(current.selector().id().toString(),((Map<?,?>)http.body(read).get("currentCard")).get("taskId"));
            var saveHeaders=new HashMap<>(selection);saveHeaders.put("Idempotency-Key",UUID.randomUUID().toString());saveHeaders.put("If-None-Match","*");
            var saved=http.request("PUT","/api/v1/tasks/"+current.selector().id()+"/draft",Map.of("actionCode","RECORD_CONTACT_RESULT","schemaVersion",1,"values",values),saveHeaders);assertEquals(201,saved.statusCode());
            var body=http.body(saved);var draft=(Map<?,?>)body.get("draft");var payload=new TreeMap<String,Object>(values);payload.put("draftId",draft.get("draftId"));payload.put("expectedDraftRevision",draft.get("draftRevision"));payload.put("draftDigest",draft.get("digest"));
            var headers=new HashMap<>(selection);headers.put("Idempotency-Key",command.toString());headers.put("If-Match",(String)((Map<?,?>)body.get("preconditions")).get("taskETag"));
            var written=http.request("POST","/api/v1/tasks/"+current.selector().id()+"/commands/record-contact-result",payload,headers);assertEquals(200,written.statusCode());
            String receipt="/api/v1/commands/"+command+"/receipt";
            assertEquals(200,http.request("GET",receipt,null,selection).statusCode());var before=counts();
            assertEquals(404,http.request("GET",receipt,null,Map.of("X-Appointment-Id",delegate.appointmentId().toString())).statusCode(),"Own context cannot recover delegated receipt");assertEquals(before,counts());
            assertEquals("1",scalar("select count(*)::text from audit.audit_entry_classified_v where tenant_id=? and command_id=? and actor_principal_id=? and actor_appointment_id=? and on_behalf_of_principal_id=? and on_behalf_of_appointment_id=? and authorization_path_code='DELEGATED'",seed.tenant(),command,delegate.principalId(),delegate.appointmentId(),seed.principal(),seed.appointment()));
            if(defect.equals("DELEGATION"))mutate("update identity.delegation_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and delegation_grant_id=?",seed.tenant(),delegation);
            else if(defect.equals("SOURCE"))mutate("update identity.authority_grant set state='REVOKED',revoked_at=clock_timestamp(),revocation_reason_code='FIXTURE',revision=revision+1 where tenant_id=? and authority_grant_id=?",seed.tenant(),seed.grant());
            else mutate("insert into identity.object_access_grant (tenant_id,object_access_grant_id,grantee_principal_id,granted_by_appointment_id,access_code,effect_code,valid_from,state,created_at,object_subject_type,object_subject_id,object_subject_revision) select ?,?,?,?,'SALES_CONTACT_OWNER','DENY',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp(),'lead.lead',lead_id,revision from lead.lead where tenant_id=? and lead_id=?",seed.tenant(),UUID.randomUUID(),defect.equals("DENY_ACTUAL")?delegate.principalId():seed.principal(),seed.appointment(),seed.tenant(),current.lead().id());
            before=counts();var denied=http.request("GET",receipt,null,selection);assertEquals(403,denied.statusCode());assertFalse(denied.body().contains("receiptRef"));assertEquals(before,counts());
        }
    }
}
