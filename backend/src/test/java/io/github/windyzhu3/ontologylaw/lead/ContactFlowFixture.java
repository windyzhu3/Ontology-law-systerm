package io.github.windyzhu3.ontologylaw.lead;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
import static io.github.windyzhu3.ontologylaw.identity.AuthorizationServiceIT.sql;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Real Owner/Runtime fixtures; only business fact time is accelerated, never authorization time. */
public abstract class ContactFlowFixture extends WorkcardTestFixture {
    protected Instant businessAt=Instant.parse("2026-08-03T01:00:00.000000Z");
    protected CommandRuntime runtime;
    protected boolean bothChannels;
    @Override protected Map<String,Object> input(boolean contacts){var values=super.input(contacts);values.put("capturedAt",businessAt.minusSeconds(120).toString());if(contacts&&bothChannels)values.put("email","fixture@example.test");return values;}
    protected void recoverContact()throws Exception{
        selectTask(TaskFactory.Type.CONTACT_LEAD);var actor=service("CONTACT_TASK_RECOVER");
        try(var c=database.apiConnection()){businessAt=inTransaction(c,Capability.QUERY,x->EventResponsibilityReader.databaseBacked().latestWait(x,seed.tenant(),current.selector().id()).resumeDue().plusSeconds(1));}
        assertEquals(CommandOutcome.Status.SUCCEEDED,execute(recovery(actor)).status());selectTask(TaskFactory.Type.CONTACT_LEAD);
    }
    protected void setupContact()throws Exception {
        setupFlow(TaskFactory.Type.CONTACT_LEAD);
    }
    protected void setupFlow(TaskFactory.Type type)throws Exception {
        setupCard(type,businessAt.minusSeconds(60));
        try(var c=database.apiConnection()){inTransaction(c,Capability.COMMAND,x->{grant(x,"LEAD_VALIDITY_REVIEW");return null;});}
        var handlers=new ArrayList<CommandHandler>(new LeadCommands(policies,protection).handlers());
        handlers.removeIf(h->h.type()==CommandEnvelope.Type.RECORD_CONTACT_RESULT||h.type()==CommandEnvelope.Type.REVIEW_LEAD_VALIDITY);
        handlers.addAll(new ContactCommands(policies,protection,()->businessAt).handlers());
        runtime=new CommandRuntime(handlers,AuthorizationService.databaseBacked(),io.github.windyzhu3.ontologylaw.audit.AuditAppender.databaseBacked("CONTACT_FLOW_IT"),R1AuthorizationReaders.databaseBacked(policies),R1EventReaders.databaseBacked());
    }
    protected CommandOutcome execute(CommandEnvelope e)throws Exception {try(var c=database.apiConnection()){return assertInstanceOf(CommandOutcome.class,assertDoesNotThrow(()->runtime.execute(c,e)));}}
    protected CommandEnvelope prepare(Map<String,Object> values)throws Exception {
        ActionDraftService.Draft draft;
        try(var c=database.apiConnection()){draft=inTransaction(c,Capability.COMMAND,x->ActionDraftService.databaseBacked().save(x,seed.tenant(),current,null,CurrentLeadReader.validatedDraftValues(current.type().command,values),seed.appointment(),businessAt).draft());}
        var p=new TreeMap<String,Object>(values);p.put("draftId",draft.selector().id().toString());p.put("expectedDraftRevision",draft.selector().revision());p.put("draftDigest",draft.digest());
        return new CommandEnvelope(CommandEnvelope.Type.valueOf(current.type().command),UUID.randomUUID(),UUID.randomUUID(),seed.request().actor(),p,new CommandEnvelope.TaskPrecondition(current.selector().id(),R1ResourceTags.task(seed.request().actor(),current.selector(),current.state())));
    }
    protected Map<String,Object> contact(String code){var v=new TreeMap<String,Object>();v.put("leadAssignmentId",secondaryFact.id().toString());v.put("leadAssignmentRevision",secondaryFact.revision());v.put("contactChannelCode","PHONE");v.put("resultCode",code);if("CONNECTED_VALID".equals(code))v.put("legalNeed","Confirmed flow need");return v;}
    protected TaskFactory.Task selectTask(TaskFactory.Type type)throws Exception {try(var c=database.apiConnection()){current=inTransaction(c,Capability.QUERY,x->TaskFactory.databaseBacked().activeForLead(x,seed.tenant(),current.lead()).stream().filter(t->t.type()==type).findFirst().orElseThrow());return current;}}
    protected List<Long> counts()throws Exception {try(var c=database.apiConnection()){return inTransaction(c,Capability.QUERY,x->{var result=new ArrayList<Long>();for(String table:List.of("lead.lead_contact_result","opportunity.opportunity","responsibility.decision_record","responsibility.wait_receipt","responsibility.task_occurrence","responsibility.action_draft","execution.command_execution_slot","execution.command_receipt","audit.audit_entry_classified_v","execution.domain_event","execution.domain_event_outbox")){try(var p=x.prepareStatement("select count(*) from "+table+" where tenant_id=?")){p.setObject(1,seed.tenant());try(var r=p.executeQuery()){r.next();result.add(r.getLong(1));}}}return result;});}}
    protected void delta(List<Long> before,long... expected)throws Exception{var after=counts();var actual=new ArrayList<Long>();for(int i=0;i<before.size();i++)actual.add(after.get(i)-before.get(i));assertEquals(Arrays.stream(expected).boxed().toList(),actual);}
    protected String scalar(String query,Object... args)throws Exception{try(var c=database.apiConnection()){return inTransaction(c,Capability.QUERY,x->{try(var p=x.prepareStatement(query)){for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);try(var r=p.executeQuery()){assertTrue(r.next());return r.getString(1);}}});}}
    protected Map<String,Object> review(CommandOutcome contact,String code){return Map.of("triggeringContactResultId",contact.resultFact().id().toString(),"triggeringContactResultHash",contact.resultFact().hash(),"decisionCode",code,"rationaleSummary","Supervisor checked current exact result");}
    protected AuthorizationService.Actor service(String authority)throws Exception {
        return service(authority,false);
    }
    protected AuthorizationService.Actor service(String authority,boolean expired)throws Exception {
        UUID principal=UUID.randomUUID(),appointment=UUID.randomUUID();
        mutate("insert into identity.principal (tenant_id,principal_id,principal_kind,identity_provider_code,external_subject_hmac,display_name,state,created_at) values (?,?,'SERVICE','FIXTURE',?,'Recovery service','ACTIVE',clock_timestamp())",seed.tenant(),principal,CanonicalJson.digest(principal.toString()));
        mutate("insert into identity.appointment (tenant_id,appointment_id,principal_id,organization_unit_id,role_code,effective_from,state,created_at) values (?,?,?,?,'RECOVERY',clock_timestamp()-interval '1 day','ACTIVE',clock_timestamp())",seed.tenant(),appointment,principal,seed.org());
        mutate("insert into identity.authority_grant (tenant_id,authority_grant_id,grantee_appointment_id,granted_by_appointment_id,scope_organization_unit_id,authority_code,valid_from,valid_until,state,created_at) values (?,?,?,?,?,?,clock_timestamp()-interval '1 day',"+(expired?"clock_timestamp()-interval '1 second'":"null")+",'ACTIVE',clock_timestamp())",seed.tenant(),UUID.randomUUID(),appointment,seed.appointment(),seed.org(),authority);
        return new AuthorizationService.Actor(seed.tenant(),principal,appointment,null,null,AuthorizationService.PrincipalKind.SERVICE);
    }
    protected CommandEnvelope recovery(AuthorizationService.Actor actor)throws Exception {
        R1EventFacts.Wait wait;
        try(var c=database.apiConnection()){wait=inTransaction(c,Capability.QUERY,x->EventResponsibilityReader.databaseBacked().latestWait(x,seed.tenant(),current.selector().id()));}
        var type=current.type()==TaskFactory.Type.CONTACT_LEAD?CommandEnvelope.Type.REOPEN_DUE_CONTACT_TASKS:CommandEnvelope.Type.REOPEN_DUE_ROUTING_REVIEW_TASKS;
        return new CommandEnvelope(type,UUID.randomUUID(),UUID.randomUUID(),actor,Map.of("taskId",current.selector().id().toString(),"expectedTaskRevision",current.selector().revision(),"waitReceiptId",wait.selector().id().toString(),"waitReceiptHash",wait.selector().hash(),"dueCutoff",wait.resumeDue().toString()));
    }
}
