package io.github.windyzhu3.ontologylaw.opportunity.internal.persistence;

import io.github.windyzhu3.ontologylaw.opportunity.*;
import io.github.windyzhu3.ontologylaw.opportunity.FollowupAttemptService.*;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.security.MessageDigest;

public final class JdbcFollowupAttemptService implements FollowupAttemptService {
    private final OpportunityProgressProtection protection;private final QuoteDraftService.Codec codec;private final Ports tasks;
    public JdbcFollowupAttemptService(OpportunityProgressProtection protection,QuoteDraftService.Codec codec,Ports tasks){this.protection=protection;this.codec=codec;this.tasks=tasks;}
    private static void require(boolean value,String code){if(!value)throw new Blocked(code);}
    private static void bind(PreparedStatement p,Object... values)throws SQLException{for(int i=0;i<values.length;i++)p.setObject(i+1,values[i] instanceof Instant at?at.atOffset(ZoneOffset.UTC):values[i]);}
    private static Map<String,Object> row(Connection c,String sql,Object... values)throws SQLException{try(var p=c.prepareStatement(sql)){bind(p,values);try(var r=p.executeQuery()){if(!r.next())return null;var result=new LinkedHashMap<String,Object>();for(int i=1;i<=r.getMetaData().getColumnCount();i++)result.put(r.getMetaData().getColumnLabel(i),r.getObject(i));if(r.next())throw new SQLException("Ambiguous attempt source","23000");return result;}}}
    private static void write(Connection c,String sql,Object... values)throws SQLException{try(var p=c.prepareStatement(sql)){bind(p,values);p.executeUpdate();}}
    private static Instant time(Object value){return value instanceof OffsetDateTime t?t.toInstant():((Timestamp)value).toInstant();}
    private static UUID id(Object value){return (UUID)value;}
    private static String hash(byte[] bytes){return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    private static Subject selector(String type,UUID id,Long revision){return id==null?null:new Subject(type,id,revision,null);}
    private static Map<String,Object> selector(Subject s){return s==null?null:s.hash()==null?Map.of("type",s.type(),"id",s.id().toString(),"revision",s.revision()):Map.of("type",s.type(),"id",s.id().toString(),"hash",s.hash());}
    public Subject record(Connection c,Actor actor,Basis b,OpportunityFollowupAttempt input,boolean quote,ZoneId zone)throws SQLException {
        if(c.getAutoCommit()||c.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED)throw new SQLException("Attempt requires READ COMMITTED transaction","25001");
        require(actor.principalKind()==PrincipalKind.HUMAN&&actor.onBehalfAppointmentId()==null,"NOT_AUTHORIZED");
        var root=row(c,"select * from opportunity.opportunity where tenant_id=? and opportunity_id=? for update",actor.tenantId(),b.opportunity().id());
        AuthorizationService.databaseBacked().lockForEvaluation(c,actor.tenantId());
        require(root!=null&&b.opportunity().revision().equals(root.get("revision")),"STALE_SUBJECT");require(root.get("closed_at")==null,"OPPORTUNITY_CLOSED");
        require(!tasks.contractTakenOver(c,actor.tenantId(),b.opportunity().id()),"OPPORTUNITY_HAS_DOWNSTREAM_FACTS");
        var owner=OpportunityResponsibilityReader.databaseBacked().current(c,actor.tenantId(),b.opportunity());
        require(owner.appointmentId().equals(actor.appointmentId())&&owner.basis().equals(b.responsibility()),"NOT_AUTHORIZED");
        var task=tasks.read(c,actor.tenantId(),b.task().id());
        require(task!=null&&task.selector().equals(b.task())&&task.opportunity().equals(b.opportunity())&&task.responsibility().equals(b.responsibility())&&task.owner().equals(actor.appointmentId())&&Set.of("OPEN","WAITING").contains(task.state())&&task.purpose().equals(quote?"RECORD_QUOTE_REPLY":"PROGRESS_OPPORTUNITY")&&Objects.equals(task.waitReceipt(),b.waitReceipt()),"STALE_TASK");
        var w=row(c,"select w.* from opportunity.quote_workflow w where w.tenant_id=? and w.opportunity_id=? and not exists(select 1 from opportunity.quote_workflow n where n.tenant_id=w.tenant_id and n.previous_workflow_id=w.quote_workflow_id)",actor.tenantId(),b.opportunity().id());
        if(quote)require(w!=null&&b.quoteWorkflow()!=null&&b.quoteWorkflow().id().equals(w.get("quote_workflow_id"))&&Set.of("AWAIT_REPLY","FOLLOW_UP","CLARIFY_REPLY").contains(w.get("stage"))&&w.get("task_id")!=null&&tasks.current(c,actor.tenantId(),id(w.get("task_id")))!=null&&b.task().equals(tasks.current(c,actor.tenantId(),id(w.get("task_id"))).selector()),"STALE_SUBJECT");
        else require(w==null&&b.quoteWorkflow()==null,"OPPORTUNITY_HAS_DOWNSTREAM_FACTS");
        var authority=OpportunityOwnerExceptionAuthorityReader.databaseBacked();var org=authority.historicalOrganization(c,actor.tenantId(),id(root.get("owner_appointment_id")));
        var facts=new ArrayList<Subject>(List.of(b.opportunity(),b.responsibility(),b.task()));if(b.waitReceipt()!=null)facts.add(b.waitReceipt());if(b.quoteWorkflow()!=null)facts.add(b.quoteWorkflow());
        require(org!=null&&authority.permitted(c,actor,org,facts,quote?"QUOTE_RESPONSE":"SALES_OPPORTUNITY_OWNER"),"NOT_AUTHORIZED");
        Instant now=time(row(c,"select clock_timestamp() at").get("at"));input.validateAt(now,time(root.get("created_at")));
        UUID attempt=id(row(c,"select uuidv7() id").get("id")),nextWorkflow=quote?id(row(c,"select uuidv7() id").get("id")):null;
        var body=new LinkedHashMap<String,Object>();body.put("profile","R2_FOLLOWUP_ATTEMPT_V1");body.put("tenantId",actor.tenantId().toString());body.put("attemptId",attempt.toString());body.put("opportunity",selector(b.opportunity()));body.put("responsibility",selector(b.responsibility()));body.put("task",selector(b.task()));body.put("wait",selector(b.waitReceipt()));body.put("quoteWorkflow",selector(b.quoteWorkflow()));body.put("context",quote?"QUOTE":"OPPORTUNITY");body.put("actor",actor.appointmentId().toString());body.put("recordedAt",now.toString());body.put("values",input.values());
        String canonical=codec.encode(body);byte[] digest=QuoteCanonicalJson.digest(canonical);var fact=new Subject(FACT,attempt,null,hash(digest));
        var next=tasks.arrange(c,actor.tenantId(),task,fact,zone,input.nextCheckAt(),now);
        if(quote)write(c,"insert into opportunity.quote_workflow(tenant_id,quote_workflow_id,revision,opportunity_id,quote_revision_id,previous_workflow_id,stage,owner_appointment_id,task_id,prior_task_id,next_check_at,created_at) values(?,?,0,?,?,?,'AWAIT_REPLY',?,?,?,?,?)",actor.tenantId(),nextWorkflow,b.opportunity().id(),w.get("quote_revision_id"),b.quoteWorkflow().id(),actor.appointmentId(),next.id(),b.task().id(),input.nextCheckAt(),now);
        write(c,"insert into opportunity.followup_attempt(tenant_id,followup_attempt_id,revision,opportunity_id,opportunity_revision,responsibility_type,responsibility_id,responsibility_revision,context_code,attempt_code,prior_task_id,prior_task_revision,prior_wait_id,prior_wait_hash,task_id,quote_workflow_id,next_quote_workflow_id,recorded_by,body_ciphertext,body_digest,occurred_at,next_check_at,created_at) values(?,?,0,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",actor.tenantId(),attempt,b.opportunity().id(),b.opportunity().revision(),b.responsibility().type(),b.responsibility().id(),b.responsibility().revision(),quote?"QUOTE":"OPPORTUNITY",input.type(),b.task().id(),b.task().revision(),b.waitReceipt()==null?null:b.waitReceipt().id(),b.waitReceipt()==null?null:Base64.getUrlDecoder().decode(b.waitReceipt().hash()),next.id(),b.quoteWorkflow()==null?null:b.quoteWorkflow().id(),nextWorkflow,actor.appointmentId(),protection.encryptAttempt(actor.tenantId(),b.opportunity().id(),attempt,canonical),digest,input.occurredAt(),input.nextCheckAt(),now);
        facts.add(fact);facts.add(next);require(authority.permitted(c,actor,org,facts,quote?"QUOTE_RESPONSE":"SALES_OPPORTUNITY_OWNER"),"NOT_AUTHORIZED");return fact;
    }
    public Metadata metadata(Connection c,UUID tenant,Subject exact)throws SQLException{return readMetadata(c,tenant,exact);}
    public QuoteBasis quoteBasis(Connection c,UUID tenant,UUID opportunity)throws SQLException {
        var w=row(c,"select w.* from opportunity.quote_workflow w where w.tenant_id=? and w.opportunity_id=? and not exists(select 1 from opportunity.quote_workflow n where n.tenant_id=w.tenant_id and n.previous_workflow_id=w.quote_workflow_id)",tenant,opportunity);
        if(w==null)return null;var task=w.get("task_id")==null?null:tasks.current(c,tenant,id(w.get("task_id")));
        return new QuoteBasis(selector("opportunity.quote_workflow",id(w.get("quote_workflow_id")),0L),(String)w.get("stage"),task==null?null:task.selector());
    }
    public static Metadata readRecovery(Connection c,UUID tenant,Subject exact)throws SQLException {
        var m=readMetadata(c,tenant,exact);if(m==null)return null;
        var root=row(c,"select revision,closed_at,current_quote_revision_id from opportunity.opportunity where tenant_id=? and opportunity_id=?",tenant,m.basis().opportunity().id());
        if(root==null||root.get("closed_at")!=null||!m.basis().opportunity().revision().equals(root.get("revision")))return null;
        var w=row(c,"select w.* from opportunity.quote_workflow w where w.tenant_id=? and w.opportunity_id=? and not exists(select 1 from opportunity.quote_workflow n where n.tenant_id=w.tenant_id and n.previous_workflow_id=w.quote_workflow_id)",tenant,m.basis().opportunity().id());
        if("QUOTE".equals(m.context())){
            if(w==null||!m.nextWorkflow().equals(w.get("quote_workflow_id"))||!m.nextTask().equals(w.get("task_id"))||!"AWAIT_REPLY".equals(w.get("stage"))||!Objects.equals(root.get("current_quote_revision_id"),w.get("quote_revision_id"))||!m.nextCheckAt().equals(time(w.get("next_check_at"))))return null;
        }else if(w!=null)return null;
        return m;
    }
    public static Metadata readMetadata(Connection c,UUID tenant,Subject exact)throws SQLException {
        if(exact==null||!FACT.equals(exact.type())||exact.hash()==null)return null;
        var r=row(c,"select a.*,created_in_transaction=pg_current_xact_id() in_tx from opportunity.followup_attempt a where tenant_id=? and followup_attempt_id=?",tenant,exact.id());
        if(r==null||!MessageDigest.isEqual((byte[])r.get("body_digest"),Base64.getUrlDecoder().decode(exact.hash())))return null;
        var basis=new Basis(selector("opportunity.opportunity",id(r.get("opportunity_id")),(Long)r.get("opportunity_revision")),selector((String)r.get("responsibility_type"),id(r.get("responsibility_id")),(Long)r.get("responsibility_revision")),selector("responsibility.task_occurrence",id(r.get("prior_task_id")),(Long)r.get("prior_task_revision")),r.get("prior_wait_id")==null?null:new Subject("responsibility.wait_receipt",id(r.get("prior_wait_id")),null,hash((byte[])r.get("prior_wait_hash"))),selector("opportunity.quote_workflow",id(r.get("quote_workflow_id")),0L));
        return new Metadata(exact,basis,id(r.get("recorded_by")),id(r.get("task_id")),id(r.get("next_quote_workflow_id")),(String)r.get("context_code"),(String)r.get("attempt_code"),time(r.get("occurred_at")),time(r.get("next_check_at")),time(r.get("created_at")),Boolean.TRUE.equals(r.get("in_tx")));
    }
    public List<Metadata> history(Connection c,UUID tenant,UUID opportunity)throws SQLException {
        var out=new ArrayList<Metadata>();try(var p=c.prepareStatement("select followup_attempt_id,body_digest from opportunity.followup_attempt where tenant_id=? and opportunity_id=? order by created_at,followup_attempt_id")){bind(p,tenant,opportunity);try(var r=p.executeQuery()){while(r.next())out.add(readMetadata(c,tenant,new Subject(FACT,r.getObject(1,UUID.class),null,hash(r.getBytes(2)))));}}return List.copyOf(out);
    }
    public String summary(Connection c,UUID tenant,Metadata fact)throws SQLException {
        var r=row(c,"select body_ciphertext,body_digest from opportunity.followup_attempt where tenant_id=? and followup_attempt_id=?",tenant,fact.selector().id());if(r==null)return null;
        String clear=protection.decryptAttempt(tenant,fact.basis().opportunity().id(),fact.selector().id(),(byte[])r.get("body_ciphertext"));if(!MessageDigest.isEqual(QuoteCanonicalJson.digest(clear),(byte[])r.get("body_digest")))throw new SQLException("Attempt integrity failure","22000");
        return (String)((Map<?,?>)codec.decode(clear).get("values")).get("summary");
    }
}
