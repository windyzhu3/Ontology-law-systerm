package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService;
import io.github.windyzhu3.ontologylaw.identity.OpportunityLedgerAuthorityReader;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import io.github.windyzhu3.ontologylaw.lead.*;
import io.github.windyzhu3.ontologylaw.opportunity.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static io.github.windyzhu3.ontologylaw.api.R25AiCandidateContract.*;

/** Server-selected input only; typed disclosure commits before any caller sees text. */
public final class R25AiSourceReadService {
    public record Snapshot(String fingerprint,List<Source> sources) {
        public Snapshot {if(fingerprint==null||!fingerprint.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Source fingerprint required");sources=List.copyOf(sources);validateSources(sources);}
        @Override public String toString(){return "AiSourceSnapshot[protected]";}
    }
    private final R2OpportunityLedgerReadService ledger;
    private final CurrentLeadReader leads;
    private final OpportunityLedgerReader progress;
    private final OpportunityProgressProtection protection;
    private final AuditAppender audit;
    private final OpportunityLedgerAuthorityReader authority=OpportunityLedgerAuthorityReader.databaseBacked();
    public R25AiSourceReadService(byte[] key,LeadProtection leads,OpportunityProgressProtection progress,AuditAppender audit){
        ledger=new R2OpportunityLedgerReadService(key,leads,progress,audit);this.leads=CurrentLeadReader.databaseBacked(leads);this.progress=OpportunityLedgerReader.databaseBacked(progress);protection=progress;this.audit=audit;
    }
    public Snapshot read(Connection connection,Actor actor,UUID opportunity,Task task)throws SQLException {
        Objects.requireNonNull(opportunity);Objects.requireNonNull(task);
        if(task==Task.MATERIALS)return materials(connection,actor,opportunity);
        return OpportunityLedgerReadRuntime.read(connection,actor,audit,(c,now)->{
            var x=context(c,actor,opportunity);var disclosures=new ArrayList<AuditAppender.OpportunityLedgerDisclosureEntry>();
            for(var fact:x.facts())disclose(c,actor,x,fact,disclosures);
            var sources=new ArrayList<Source>();var refs=new ArrayList<Subject>(x.facts());
            if(task==Task.FIELDS) {
                var lead=leads.read(c,actor.tenantId(),x.lead().id());if(lead==null||!x.lead().equals(lead.selector()))throw failure(412,"STALE_SUBJECT");
                String text="客户名称："+text(lead.customerName())+"\n原录入名称："+text(lead.capturedName())+"\n联系人："+text(lead.contactName())+"\n原录入电话："+text(lead.capturedPhone())+"\n需求原文："+text(lead.legalNeedSummary());
                sources.add(new Source("s1","当前线索原文","TEXT",text));
            } else {
                var rows=progress.progressHistory(c,actor.tenantId(),opportunity,51);if(rows.size()>50)throw new Failure("AI_INPUT_TOO_LARGE");
                for(var row:rows) {
                    if(!authority.permitted(c,actor,x.organization(),List.of(row.selector()),x.code()))continue;
                    disclose(c,actor,x,row.selector(),disclosures);
                    var body=JSON.readTree(progress.progressBody(c,actor.tenantId(),opportunity,row.selector()));var values=body.path("values");
                    if(!OpportunityProgressInput.CONTRACT.equals(body.path("profile").asText())||!actor.tenantId().toString().equals(body.path("tenantId").asText())||!opportunity.toString().equals(body.path("opportunityId").asText())||!row.selector().id().toString().equals(body.path("progressId").asText())||!values.path("summary").isString()||!row.occurredAt().equals(Instant.parse(values.path("occurredAt").asText())))throw new SQLException("Invalid confirmed progress source","22000");
                    String summary=values.path("summary").asText();validText(summary,2000);refs.add(row.selector());
                    sources.add(new Source("s"+(sources.size()+1),"已确认跟进 · "+row.occurredAt(),"PROGRESS","发生时间："+row.occurredAt()+"\n进展原文："+summary));
                }
            }
            var result=snapshot(task,x,refs,sources);
            for(var d:disclosures)if(!AuthorizationService.databaseBacked().evaluate(c,d.authorization().request(),true).allowed())throw failure(403,"NOT_AUTHORIZED");
            return new OpportunityLedgerReadRuntime.Prepared<>(result,List.copyOf(disclosures));
        });
    }
    private Snapshot materials(Connection connection,Actor actor,UUID opportunity)throws SQLException {
        return MaterialReadRuntime.read(connection,actor,audit,(c,now)->{
            var x=context(c,actor,opportunity);var base=R2MaterialsServices.facts(c,actor.tenantId(),x.header().selector(),null);
            String code=actor.appointmentId().equals(x.owner().appointmentId())&&authority.permitted(c,actor,x.organization(),base,"MATERIALS_MANAGE")?"MATERIALS_MANAGE":authority.permitted(c,actor,x.organization(),base,"MATERIALS_READ")?"MATERIALS_READ":null;
            if(code==null)throw failure(403,"NOT_AUTHORIZED");
            var disclosures=new ArrayList<AuditAppender.MaterialDisclosureEntry>();var refs=new ArrayList<Subject>();
            discloseMaterials(c,actor,x,base,code,disclosures);refs.addAll(base);
            var sources=new ArrayList<Source>(List.of(
                new Source("rule1","合同及业务资料","RULE","T06 销售准备参考 CONTRACT_BUSINESS：合同及业务资料。仅核对当前有权材料清单；未找到不代表全量不存在，不改变报价、签约或转案准入。"),
                new Source("rule2","相关往来记录","RULE","T06 销售准备参考 CORRESPONDENCE：相关往来记录。仅核对当前有权材料清单；未找到不代表全量不存在，不改变报价、签约或转案准入。")));
            var rows=OpportunityMaterials.databaseBacked().currentVersions(c,actor.tenantId(),opportunity,49);if(rows.size()>48)throw new Failure("AI_INPUT_TOO_LARGE");
            var evidence=R2MaterialsServices.evidence(protection);
            for(var row:rows) {
                var facts=R2MaterialsServices.facts(c,actor.tenantId(),x.header().selector(),row.selector());
                if(facts==null||!authority.permitted(c,actor,x.organization(),facts,code))continue;
                discloseMaterials(c,actor,x,facts,code,disclosures);refs.addAll(facts);
                var basis=evidence.basis(c,actor.tenantId(),row.upload());var body=evidence.body(c,actor.tenantId(),basis);
                sources.add(new Source("s"+(sources.size()-1),"已接收材料 · "+row.receivedAt(),"MATERIAL","用途："+row.purpose()+"\n文件名称："+text((String)body.get("fileName"))+"\n材料说明："+text((String)body.get("note"))+"\n仅为已接收版本的说明，未解析文件正文。"));
            }
            return new MaterialReadRuntime.Prepared<>(snapshot(Task.MATERIALS,x,refs,sources),List.copyOf(disclosures));
        });
    }
    private R2OpportunityLedgerReadService.Context context(Connection c,Actor actor,UUID id)throws SQLException{var x=ledger.context(c,actor,id);if(x==null)throw failure(403,"NOT_AUTHORIZED");return x;}
    private void disclose(Connection c,Actor actor,R2OpportunityLedgerReadService.Context x,Subject source,List<AuditAppender.OpportunityLedgerDisclosureEntry> disclosures)throws SQLException {
        var decision=authority.evidence(c,actor,x.organization(),source,x.code());if(decision==null||!decision.allowed())throw failure(403,"NOT_AUTHORIZED");
        disclosures.add(new AuditAppender.OpportunityLedgerDisclosureEntry(UUID.randomUUID(),UUID.randomUUID(),source,decision));
    }
    private void discloseMaterials(Connection c,Actor actor,R2OpportunityLedgerReadService.Context x,List<Subject> facts,String code,List<AuditAppender.MaterialDisclosureEntry> disclosures)throws SQLException {
        for(var source:facts){var decision=authority.evidence(c,actor,x.organization(),source,code);if(decision==null||!decision.allowed())throw failure(403,"NOT_AUTHORIZED");disclosures.add(new AuditAppender.MaterialDisclosureEntry(UUID.randomUUID(),UUID.randomUUID(),source,decision,"BODY"));}
    }
    private static Snapshot snapshot(Task task,R2OpportunityLedgerReadService.Context x,List<Subject> refs,List<Source> sources) {
        validateSources(sources);
        var basis=Map.of("contract","R25_AI_CANDIDATES_V1","task",task.name(),"opportunity",reference(x.header().selector()),"responsibility",reference(x.owner().basis()),"owner",x.owner().appointmentId().toString(),"closed",x.header().closed(),"facts",refs.stream().distinct().map(R25AiSourceReadService::reference).toList(),"sources",sources.stream().map(s->Map.of("id",s.id(),"label",s.label(),"kind",s.kind(),"text",s.text())).toList());
        return new Snapshot(HexFormat.of().formatHex(CanonicalJson.digest(CanonicalJson.encode(basis))),sources);
    }
    private static Map<String,Object> reference(Subject source){return source.revision()!=null?Map.of("type",source.type(),"id",source.id().toString(),"revision",source.revision()):Map.of("type",source.type(),"id",source.id().toString(),"hash",source.hash());}
    private static String text(String value){return value==null||value.isBlank()?"未提供":value;}
    private static R1ServiceReadRuntime.Failure failure(int status,String code){return new R1ServiceReadRuntime.Failure(status,code);}
}
