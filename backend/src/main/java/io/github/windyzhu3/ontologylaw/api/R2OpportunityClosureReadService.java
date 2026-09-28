package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.lead.LeadProtection;
import io.github.windyzhu3.ontologylaw.opportunity.*;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import java.sql.*;
import java.util.*;

/** Exact terminal context. No customer/body decryption before the matching disclosure authority. */
public final class R2OpportunityClosureReadService {
    private final R2OpportunityLedgerReadService ledger;
    private final OpportunityClosureService closure;
    private final AuditAppender audit;
    private final OpportunityLedgerAuthorityReader authority=OpportunityLedgerAuthorityReader.databaseBacked();
    public R2OpportunityClosureReadService(byte[] key,LeadProtection leads,OpportunityProgressProtection protection,AuditAppender audit){this.ledger=new R2OpportunityLedgerReadService(key,leads,protection,audit);this.closure=io.github.windyzhu3.ontologylaw.api.R2OpportunityClosureServices.create(protection);this.audit=Objects.requireNonNull(audit);}
    public Map<String,Object> read(Connection connection,Actor actor,UUID id)throws SQLException {
        if(id==null)throw failure(400,"VALIDATION_FAILED");
        return OpportunityClosureReadRuntime.read(connection,actor,audit,(c,now)->{
            var x=ledger.context(c,actor,id);if(x==null)throw failure(403,"NOT_AUTHORIZED");
            var snapshot=closure.inspect(c,actor.tenantId(),id);
            if(snapshot==null||!snapshot.opportunity().equals(x.header().selector())||!snapshot.responsibility().equals(x.owner())||(!snapshot.downstream()&&(!Objects.equals(snapshot.task(),x.tasks().task())||!Objects.equals(snapshot.waitReceipt(),x.tasks().waitReceipt()))))throw failure(409,"STALE_SUBJECT");
            var disclosures=new ArrayList<AuditAppender.OpportunityClosureDisclosureEntry>();
            for(var f:x.facts())disclose(c,actor,x.organization(),f,x.code(),disclosures);
            var result=new LinkedHashMap<String,Object>();result.put("opportunity",Map.of("id",id.toString(),"revision",snapshot.opportunity().revision()));
            result.put("status","READ_ONLY");
            if(snapshot.closed()){
                result.put("status","CLOSED");var terminal=snapshot.closure();
                if(terminal!=null&&authority.permitted(c,actor,x.organization(),List.of(terminal.selector()),x.code())){
                    disclose(c,actor,x.organization(),terminal.selector(),x.code(),disclosures);
                    var summary=closure.summary(c,actor.tenantId(),terminal.selector());
                    result.put("closure",Map.of("reasonCode",terminal.reasonCode(),"summary",summary,"closedAt",terminal.closedAt().toString()));
                }
            }else if(snapshot.downstream()){
                // Do not reveal the existence of inaccessible downstream records.
                if(authority.permitted(c,actor,x.organization(),snapshot.downstreamFacts(),x.code())){
                    result.put("status","BLOCKED");for(var f:snapshot.downstreamFacts())disclose(c,actor,x.organization(),f,x.code(),disclosures);
                }
            }else{
                var facts=new LinkedHashSet<Subject>(R2OpportunityOwnerExceptionAssembly.protectedFacts(c,actor.tenantId(),snapshot.opportunity()));facts.addAll(x.facts());
                var owner=WorkcardOwnerReader.databaseBacked().read(c,actor.tenantId(),x.owner().appointmentId());
                if(owner!=null){facts.add(owner.appointment().selector());facts.add(owner.principal().selector());facts.add(owner.organization().selector());}
                if(snapshot.task()!=null){var task=AuthorizationTaskReader.databaseBacked().read(c,actor.tenantId(),snapshot.task().id());if(task==null||!snapshot.task().equals(task.selector()))throw failure(409,"STALE_SUBJECT");if(task.draft()!=null)facts.add(task.draft().selector());}
                boolean ready=owner!=null&&x.tasks().lineageValid()&&OpportunityOwnerExceptionAuthorityReader.databaseBacked().hasAuthority(c,actor,"OPPORTUNITY_CLOSE",now)&&authority.permitted(c,actor,x.organization(),List.copyOf(facts),"OPPORTUNITY_CLOSE");
                if(ready){result.put("status","READY");result.put("expectedResponsibility",selector(snapshot.responsibility().basis()));result.put("expectedTask",selector(snapshot.task()));result.put("expectedWait",selector(snapshot.waitReceipt()));for(var f:facts)disclose(c,actor,x.organization(),f,"OPPORTUNITY_CLOSE",disclosures);}
            }
            if(!x.equals(ledger.context(c,actor,id))||!snapshot.equals(closure.inspect(c,actor.tenantId(),id)))throw failure(409,"STALE_SUBJECT");
            for(var d:disclosures)if(!AuthorizationService.databaseBacked().evaluate(c,d.authorization().request(),true).allowed())throw failure(403,"NOT_AUTHORIZED");
            return new OpportunityClosureReadRuntime.Prepared<>(result,List.copyOf(disclosures));
        });
    }
    private void disclose(Connection c,Actor actor,UUID organization,Subject fact,String code,List<AuditAppender.OpportunityClosureDisclosureEntry> out)throws SQLException {var e=authority.evidence(c,actor,organization,fact,code);if(e==null||!e.allowed())throw failure(403,"NOT_AUTHORIZED");out.add(new AuditAppender.OpportunityClosureDisclosureEntry(UUID.randomUUID(),UUID.randomUUID(),fact,e));}
    private static Map<String,Object> selector(Subject s){return s==null?null:s.revision()!=null?Map.of("type",s.type(),"id",s.id().toString(),"revision",s.revision()):Map.of("type",s.type(),"id",s.id().toString(),"hash",s.hash());}
    private static R1ServiceReadRuntime.Failure failure(int status,String code){return new R1ServiceReadRuntime.Failure(status,code);}
}
