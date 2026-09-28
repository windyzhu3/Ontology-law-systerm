package io.github.windyzhu3.ontologylaw.opportunity.internal.persistence;

import io.github.windyzhu3.ontologylaw.opportunity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.security.MessageDigest;

public final class JooqOpportunityClosureService implements OpportunityClosureService {
    private final OpportunityProgressProtection protection;private final Tasks tasks;private final Downstream downstreamReader;
    public JooqOpportunityClosureService(OpportunityProgressProtection protection,Tasks tasks,Downstream downstream){this.protection=Objects.requireNonNull(protection);this.tasks=Objects.requireNonNull(tasks);this.downstreamReader=Objects.requireNonNull(downstream);}
    public Snapshot inspect(Connection c,UUID tenant,UUID id)throws SQLException{
        var header=OpportunityCommandReader.databaseBacked(protection).header(c,tenant,id);if(header==null)return null;
        var responsibility=OpportunityResponsibilityReader.databaseBacked().current(c,tenant,header.selector());
        Closure closure=null;try(var p=c.prepareStatement("select * from opportunity.closure where tenant_id=? and opportunity_id=?")){p.setObject(1,tenant);p.setObject(2,id);try(var r=p.executeQuery()){if(r.next())closure=row(r);}}
        var downstream=new ArrayList<Subject>();try(var p=c.prepareStatement("select quote_revision_id,content_digest from opportunity.quote_revision where tenant_id=? and opportunity_id=? order by quote_revision_id limit 1")){p.setObject(1,tenant);p.setObject(2,id);try(var r=p.executeQuery()){while(r.next())if(!quoteDispositionClosable(c,tenant,id))downstream.add(new Subject("opportunity.quote_revision",r.getObject(1,UUID.class),null,hash(r.getBytes(2))));}}
        try(var p=c.prepareStatement("select quote_workflow_id from opportunity.quote_workflow where tenant_id=? and opportunity_id=? and quote_revision_id is null and not exists(select 1 from opportunity.quote_revision q where q.tenant_id=? and q.opportunity_id=?)")){p.setObject(1,tenant);p.setObject(2,id);p.setObject(3,tenant);p.setObject(4,id);try(var r=p.executeQuery()){while(r.next())downstream.add(new Subject("opportunity.quote_workflow",r.getObject(1,UUID.class),0L,null));}}
        downstream.addAll(downstreamReader.read(c,tenant,id));
        // Downstream Owners may have several independent tasks; they cannot be closed here.
        var active=downstream.isEmpty()?tasks.active(c,tenant,header.selector()):null;
        if(active!=null&&(!active.basis().equals(responsibility.basis())||!active.owner().equals(responsibility.appointmentId())))throw new Blocked("STALE_TASK");
        return new Snapshot(header.selector(),responsibility,active==null?null:active.selector(),active==null?null:active.waitReceipt(),active==null?null:active.state(),header.closed(),downstream,closure);
    }
    private boolean quoteDispositionClosable(Connection c,UUID tenant,UUID opportunity)throws SQLException{
        try(var p=c.prepareStatement("select exists(select 1 from opportunity.quote_workflow w join opportunity.quote_revision q on q.tenant_id=w.tenant_id and q.quote_revision_id=w.quote_revision_id where w.tenant_id=? and w.opportunity_id=? and not exists(select 1 from opportunity.quote_workflow n where n.tenant_id=w.tenant_id and n.previous_workflow_id=w.quote_workflow_id) and (w.stage='SALES_DISPOSITION' or (w.stage in ('DELIVER','AWAIT_REPLY') and q.valid_until<=clock_timestamp() and not exists(select 1 from opportunity.quote_response r join opportunity.quote_issue i on i.tenant_id=r.tenant_id and i.quote_issue_id=r.quote_issue_id where i.tenant_id=q.tenant_id and i.quote_revision_id=q.quote_revision_id))) and not exists(select 1 from opportunity.quote_response r join opportunity.quote_issue i on i.tenant_id=r.tenant_id and i.quote_issue_id=r.quote_issue_id join opportunity.quote_revision accepted on accepted.tenant_id=i.tenant_id and accepted.quote_revision_id=i.quote_revision_id where accepted.tenant_id=w.tenant_id and accepted.opportunity_id=w.opportunity_id and r.response_code='ACCEPTED'))")){p.setObject(1,tenant);p.setObject(2,opportunity);try(var r=p.executeQuery()){r.next();return r.getBoolean(1);}}
    }
    public Closure read(Connection c,UUID tenant,Subject exact)throws SQLException{
        return readMetadata(c,tenant,exact);
    }
    public static Closure readMetadata(Connection c,UUID tenant,Subject exact)throws SQLException{
        if(exact==null||!"opportunity.closure".equals(exact.type())||!Long.valueOf(0).equals(exact.revision()))return null;
        try(var p=c.prepareStatement("select * from opportunity.closure where tenant_id=? and closure_id=?")){p.setObject(1,tenant);p.setObject(2,exact.id());try(var r=p.executeQuery()){return r.next()?row(r):null;}}
    }
    public static Closure currentMetadata(Connection c,UUID tenant,UUID opportunity)throws SQLException{
        try(var p=c.prepareStatement("select * from opportunity.closure where tenant_id=? and opportunity_id=?")){p.setObject(1,tenant);p.setObject(2,opportunity);try(var r=p.executeQuery()){return r.next()?row(r):null;}}
    }
    public String summary(Connection c,UUID tenant,Subject exact)throws SQLException{
        var closure=read(c,tenant,exact);if(closure==null)return null;
        try(var p=c.prepareStatement("select closure_summary_ciphertext,summary_digest from opportunity.closure where tenant_id=? and closure_id=?")){p.setObject(1,tenant);p.setObject(2,exact.id());try(var r=p.executeQuery()){if(!r.next())return null;
            String value;try{value=protection.decryptClosure(tenant,closure.opportunity().id(),exact.id(),r.getBytes(1));}catch(IllegalArgumentException failure){throw new SQLException("Invalid protected closure","22000");}
            if(!MessageDigest.isEqual(digest(value),r.getBytes(2)))throw new SQLException("Invalid protected closure digest","22000");return value;
        }}
    }
    public Closure close(Connection c,UUID tenant,Input in)throws SQLException{
        if(c.getAutoCommit()||c.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED)throw new SQLException("Closure requires READ COMMITTED transaction","25001");
        if(in==null||in.summary()==null||!in.summary().equals(in.summary().strip())||in.summary().isBlank()||in.summary().codePointCount(0,in.summary().length())>1000||!Set.of("CLIENT_DECLINED","NEED_CANCELLED","OTHER").contains(in.reasonCode())||in.actor()==null)throw new Blocked("VALIDATION_FAILED");
        OpportunityCommandReader.databaseBacked(protection).lock(c,tenant,in.opportunity().id());var s=inspect(c,tenant,in.opportunity().id());
        require(s!=null,"NOT_FOUND");require(s.opportunity().equals(in.opportunity()),"STALE_SUBJECT");require(!s.closed(),"OPPORTUNITY_CLOSED");require(!s.downstream(),"OPPORTUNITY_HAS_DOWNSTREAM_FACTS");
        require(s.responsibility().basis().equals(in.responsibility()),"STALE_SUBJECT");require(Objects.equals(s.task(),in.task())&&Objects.equals(s.waitReceipt(),in.waitReceipt()),"STALE_TASK");
        if(in.opportunity().revision()>=9007199254740991L)throw new SQLException("Revision cannot be safely incremented","22003");Instant at;UUID id;
        try(var p=c.prepareStatement("select uuidv7(),clock_timestamp()")){try(var r=p.executeQuery()){r.next();id=r.getObject(1,UUID.class);at=r.getObject(2,OffsetDateTime.class).toInstant();}}
        var selector=new Subject("opportunity.closure",id,0L,null);byte[] ciphertext=protection.encryptClosure(tenant,in.opportunity().id(),id,in.summary());
        try(var p=c.prepareStatement("insert into opportunity.closure (tenant_id,closure_id,revision,opportunity_id,opportunity_revision,responsibility_type,responsibility_id,responsibility_revision,task_occurrence_id,task_revision,wait_receipt_id,wait_hash,closed_by_appointment_id,reason_code,closure_summary_ciphertext,summary_digest,closed_at) values (?,?,0,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")){
            Object[] values={tenant,id,in.opportunity().id(),in.opportunity().revision(),in.responsibility().type(),in.responsibility().id(),in.responsibility().revision(),in.task()==null?null:in.task().id(),in.task()==null?null:in.task().revision(),in.waitReceipt()==null?null:in.waitReceipt().id(),in.waitReceipt()==null?null:Base64.getUrlDecoder().decode(in.waitReceipt().hash()),in.actor(),in.reasonCode(),ciphertext,digest(in.summary()),at.atOffset(ZoneOffset.UTC)};
            for(int i=0;i<values.length;i++)p.setObject(i+1,values[i]);p.executeUpdate();
        }
        tasks.cancel(c,tenant,in.opportunity(),in.responsibility(),s.responsibility().appointmentId(),in.task(),in.waitReceipt(),selector,at);
        try(var p=c.prepareStatement("update opportunity.opportunity set close_outcome_code=?,closed_at=?,revision=revision+1 where tenant_id=? and opportunity_id=? and revision=? and closed_at is null")){p.setString(1,in.reasonCode());p.setObject(2,at.atOffset(ZoneOffset.UTC));p.setObject(3,tenant);p.setObject(4,in.opportunity().id());p.setLong(5,in.opportunity().revision());require(p.executeUpdate()==1,"STALE_SUBJECT");}
        return read(c,tenant,selector);
    }
    private static Closure row(ResultSet r)throws SQLException{
        UUID task=r.getObject("task_occurrence_id",UUID.class),wait=r.getObject("wait_receipt_id",UUID.class);
        return new Closure(new Subject("opportunity.closure",r.getObject("closure_id",UUID.class),0L,null),new Subject("opportunity.opportunity",r.getObject("opportunity_id",UUID.class),r.getLong("opportunity_revision"),null),new Subject(r.getString("responsibility_type"),r.getObject("responsibility_id",UUID.class),r.getLong("responsibility_revision"),null),task==null?null:new Subject("responsibility.task_occurrence",task,r.getLong("task_revision"),null),wait==null?null:new Subject("responsibility.wait_receipt",wait,null,hash(r.getBytes("wait_hash"))),r.getObject("closed_by_appointment_id",UUID.class),r.getString("reason_code"),r.getObject("closed_at",OffsetDateTime.class).toInstant());
    }
    private static String hash(byte[] bytes){return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    private static void require(boolean value,String code){if(!value)throw new Blocked(code);}
    private static byte[] digest(String text){try{return MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
}
