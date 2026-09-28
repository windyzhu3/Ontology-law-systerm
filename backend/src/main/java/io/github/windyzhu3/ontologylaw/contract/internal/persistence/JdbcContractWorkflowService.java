package io.github.windyzhu3.ontologylaw.contract.internal.persistence;

import io.github.windyzhu3.ontologylaw.contract.*;
import io.github.windyzhu3.ontologylaw.contract.ContractWorkflowService.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.security.MessageDigest;

/** Contract Owner orchestration. Every transition uses the caller's authorized transaction. */
public final class JdbcContractWorkflowService implements ContractWorkflowService {
    private record RowKey(String query,List<Object> arguments) {}
    private record LedgerFacts(Connection connection,UUID tenant,Map<UUID,List<Subject>> facts,Map<RowKey,List<Map<String,Object>>> rows,Map<UUID,List<UUID>> groups) {}
    private static final ThreadLocal<LedgerFacts> ledgerFacts=new ThreadLocal<>();
    public ReadScope lockedLedgerFacts(Connection c,UUID tenant)throws SQLException {
        if(c.getAutoCommit()||c.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED||ledgerFacts.get()!=null)throw new SQLException("Fenced query scope required","25001");
        ledgerFacts.set(new LedgerFacts(c,tenant,new HashMap<>(),new HashMap<>(),new HashMap<>()));return ()->ledgerFacts.remove();
    }
    public void prepareLedgerCandidates(Connection c,UUID tenant,List<UUID> candidates,int batchSize)throws SQLException{
        var scope=ledgerFacts.get();if(scope==null||scope.connection()!=c||!scope.tenant().equals(tenant)||c.getAutoCommit())throw new SQLException("Exact fenced Owner scope required","25001");
        if(candidates.size()>100||new HashSet<>(candidates).size()!=candidates.size()||batchSize<1||batchSize>20)throw new IllegalArgumentException("Bounded unique candidates required");
        for(int i=0;i<candidates.size();i+=batchSize){var group=List.copyOf(candidates.subList(i,Math.min(i+batchSize,candidates.size())));for(var id:group)scope.groups().put(id,group);}
    }
    private void requireCommandScope()throws SQLException {if(ledgerFacts.get()!=null)throw new SQLException("Command in ledger read scope","25001");}
    private final ContractProtection protection;
    private final ContractPreparationSources sources;
    private final ContractPreparationRepository.Codec codec;
    private final Ports ports;
    private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    public JdbcContractWorkflowService(ContractProtection p,ContractPreparationSources s,ContractPreparationRepository.Codec c,Ports ports){protection=Objects.requireNonNull(p);sources=Objects.requireNonNull(s);codec=Objects.requireNonNull(c);this.ports=Objects.requireNonNull(ports);}
    private JdbcContractExecutionWorkflow executions(){return new JdbcContractExecutionWorkflow(this,ports,protection,codec);}
    private JdbcContractNegotiation negotiation(){return new JdbcContractNegotiation(ports,protection,codec);}
    private JdbcManualSignatureWorkflow signatures(){return new JdbcManualSignatureWorkflow(this,ports,protection,codec);}
    private ContractPreparationRepository drafts(){return ContractPreparationRepository.databaseBacked(protection,sources,codec);}
    private ContractVersionRepository versions(){return ContractVersionRepository.databaseBacked(protection,sources,codec);}
    static List<Map<String,Object>> rows(Connection c,String query,Object...args)throws SQLException {
        // Reuse only business rows inside the caller's fenced read, never permission
        // decisions or clock-dependent queries. Bound memory even with long histories.
        var scope=ledgerFacts.get();String normalized=query.toLowerCase(Locale.ROOT);
        boolean reuse=scope!=null&&scope.connection()==c&&args.length>0&&scope.tenant().equals(args[0])
                &&normalized.startsWith("select ")&&!normalized.contains("clock_timestamp")&&!normalized.contains("now()")&&!normalized.contains("current_timestamp")&&!normalized.contains("for update")&&!normalized.contains("for share");
        var key=reuse?new RowKey(query,Collections.unmodifiableList(new ArrayList<>(Arrays.asList(args)))):null;
        if(reuse&&scope.rows().containsKey(key))return scope.rows().get(key);
        if(reuse&&args.length>=2&&args[1] instanceof UUID candidate&&scope.groups().containsKey(candidate)&&repeatedPair(args,scope.tenant(),candidate)){
            var group=scope.groups().get(candidate);var missing=group.stream().filter(id->!scope.rows().containsKey(new RowKey(query,pairArguments(scope.tenant(),id,args.length)))).toList();
            if(missing.size()>1){
                var batchResults=new HashMap<UUID,List<Map<String,Object>>>();
                try(var p=c.prepareStatement(String.join(";",Collections.nCopies(missing.size(),query)))){
                    int n=1;for(var id:missing)for(var value:pairArguments(scope.tenant(),id,args.length))p.setObject(n++,value);
                    boolean result=p.execute();
                    for(int i=0;i<missing.size();i++){
                        if(!result)throw new SQLException("Missing bounded Owner result","22000");
                        try(var r=p.getResultSet()){var values=new ArrayList<Map<String,Object>>();while(r.next()){var row=new LinkedHashMap<String,Object>();for(int k=1;k<=r.getMetaData().getColumnCount();k++)row.put(r.getMetaData().getColumnLabel(k),r.getObject(k));values.add(Collections.unmodifiableMap(row));}batchResults.put(missing.get(i),List.copyOf(values));}
                        result=p.getMoreResults(Statement.CLOSE_CURRENT_RESULT);
                    }
                    if(result||p.getUpdateCount()!=-1)throw new SQLException("Unexpected bounded Owner result","22000");
                }
                registerRelatedGroups(scope.groups(),batchResults.values(),missing.size());
                batchResults.forEach((id,rows)->{if(scope.rows().size()<4096)scope.rows().put(new RowKey(query,pairArguments(scope.tenant(),id,args.length)),rows);});
                return batchResults.get(candidate);
            }
        }
        try(var p=c.prepareStatement(query)){bind(p,args);try(var r=p.executeQuery()){var list=new ArrayList<Map<String,Object>>();while(r.next()){var m=new LinkedHashMap<String,Object>();for(int i=1;i<=r.getMetaData().getColumnCount();i++)m.put(r.getMetaData().getColumnLabel(i),r.getObject(i));list.add(reuse?Collections.unmodifiableMap(m):m);}var result=reuse?List.copyOf(list):list;if(reuse&&scope.rows().size()<4096)scope.rows().put(key,result);return result;}}
    }
    private static boolean repeatedPair(Object[] args,UUID tenant,UUID candidate){
        if(args.length%2!=0)return false;
        for(int i=0;i<args.length;i+=2)if(!tenant.equals(args[i])||!candidate.equals(args[i+1]))return false;
        return true;
    }
    private static List<Object> pairArguments(UUID tenant,UUID id,int count){
        var result=new ArrayList<Object>(count);for(int i=0;i<count;i+=2){result.add(tenant);result.add(id);}return List.copyOf(result);
    }
    /** Discover only identifiers already returned by this bounded Owner page. Each
     * follow-up still executes its original SQL with each exact tenant/identifier pair. */
    private static void registerRelatedGroups(Map<UUID,List<UUID>> groups,Collection<List<Map<String,Object>>> batches,int maximum){
        var columns=new LinkedHashMap<String,Set<UUID>>();
        for(var batch:batches)for(var row:batch)for(var field:row.entrySet())
            if(!field.getKey().equals("tenant_id")&&field.getValue() instanceof UUID id)
                columns.computeIfAbsent(field.getKey(),ignored->new LinkedHashSet<>()).add(id);
        for(var ids:columns.values()){
            var ordered=List.copyOf(ids);for(int offset=0;offset<ordered.size();offset+=maximum){
                var group=List.copyOf(ordered.subList(offset,Math.min(offset+maximum,ordered.size())));
                for(var id:group)if(groups.size()<4096)groups.putIfAbsent(id,group);
            }
        }
    }
    static Map<String,Object> row(Connection c,String query,Object...args)throws SQLException{var list=rows(c,query,args);return list.isEmpty()?null:list.getFirst();}
    /** Same exact references and tenant predicate, fetched in one round trip. Tables are internal constants. */
    static List<Subject> referenceFacts(Connection c,UUID tenant,String column,UUID id,List<String> tables)throws SQLException {
        if(!Set.of("opportunity_id","contract_revision_id").contains(column)||tables.isEmpty()||tables.stream().anyMatch(t->!t.matches("[a-z_]+")))throw new IllegalArgumentException("Internal reference tables required");
        var queries=new ArrayList<String>();var args=new ArrayList<Object>();
        for(var table:tables){queries.add("select 'contract."+table+"' fact_type,"+table+"_id id from contract."+table+" where tenant_id=? and "+column+"=?");args.add(tenant);args.add(id);}
        return rows(c,String.join(" union all ",queries),args.toArray()).stream().map(r->fact((String)r.get("fact_type"),uuid(r.get("id")))).toList();
    }
    static void write(Connection c,String query,Object...args)throws SQLException{try(var p=c.prepareStatement(query)){bind(p,args);if(p.executeUpdate()!=1)throw stale();}}
    private static void bind(PreparedStatement p,Object...args)throws SQLException{for(int i=0;i<args.length;i++)p.setObject(i+1,args[i] instanceof Instant at?at.atOffset(ZoneOffset.UTC):args[i]);}
    static UUID uuid(Object value){return value==null?null:value instanceof UUID u?u:UUID.fromString(value.toString());}
    static long number(Object value){if(!(value instanceof Integer||value instanceof Long||value instanceof Short))throw new IllegalArgumentException("Exact integer required");long n=((Number)value).longValue();if(n< -9007199254740991L||n>9007199254740991L)throw new IllegalArgumentException("Unsafe integer");return n;}
    static Instant time(Object value){return value instanceof Timestamp t?t.toInstant():value instanceof OffsetDateTime t?t.toInstant():Instant.parse(value.toString());}
    static String hex(Object value){return HexFormat.of().formatHex((byte[])value);}
    static Subject fact(String type,UUID id){return id==null?null:new Subject(type,id,0L,null);}
    static Subject version(Map<String,Object> v){return v==null?null:new Subject("contract.contract_revision",uuid(v.get("contract_revision_id")),null,Base64.getUrlEncoder().withoutPadding().encodeToString((byte[])v.get("content_digest")));}
    static Map<String,Object> selector(Subject s){return s==null?null:s.hash()!=null?Map.of("id",s.id().toString(),"hash",s.hash()):Map.of("id",s.id().toString(),"revision",s.revision());}
    static Map<String,Object> selector(String type,Object id){return selector(fact(type,uuid(id)));}
    static Blocked stale(){return new Blocked("STALE_SUBJECT");}
    @SuppressWarnings("unchecked") static Map<String,Object> map(Object o){if(!(o instanceof Map<?,?>))throw new IllegalArgumentException("Object required");return (Map<String,Object>)o;}
    private Map<String,Object> opportunity(Connection c,UUID tenant,UUID id,boolean lock)throws SQLException{var r=row(c,"select * from opportunity.opportunity where tenant_id=? and opportunity_id=?"+(lock?" for update":""),tenant,id);if(r==null)throw new Blocked("NOT_FOUND");return r;}
    private Subject opportunity(Map<String,Object> r){return new Subject("opportunity.opportunity",uuid(r.get("opportunity_id")),number(r.get("revision")),null);}
    private Subject confirmation(Connection c,UUID tenant,UUID oid)throws SQLException{var r=row(c,"select x.customer_requirement_confirmation_id id from opportunity.customer_requirement_confirmation x where x.tenant_id=? and x.opportunity_id=? and not exists(select 1 from opportunity.customer_requirement_confirmation n where n.tenant_id=x.tenant_id and n.previous_confirmation_id=x.customer_requirement_confirmation_id)",tenant,oid);return r==null?null:fact("opportunity.customer_requirement_confirmation",uuid(r.get("id")));}
    private Map<String,Object> latest(Connection c,UUID tenant,UUID oid,String table,String previous)throws SQLException{return row(c,"select x.* from contract."+table+" x where x.tenant_id=? and x.opportunity_id=? and not exists(select 1 from contract."+table+" n where n.tenant_id=x.tenant_id and n."+previous+"=x."+table+"_id)",tenant,oid);}
    private Map<String,Object> root(Connection c,UUID tenant,UUID oid)throws SQLException{var rs=rows(c,"select * from contract.contract where tenant_id=? and opportunity_id=? and preparation_contract_code='R2_CONTRACT_PREPARATION_V1' order by contract_id",tenant,oid);if(rs.size()>1)throw new Blocked("CONTRACT_SOURCE_AMBIGUOUS");return rs.isEmpty()?null:rs.getFirst();}
    private Map<String,Object> currentVersion(Connection c,UUID tenant,Map<String,Object> root)throws SQLException{return root==null||root.get("current_revision_id")==null?null:row(c,"select * from contract.contract_revision where tenant_id=? and contract_revision_id=?",tenant,root.get("current_revision_id"));}
    private Map<String,Object> workflow(Connection c,UUID tenant,UUID oid)throws SQLException{return latest(c,tenant,oid,"preparation_workflow","previous_workflow_id");}
    private String stage(Map<String,Object> w){return w==null?"DIRECT_REQUEST":(String)w.get("stage_code");}
    /** A changed basis is a read projection, never a fabricated return decision or history rewrite. */
    private String effectiveStage(Connection c,UUID tenant,Subject conf,Map<String,Object> root,Map<String,Object> w,Responsibility owner)throws SQLException{
        String stage=stage(w);if(!Set.of("SUBMIT_REVIEW","SUBMIT_APPROVAL").contains(stage)||owner==null)return stage;
        var version=currentVersion(c,tenant,root);if(version==null||conf==null||!Objects.equals(version.get("customer_confirmation_id"),conf.id()))return "RETURNED";
        if(!versionDocumentsUsable(c,tenant,version))return "RETURNED";
        try{if(!versions().partySnapshotDigest(c,tenant,conf.id()).equals(hex(version.get("party_snapshot_digest"))))return "RETURNED";}catch(ContractPreparationSources.Unavailable changed){return "RETURNED";}
        var policy=ports.approvalPolicy(c,tenant,owner.organization(),protectedFacts(c,tenant,uuid(root.get("opportunity_id"))));if(policy==null)return "RETURNED";
        var requirements=rows(c,"select policy_id,policy_digest from contract.revision_approval_requirement where tenant_id=? and contract_revision_id=?",tenant,version.get("contract_revision_id"));
        if(requirements.isEmpty()||requirements.stream().anyMatch(r->!policy.id().equals(r.get("policy_id"))||!policy.digest().equals(hex(r.get("policy_digest")))))return "RETURNED";
        return stage;
    }
    private Map<String,Object> decoded(UUID tenant,UUID oid,Map<String,Object> r,String id,ContractProtection.Kind kind){String clear=protection.open(tenant,oid,uuid(r.get(id)),kind,(byte[])r.get("body_ciphertext"));if(!MessageDigest.isEqual(ContractCanonicalJson.digest(clear),(byte[])r.get("body_digest")))throw new IllegalStateException("Contract body integrity failure");return codec.decode(clear);}
    Map<String,Object> packageBody(UUID tenant,UUID oid,Map<String,Object> v){String clear=protection.open(tenant,oid,uuid(v.get("contract_revision_id")),ContractProtection.Kind.PACKAGE,(byte[])v.get("package_ciphertext"));if(!MessageDigest.isEqual(ContractCanonicalJson.digest(clear),(byte[])v.get("content_digest")))throw new IllegalStateException("Contract version integrity failure");return codec.decode(clear);}
    public static ApprovalPolicy approvalPolicy(Connection c,UUID tenant,UUID organization)throws SQLException {
        var r=row(c,"select * from contract.approval_policy where tenant_id=? and organization_unit_id=? and policy_code='R2_CONTRACT_APPROVAL_V1' order by policy_version desc limit 1",tenant,organization);if(r==null)return null;
        var members=rows(c,"select appointment_id from contract.approval_policy_member where tenant_id=? and policy_id=? order by requirement_code",tenant,r.get("approval_policy_id"));if(members.isEmpty())return null;
        return new ApprovalPolicy(uuid(r.get("approval_policy_id")),hex(r.get("policy_digest")),members.stream().map(m->uuid(m.get("appointment_id"))).toList());
    }
    public List<UUID> ledgerOpportunities(Connection c,UUID tenant,int limit,UUID after)throws SQLException {
        if(limit<1||limit>100)throw new IllegalArgumentException("Contract candidate limit must be 1..100");
        return rows(c,"select opportunity_id from contract.contract where tenant_id=? and (?::uuid is null or opportunity_id>?::uuid) order by opportunity_id limit ?",tenant,after,after,limit).stream().map(r->uuid(r.get("opportunity_id"))).toList();
    }
    public List<RecoveryCandidate> recoveryCandidates(Connection c,Actor actor,int limit,UUID after)throws SQLException {return recoveryPage(c,actor,limit,after).candidates();}
    private Map<String,Object> acceptedRecoverySource(Connection c,UUID tenant,UUID oid)throws SQLException {
        return row(c,"select s.* from opportunity.contract_preparation_source s join opportunity.quote_response r on r.tenant_id=s.tenant_id and r.quote_response_id=s.quote_response_id join opportunity.quote_issue i on i.tenant_id=r.tenant_id and i.quote_issue_id=r.quote_issue_id join opportunity.opportunity o on o.tenant_id=s.tenant_id and o.opportunity_id=s.opportunity_id where s.tenant_id=? and s.opportunity_id=? and o.current_quote_revision_id=i.quote_revision_id and not exists(select 1 from contract.contract k where k.tenant_id=s.tenant_id and k.opportunity_id=s.opportunity_id)",tenant,oid);
    }
    private String lostAuthority(Map<String,Object> w){return switch(stage(w)){case "DIRECT_REVIEW"->"CONTRACT_PREPARATION_DECIDE";case "AWAIT_REVIEW"->"CONTRACT_REVIEW";case "AWAIT_APPROVAL"->"CONTRACT_APPROVE";case "DIRECT_RETURNED","PREPARE","RETURNED","REVIEW_BLOCKED","SUBMIT_REVIEW","SUBMIT_APPROVAL","REVIEW_SUPPLEMENT"->"CONTRACT_PREPARE";default->null;};}
    private String returnStage(Map<String,Object> w){return stage(w).equals("OWNER_EXCEPTION")?(String)w.get("recovery_resume_stage"):"CONTRACT_PREPARE".equals(lostAuthority(w))?stage(w):stage(w).equals("DIRECT_REVIEW")?"DIRECT_RETURNED":"RETURNED";}
    private static String preparationTask(String stage){return switch(stage){case "DIRECT_RETURNED"->"REQUEST_CONTRACT_PREPARATION";case "PREPARE","RETURNED","REVIEW_BLOCKED"->"PREPARE_CONTRACT";case "SUBMIT_REVIEW"->"SUBMIT_CONTRACT_REVIEW";case "SUBMIT_APPROVAL"->"SUBMIT_CONTRACT_APPROVAL";case "REVIEW_SUPPLEMENT"->"SUPPLEMENT_CONTRACT_REVIEW";default->throw new Blocked("STALE_SUBJECT");};}
    private boolean authorityRecoveryAvailable(Connection c,UUID tenant,Responsibility owner,Map<String,Object> w,List<Subject> facts)throws SQLException {
        if(w==null)return false;
        if(stage(w).equals("OWNER_EXCEPTION"))return w.get("recovery_resume_stage")!=null&&ports.eligible(c,tenant,owner.organization(),facts,"CONTRACT_PREPARE").contains(owner.owner());
        String authority=lostAuthority(w);if(authority==null||w.get("task_id")==null)return false;
        var task=ports.read(c,tenant,uuid(w.get("task_id")));String purpose=switch(stage(w)){case "DIRECT_REVIEW"->"DECIDE_CONTRACT_PREPARATION";case "AWAIT_REVIEW"->"REVIEW_CONTRACT";case "AWAIT_APPROVAL"->"APPROVE_CONTRACT";default->preparationTask(stage(w));};
        return task!=null&&Set.of("OPEN","WAITING").contains(task.state())&&task.type().equals(purpose)&&task.owner().equals(uuid(w.get("owner_appointment_id")))&&!ports.eligible(c,tenant,owner.organization(),facts,authority).contains(task.owner());
    }
    public RecoveryPage recoveryPage(Connection c,Actor actor,int limit,UUID after)throws SQLException {
        if(actor.principalKind()!=PrincipalKind.SERVICE||limit<1||limit>100)throw new Blocked("NOT_AUTHORIZED");var out=new ArrayList<RecoveryCandidate>();
        var scanned=rows(c,"select o.opportunity_id from opportunity.opportunity o where o.tenant_id=? and (o.closed_at is null or exists(select 1 from contract.payment_request p where p.tenant_id=o.tenant_id and p.opportunity_id=o.opportunity_id)) and (?::uuid is null or o.opportunity_id>?::uuid) and (exists(select 1 from contract.signature_handoff h where h.tenant_id=o.tenant_id and h.opportunity_id=o.opportunity_id) or (exists(select 1 from contract.negotiation_disposition d where d.tenant_id=o.tenant_id and d.opportunity_id=o.opportunity_id and d.kind='REQUEST_REVIEW' and not exists(select 1 from contract.negotiation_disposition n where n.tenant_id=d.tenant_id and n.previous_disposition_id=d.negotiation_disposition_id))) or (exists(select 1 from opportunity.contract_preparation_source s where s.tenant_id=o.tenant_id and s.opportunity_id=o.opportunity_id) and not exists(select 1 from contract.preparation_workflow w where w.tenant_id=o.tenant_id and w.opportunity_id=o.opportunity_id)) or exists(select 1 from contract.preparation_workflow w where w.tenant_id=o.tenant_id and w.opportunity_id=o.opportunity_id and w.stage_code in ('DIRECT_REVIEW','AWAIT_REVIEW','AWAIT_APPROVAL','OWNER_EXCEPTION','READY_FOR_SIGNATURE','DIRECT_RETURNED','PREPARE','RETURNED','REVIEW_BLOCKED','SUBMIT_REVIEW','SUBMIT_APPROVAL','REVIEW_SUPPLEMENT') and not exists(select 1 from contract.preparation_workflow n where n.tenant_id=w.tenant_id and n.previous_workflow_id=w.preparation_workflow_id))) order by o.opportunity_id limit ?",actor.tenantId(),after,after,limit);
        for(var scannedRow:scanned){UUID oid=uuid(scannedRow.get("opportunity_id"));var o=opportunity(opportunity(c,actor.tenantId(),oid,false));var owner=ports.responsibility(c,actor.tenantId(),o);if(owner==null)continue;var w=workflow(c,actor.tenantId(),oid);var fs=new ArrayList<>(protectedFacts(c,actor.tenantId(),oid));
            if(!ports.permitted(c,actor,owner.organization(),fs,"CONTRACT_TASK_RECOVER"))continue;
            var paymentCandidate=ports.paymentRecovery(c,actor,o,owner);if(paymentCandidate!=null){out.add(paymentCandidate);continue;}if(opportunity(c,actor.tenantId(),oid,false).get("closed_at")!=null)continue;
            if(negotiation().blocked(negotiation().latest(c,actor.tenantId(),oid))){var candidate=negotiation().recovery(c,actor,o,owner,fs);if(candidate!=null)out.add(candidate);continue;}var root=root(c,actor.tenantId(),oid);var version=currentVersion(c,actor.tenantId(),root);var ready=signatures().readiness(c,actor.tenantId(),version);
            var executionCandidate=executions().recovery(c,actor,o,owner,fs);if(executionCandidate!=null){out.add(executionCandidate);continue;}
            if(ready!=null&&stage(w).equals("READY_FOR_SIGNATURE")){try{signatures().current(c,actor,owner,confirmation(c,actor.tenantId(),oid),root,version,ready,fs);}catch(Blocked|ContractPreparationSources.Unavailable invalid){continue;}var sw=signatures().workflow(c,actor.tenantId(),uuid(ready.get("signature_readiness_id")));if(signatures().recoverable(c,actor.tenantId(),oid,owner,ready,sw)){var source=sw==null?fact("contract.signature_readiness",uuid(ready.get("signature_readiness_id"))):fact("contract.signature_workflow",uuid(sw.get("signature_workflow_id")));out.add(new RecoveryCandidate(o,owner.basis(),source,sw==null?null:source,owner.owner()));}continue;}
            if(authorityRecoveryAvailable(c,actor.tenantId(),owner,w,fs)){var wf=fact("contract.preparation_workflow",uuid(w.get("preparation_workflow_id")));out.add(new RecoveryCandidate(o,owner.basis(),wf,wf,owner.owner()));continue;}
            if(w!=null&&(!stage(w).equals("OWNER_EXCEPTION")||w.get("recovery_resume_stage")!=null))continue;
            var source=acceptedRecoverySource(c,actor.tenantId(),oid);if(source==null)continue;var sf=fact("opportunity.contract_preparation_source",uuid(source.get("contract_preparation_source_id")));fs.add(sf);
            if(!ports.permitted(c,actor,owner.organization(),fs,"CONTRACT_TASK_RECOVER"))continue;
            boolean qualified=ports.eligible(c,actor.tenantId(),owner.organization(),fs,"CONTRACT_PREPARE").contains(owner.owner());if(w!=null&&!qualified)continue;
            if(ports.active(c,actor.tenantId(),o).stream().anyMatch(t->t.type().equals("PREPARE_CONTRACT")&&t.owner().equals(owner.owner())))continue;
            out.add(new RecoveryCandidate(o,owner.basis(),sf,w==null?null:fact("contract.preparation_workflow",uuid(w.get("preparation_workflow_id"))),owner.owner()));
        }
        return new RecoveryPage(out,scanned.isEmpty()?after:uuid(scanned.getLast().get("opportunity_id")),scanned.size()<limit);
    }
    public Subject reconcile(Connection c,Actor actor,Map<String,Object> payload)throws SQLException {
        requireCommandScope();
        if(actor.principalKind()!=PrincipalKind.SERVICE||c.getAutoCommit()||c.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED)throw new Blocked("NOT_AUTHORIZED");
        UUID tenant=actor.tenantId(),oid=uuid(payload.get("opportunityId"));var raw=opportunity(c,tenant,oid,true);var o=opportunity(raw);if(o.revision()!=number(payload.get("expectedOpportunityRevision")))throw stale();var owner=ports.responsibility(c,tenant,o);if(owner==null)throw stale();expect(payload.get("responsibilityBasis"),owner.basis());if(Set.of("PAYMENT_HANDOFF","PAYMENT_RECOVERY").contains(payload.getOrDefault("sourceKind","")))return ports.reconcilePayment(c,actor,o,payload);if(raw.get("closed_at")!=null)throw stale();if("TERMINATION_REVIEW".equals(payload.get("sourceKind")))return negotiation().reconcile(c,actor,o,owner,payload,protectedFacts(c,tenant,oid));negotiation().requireContinued(c,tenant,oid);if("EXECUTION_HANDOFF".equals(payload.get("sourceKind")))return executions().reconcile(c,actor,o,owner,payload);var previous=workflow(c,tenant,oid);if(Set.of("SIGNATURE_READINESS","SIGNATURE_AUTHORITY_RETURN").contains(payload.getOrDefault("sourceKind",""))){var root=root(c,tenant,oid);return signatures().reconcile(c,actor,payload,o,owner,confirmation(c,tenant,oid),root,currentVersion(c,tenant,root));}expect(payload.get("expectedWorkflow"),previous==null?null:fact("contract.preparation_workflow",uuid(previous.get("preparation_workflow_id"))));
        var sf=map(payload.get("source"));if(number(sf.get("revision"))!=0)throw stale();var facts=new ArrayList<>(protectedFacts(c,tenant,oid));
        if("AUTHORITY_RETURN".equals(payload.get("sourceKind"))){
            if(previous==null||!uuid(sf.get("id")).equals(previous.get("preparation_workflow_id"))||!authorityRecoveryAvailable(c,tenant,owner,previous,facts))throw stale();
            var prior=ports.read(c,tenant,uuid(previous.get(stage(previous).equals("OWNER_EXCEPTION")?"prior_task_id":"task_id")));if(prior==null||!prior.subject().id().equals(oid))throw stale();facts.add(prior.selector());
            if(!ports.permitted(c,actor,owner.organization(),facts,"CONTRACT_TASK_RECOVER"))throw new Blocked("NOT_AUTHORIZED");
            boolean qualified=ports.eligible(c,tenant,owner.organization(),facts,"CONTRACT_PREPARE").contains(owner.owner());String target=returnStage(previous);Instant now=ports.now(c);var beforeCancel=prior;
            if(stage(previous).equals("OWNER_EXCEPTION")){
                if(!"CANCELLED".equals(prior.state())||prior.selector().revision()<1)throw stale();
                beforeCancel=new Task(new Subject(prior.selector().type(),prior.selector().id(),prior.selector().revision()-1,null),prior.owner(),prior.type(),prior.subject(),"OPEN",prior.createdAt(),null);
            }else ports.cancelForContract(c,tenant,prior,"CONTRACT_AUTHORITY_MISSING",now);
            var task=qualified?ports.createContractTakingOver(c,tenant,preparationTask(target),owner.owner(),o,beforeCancel,ZONE,now):null;UUID id=newId(c);var root=root(c,tenant,oid);
            write(c,"insert into contract.preparation_workflow(tenant_id,preparation_workflow_id,revision,opportunity_id,contract_id,previous_workflow_id,stage_code,owner_appointment_id,task_id,prior_task_id,created_by_appointment_id,recovery_resume_stage,created_at) values(?,?,0,?,?,?,?,?,?,?,?,?,clock_timestamp())",tenant,id,oid,root==null?null:root.get("contract_id"),previous.get("preparation_workflow_id"),qualified?target:"OWNER_EXCEPTION",owner.owner(),task==null?null:task.selector().id(),prior.selector().id(),actor.appointmentId(),qualified?null:target);
            return fact("contract.preparation_workflow",id);
        }
        if(!"ACCEPTED_QUOTE".equals(payload.getOrDefault("sourceKind","ACCEPTED_QUOTE"))||root(c,tenant,oid)!=null||previous!=null&&(!stage(previous).equals("OWNER_EXCEPTION")||previous.get("recovery_resume_stage")!=null))throw stale();
        var source=acceptedRecoverySource(c,tenant,oid);if(source==null||!uuid(sf.get("id")).equals(source.get("contract_preparation_source_id")))throw stale();facts.add(fact("opportunity.contract_preparation_source",uuid(sf.get("id"))));if(!ports.permitted(c,actor,owner.organization(),facts,"CONTRACT_TASK_RECOVER"))throw new Blocked("NOT_AUTHORIZED");
        var selected=ports.acceptedQuote(c,tenant,oid);var conf=confirmation(c,tenant,oid);if(selected==null||conf==null||!uuid(selected.get("responseId")).equals(source.get("quote_response_id")))throw stale();var terms=commercial(map(selected.get("commercial")));sources.resolve(c,new ContractPreparationSource.Basis(tenant,oid,conf.id(),terms.digest()),new ContractPreparationSources.AcceptedResponse(uuid(source.get("quote_response_id"))));
        boolean qualified=ports.eligible(c,tenant,owner.organization(),facts,"CONTRACT_PREPARE").contains(owner.owner());if(previous!=null&&!qualified)throw stale();
        if(ports.active(c,tenant,o).stream().anyMatch(t->t.type().equals("PREPARE_CONTRACT")&&t.owner().equals(owner.owner())))throw stale();var task=qualified?ports.create(c,tenant,"PREPARE_CONTRACT",owner.owner(),o,ZONE,ports.now(c)):null;UUID id=newId(c);
        write(c,"insert into contract.preparation_workflow(tenant_id,preparation_workflow_id,revision,opportunity_id,previous_workflow_id,stage_code,owner_appointment_id,task_id,created_by_appointment_id,created_at) values(?,?,0,?,?,?,?,?,?,clock_timestamp())",tenant,id,oid,previous==null?null:previous.get("preparation_workflow_id"),qualified?"PREPARE":"OWNER_EXCEPTION",owner.owner(),task==null?null:task.selector().id(),actor.appointmentId());
        return fact("contract.preparation_workflow",id);
    }
    public List<Subject> protectedFacts(Connection c,UUID tenant,UUID oid)throws SQLException {
        var scope=ledgerFacts.get();
        if(scope==null||scope.connection()!=c||!scope.tenant().equals(tenant))return loadProtectedFacts(c,tenant,oid);
        var facts=scope.facts().get(oid);if(facts==null){facts=loadProtectedFacts(c,tenant,oid);scope.facts().put(oid,facts);}return facts;
    }
    private List<Subject> loadProtectedFacts(Connection c,UUID tenant,UUID oid)throws SQLException {
        var out=new LinkedHashSet<Subject>(ports.sourceFacts(c,tenant,oid));var o=opportunity(opportunity(c,tenant,oid,false));out.add(o);var owner=ports.responsibility(c,tenant,o);if(owner!=null)out.add(owner.basis());
        out.addAll(referenceFacts(c,tenant,"opportunity_id",oid,List.of("preparation_request","preparation_draft","preparation_workflow","execution_workflow","execution_verification","payment_request","payment_workflow","payment_review")));
        for(var r:rows(c,"select d.preparation_decision_id id from contract.preparation_decision d join contract.preparation_request p on p.tenant_id=d.tenant_id and p.preparation_request_id=d.preparation_request_id where d.tenant_id=? and p.opportunity_id=?",tenant,oid))out.add(fact("contract.preparation_decision",uuid(r.get("id"))));
        for(var r:rows(c,"select * from contract.contract where tenant_id=? and opportunity_id=?",tenant,oid)){out.add(new Subject("contract.contract",uuid(r.get("contract_id")),number(r.get("revision")),null));if("R2_CONTRACT_PREPARATION_V1".equals(r.get("preparation_contract_code")))out.add(fact("contract.contract",uuid(r.get("contract_id"))));}
        for(var v:rows(c,"select v.* from contract.contract_revision v join contract.contract r on r.tenant_id=v.tenant_id and r.contract_id=v.contract_id where v.tenant_id=? and r.opportunity_id=?",tenant,oid)){
            out.add(version(v));if(v.get("template_version_id")!=null)out.addAll(approvedDocumentFacts(c,tenant,"template_version",uuid(v.get("template_version_id"))));if(v.get("body_evidence_version_id")!=null)out.addAll(ports.documentFacts(c,tenant,uuid(v.get("body_evidence_version_id"))));
            for(var clause:rows(c,"select clause_version_id from contract.revision_clause where tenant_id=? and contract_revision_id=?",tenant,v.get("contract_revision_id")))out.addAll(approvedDocumentFacts(c,tenant,"clause_version",uuid(clause.get("clause_version_id"))));
            out.addAll(referenceFacts(c,tenant,"contract_revision_id",uuid(v.get("contract_revision_id")),List.of("revision_review_request","revision_review_binding","revision_approval_request","revision_approval_requirement","signature_readiness")));
            for(var r:rows(c,"select d.revision_review_decision_id id from contract.revision_review_decision d join contract.revision_review_request p on p.tenant_id=d.tenant_id and p.revision_review_request_id=d.request_id where d.tenant_id=? and p.contract_revision_id=?",tenant,v.get("contract_revision_id")))out.add(fact("contract.revision_review_decision",uuid(r.get("id"))));
            for(var r:rows(c,"select d.revision_approval_decision_id id from contract.revision_approval_decision d join contract.revision_approval_requirement p on p.tenant_id=d.tenant_id and p.revision_approval_requirement_id=d.requirement_id where d.tenant_id=? and p.contract_revision_id=?",tenant,v.get("contract_revision_id")))out.add(fact("contract.revision_approval_decision",uuid(r.get("id"))));
        }
        out.addAll(signatures().facts(c,tenant,oid));out.addAll(negotiation().facts(c,tenant,oid));
        return List.copyOf(out);
    }
    private List<Subject> approvedDocumentFacts(Connection c,UUID tenant,String table,UUID id)throws SQLException{var r=row(c,"select evidence_version_id from contract."+table+" where tenant_id=? and "+table+"_id=?",tenant,id);if(r==null)throw new Blocked("STALE_EVIDENCE");var fs=new LinkedHashSet<>(ports.documentFacts(c,tenant,uuid(r.get("evidence_version_id"))));fs.add(fact("contract."+table,id));if(table.equals("template_version"))fs.addAll(signatures().templateFacts(c,tenant,id));return List.copyOf(fs);}
    public List<Subject> disclosureFacts(Connection c,Actor actor,UUID oid)throws SQLException{var fs=new LinkedHashSet<>(protectedFacts(c,actor.tenantId(),oid));var owner=ports.responsibility(c,actor.tenantId(),opportunity(opportunity(c,actor.tenantId(),oid,false)));if(owner!=null)for(var r:rows(c,"select template_version_id from contract.template_version where tenant_id=?",actor.tenantId())){var doc=approvedDocumentFacts(c,actor.tenantId(),"template_version",uuid(r.get("template_version_id")));if(ports.permitted(c,actor,owner.organization(),doc,"CONTRACT_READ"))fs.addAll(doc);}return List.copyOf(fs);}
    private List<String> actions(Connection c,Actor actor,Subject o,Responsibility owner,Subject conf,Map<String,Object> root,Map<String,Object> w)throws SQLException {
        if(owner==null||conf==null)return List.of();return actions(c,actor,o,owner,conf,root,w,effectiveStage(c,actor.tenantId(),conf,root,w,owner));
    }
    private List<String> actions(Connection c,Actor actor,Subject o,Responsibility owner,Subject conf,Map<String,Object> root,Map<String,Object> w,String s)throws SQLException {
        if(owner==null||conf==null)return List.of();var handling=negotiation();var disposition=handling.latest(c,actor.tenantId(),o.id());var allowed=new ArrayList<String>(handling.actions(c,actor,o,owner,w,protectedFacts(c,actor.tenantId(),o.id())));if(handling.blocked(disposition))return List.copyOf(allowed);boolean sales=owner.owner().equals(actor.appointmentId()),assigned=w!=null&&Objects.equals(w.get("owner_appointment_id"),actor.appointmentId());
        if(sales&&Set.of("DIRECT_REQUEST","DIRECT_RETURNED","PREPARE","RETURNED","REVIEW_BLOCKED","REVIEW_SUPPLEMENT").contains(s)){
            if(root==null){if(ports.acceptedQuote(c,actor.tenantId(),o.id())!=null||approvedRequest(c,actor.tenantId(),o.id())!=null)allowed.add("START_CONTRACT_PREPARATION");else allowed.add("REQUEST_CONTRACT_PREPARATION");}
            else {allowed.add("REQUEST_CONTRACT_PREPARATION");if(ports.acceptedQuote(c,actor.tenantId(),o.id())!=null||approvedRequest(c,actor.tenantId(),o.id())!=null){allowed.add("SAVE_CONTRACT_DRAFT");allowed.add("FORM_CONTRACT");}}
        }
        if(sales&&s.equals("SUBMIT_REVIEW"))allowed.add("REQUEST_CONTRACT_REVIEW");
        if(sales&&s.equals("REVIEW_SUPPLEMENT"))allowed.add("REQUEST_CONTRACT_REVIEW");
        if(sales&&s.equals("SUBMIT_APPROVAL"))allowed.add("REQUEST_CONTRACT_APPROVAL");
        if(assigned&&s.equals("DIRECT_REVIEW"))allowed.add("RECORD_CONTRACT_PREPARATION_DECISION");
        if(assigned&&s.equals("AWAIT_REVIEW"))allowed.add("RECORD_CONTRACT_REVIEW");
        if(assigned&&s.equals("AWAIT_APPROVAL"))allowed.add("RECORD_CONTRACT_DECISION");
        if(s.equals("READY_FOR_SIGNATURE"))allowed.addAll(signatures().actions(c,actor,o,owner,conf,root,currentVersion(c,actor.tenantId(),root)));
        if(s.equals("READY_FOR_SIGNATURE"))allowed.addAll(executions().actions(c,actor,o,owner));
        return List.copyOf(allowed);
    }
    private Map<String,Object> approvedRequest(Connection c,UUID tenant,UUID oid)throws SQLException {
        var r=latest(c,tenant,oid,"preparation_request","previous_request_id");if(r==null)return null;
        var o=opportunity(opportunity(c,tenant,oid,false));var owner=ports.responsibility(c,tenant,o);var current=confirmation(c,tenant,oid);
        if(owner==null||current==null||!current.id().equals(r.get("customer_confirmation_id"))||!owner.owner().equals(r.get("owner_appointment_id"))||!owner.basis().type().equals(r.get("responsibility_type"))||!owner.basis().id().equals(r.get("responsibility_id"))||owner.basis().revision()!=number(r.get("responsibility_revision"))||o.revision()!=number(r.get("opportunity_revision")))return null;
        var d=row(c,"select * from contract.preparation_decision where tenant_id=? and preparation_request_id=? and decision_code='APPROVED' and effective_from<=clock_timestamp() and (effective_until is null or effective_until>clock_timestamp())",tenant,r.get("preparation_request_id"));if(d==null)return null;var result=new LinkedHashMap<>(r);result.put("decision_id",d.get("preparation_decision_id"));return result;
    }
    /** List-only projection: no document bodies, draft decrypt, history, choices, or review preview. */
    public Map<String,Object> ledgerContext(Connection c,Actor actor,UUID oid)throws SQLException {
        UUID tenant=actor.tenantId();var opening=opportunity(c,tenant,oid,false);var o=opportunity(opening);var owner=ports.responsibility(c,tenant,o);var conf=confirmation(c,tenant,oid);var root=root(c,tenant,oid);var result=new LinkedHashMap<String,Object>();
        if(root==null){result.put("contract",null);return result;}
        var v=currentVersion(c,tenant,root);var w=workflow(c,tenant,oid);String stage=effectiveStage(c,tenant,conf,root,w,owner);
        result.put("customerName",ports.customerName(c,tenant,oid));
        result.put("execution",executions().context(c,actor,oid));
        result.put("payments",ports.payments(c,actor,opportunity(opportunity(c,tenant,oid,false))));
        result.put("termination",negotiation().summary(c,tenant,oid));result.put("signature",stage(w).equals("READY_FOR_SIGNATURE")?signatures().summary(c,actor,v):null);
        result.put("allowedActions",opening.get("closed_at")!=null?List.of():actions(c,actor,o,owner,conf,root,w,stage));
        try{if(conf!=null)versions().partySnapshotDigest(c,tenant,conf.id());}catch(ContractPreparationSources.Unavailable changed){result.put("allowedActions",negotiation().actions(c,actor,o,owner,w,protectedFacts(c,tenant,oid)));}
        result.put("contract",Map.of("selector",selector(new Subject("contract.contract",uuid(root.get("contract_id")),number(root.get("revision")),null)),"version",v==null?0:number(v.get("revision_no")),"stage",stage));
        if(w!=null){var work=new LinkedHashMap<String,Object>();work.put("stage",stage);work.put("ownerAppointmentId",w.get("owner_appointment_id").toString());var task=w.get("task_id")==null?null:ports.currentTask(c,tenant,uuid(w.get("task_id")));work.put("task",task==null?null:selector(task.selector()));if(task!=null)work.put("ownerAppointmentId",task.owner().toString());result.put("workflow",work);}else result.put("workflow",null);
        return result;
    }
    public Map<String,Object> context(Connection c,Actor actor,UUID oid)throws SQLException {
        UUID tenant=actor.tenantId();var opening=opportunity(c,tenant,oid,false);var o=opportunity(opening);var owner=ports.responsibility(c,tenant,o);var conf=confirmation(c,tenant,oid);var root=root(c,tenant,oid);var v=currentVersion(c,tenant,root);var w=workflow(c,tenant,oid);var d=latest(c,tenant,oid,"preparation_draft","previous_draft_id");var request=latest(c,tenant,oid,"preparation_request","previous_request_id");var result=new LinkedHashMap<String,Object>();
        result.put("opportunity",selector(o));result.put("responsibilityBasis",owner==null?null:selector(owner.basis()));result.put("customerConfirmation",selector(conf));result.put("customerName",ports.customerName(c,tenant,oid));result.put("termination",negotiation().context(c,tenant,oid));result.put("readonly",owner==null||opening.get("closed_at")!=null||negotiation().stopped(negotiation().latest(c,tenant,oid)));
        result.put("allowedActions",opening.get("closed_at")!=null?List.of():actions(c,actor,o,owner,conf,root,w));result.put("blockers",conf==null?List.of(Map.of("code","CUSTOMER_CONFIRMATION_REQUIRED","message","请先确认客户资料与服务需求。")):List.of());result.put("receiptBoundary",null);
        result.put("preparation",request==null?null:preparationContext(tenant,oid,request));
        if(d!=null){var body=drafts().readDraft(c,tenant,uuid(d.get("preparation_draft_id")));var doc=new LinkedHashMap<>(body.preparation());doc.put("commercial",body.commercial());result.put("draft",Map.of("selector",selector(body.selector()),"document",doc));}else result.put("draft",null);
        if(root!=null){var body=v==null?null:packageBody(tenant,oid,v);var contract=new LinkedHashMap<String,Object>();contract.put("selector",selector(new Subject("contract.contract",uuid(root.get("contract_id")),number(root.get("revision")),null)));contract.put("currentRevision",v==null?null:Map.of("id",v.get("contract_revision_id").toString(),"hash",hex(v.get("content_digest"))));contract.put("version",v==null?0:number(v.get("revision_no")));contract.put("document",body==null?sourceDocument(c,tenant,oid):Map.of("commercial",body.get("commercial"),"document",body.get("document"),"signing",body.get("signing"),"paymentGate",body.get("paymentGate")));boolean direct=(v==null?root.get("direct_preparation_decision_id"):v.get("source_direct_decision_id"))!=null;Object sid=v==null?root.get(direct?"direct_preparation_decision_id":"accepted_quote_response_id"):v.get(direct?"source_direct_decision_id":"source_quote_response_id");contract.put("source",Map.of("kind",direct?"DIRECT_AUTHORIZATION":"ACCEPTED_QUOTE","selector",sourceSelector(c,tenant,direct,uuid(sid))));contract.put("stage",effectiveStage(c,tenant,conf,root,w,owner));result.put("contract",contract);}else result.put("contract",null);
        if(w!=null){var work=new LinkedHashMap<String,Object>();work.put("selector",selector("contract.preparation_workflow",w.get("preparation_workflow_id")));work.put("stage",effectiveStage(c,tenant,conf,root,w,owner));if(!stage(w).equals(work.get("stage")))work.put("message","客户资料或审批依据已变化，请按当前依据修订合同；原审查和批准不沿用。");work.put("ownerAppointmentId",w.get("owner_appointment_id").toString());var task=w.get("task_id")==null?null:ports.currentTask(c,tenant,uuid(w.get("task_id")));work.put("task",task==null?null:selector(task.selector()));if(task!=null)work.put("ownerAppointmentId",task.owner().toString());result.put("workflow",work);}else result.put("workflow",null);
        var approvals=v==null?List.<Map<String,Object>>of():rows(c,"select q.requirement_code slot,coalesce(d.decision_code,'PENDING') status,q.revision_approval_requirement_id id from contract.revision_approval_requirement q left join contract.revision_approval_decision d on d.tenant_id=q.tenant_id and d.requirement_id=q.revision_approval_requirement_id where q.tenant_id=? and q.contract_revision_id=? order by q.requirement_code",tenant,v.get("contract_revision_id"));result.put("approvals",approvals.stream().map(a->Map.of("slot",a.get("slot"),"status",a.get("status"),"selector",selector("contract.revision_approval_requirement",a.get("id")))).toList());
        result.put("signature",stage(w).equals("READY_FOR_SIGNATURE")?signatures().context(c,actor,oid,v):null);
        result.put("execution",executions().context(c,actor,oid));
        result.put("payments",ports.payments(c,actor,opportunity(opportunity(c,tenant,oid,false))));
        result.put("review",reviewContext(c,tenant,v));
        if(v!=null&&w!=null&&stage(w).equals("AWAIT_REVIEW")&&actor.appointmentId().equals(w.get("owner_appointment_id"))&&owner!=null&&ports.permitted(c,actor,owner.organization(),protectedFacts(c,tenant,oid),"CONTRACT_REVIEW")){
            var preview=ContractReviewRepository.databaseBacked(protection,codec,ports::blockFinding,ports::reviewScopeComplete).preview(c,tenant,uuid(v.get("contract_revision_id")));
            result.put("reviewPreview",Map.of("scopeComplete",preview.scopeComplete(),"candidateCount",preview.candidateCount(),"permittedOutcomes",preview.permittedOutcomes()));
        }
        result.put("history",history(c,tenant,oid));result.put("signatureHistory",signatures().allHistory(c,tenant,oid));result.put("templates",templates(c,actor,owner));result.put("documents",ports.documents(c,actor,oid));try{result.put("partySnapshotDigest",conf==null?null:versions().partySnapshotDigest(c,tenant,conf.id()));}catch(ContractPreparationSources.Unavailable changed){result.put("partySnapshotDigest",null);result.put("allowedActions",negotiation().actions(c,actor,o,owner,w,protectedFacts(c,tenant,oid)));result.put("blockers",List.of(Map.of("code","CUSTOMER_CONFIRMATION_STALE","message","客户主体资料已变化，请先重新确认客户资料与服务需求。")));}return result;
    }
    private Map<String,Object> sourceSelector(Connection c,UUID tenant,boolean direct,UUID id)throws SQLException {
        if(direct)return selector("contract.preparation_decision",id);
        var response=row(c,"select response_content_digest from opportunity.quote_response where tenant_id=? and quote_response_id=?",tenant,id);
        if(response==null||response.get("response_content_digest")==null)throw stale();
        return selector(new Subject("opportunity.quote_response",id,null,Base64.getUrlEncoder().withoutPadding().encodeToString((byte[])response.get("response_content_digest"))));
    }
    private Map<String,Object> preparationContext(UUID tenant,UUID oid,Map<String,Object> r){var body=decoded(tenant,oid,r,"preparation_request_id",ContractProtection.Kind.REQUEST);return Map.of("selector",selector("contract.preparation_request",r.get("preparation_request_id")),"commercial",body.get("commercial"),"reason",body.get("reason"));}
    private Map<String,Object> sourceDocument(Connection c,UUID tenant,UUID oid)throws SQLException{var source=source(c,tenant,oid);return source==null?Map.of():Map.of("commercial",source.commercial().canonical());}
    private List<Map<String,Object>> templates(Connection c,Actor actor,Responsibility owner)throws SQLException{if(owner==null)return List.of();var result=new ArrayList<Map<String,Object>>();for(var r:rows(c,"select * from contract.template_version where tenant_id=? order by document_code,version_no desc",actor.tenantId())){var f=fact("contract.template_version",uuid(r.get("template_version_id")));if(ports.documentUsable(c,actor.tenantId(),uuid(r.get("evidence_version_id")),hex(r.get("body_sha256")))&&ports.permitted(c,actor,owner.organization(),approvedDocumentFacts(c,actor.tenantId(),"template_version",f.id()),"CONTRACT_READ"))result.add(Map.of("id",f.id().toString(),"label",r.get("document_code")+" · v"+r.get("version_no"),"clauseVersionIds",List.of()));}return result;}
    private List<Map<String,Object>> history(Connection c,UUID tenant,UUID oid)throws SQLException{return rows(c,"select preparation_workflow_id id,stage_code,created_at from contract.preparation_workflow where tenant_id=? and opportunity_id=? order by created_at,preparation_workflow_id",tenant,oid).stream().map(r->Map.<String,Object>of("id",r.get("id").toString(),"label","合同办理记录","summary",stageLabel(r.get("stage_code").toString()),"occurredAt",time(r.get("created_at")).toString())).toList();}
    private static String stageLabel(String s){return switch(s){case "DIRECT_REVIEW"->"等待直接准备授权";case "DIRECT_RETURNED"->"准备申请已退回";case "PREPARE"->"准备合同";case "SUBMIT_REVIEW"->"合同版本已形成";case "AWAIT_REVIEW"->"等待签约前审查";case "REVIEW_SUPPLEMENT"->"需要补充资料";case "REVIEW_BLOCKED"->"签约前审查阻断";case "SUBMIT_APPROVAL"->"等待提交合同审批";case "AWAIT_APPROVAL"->"等待合同审批";case "RETURNED"->"合同已退回修订";case "READY_FOR_SIGNATURE"->"审批完成，等待签署阶段";default->"等待责任办理";};}
    private Map<String,Object> reviewContext(Connection c,UUID tenant,Map<String,Object> v)throws SQLException{if(v==null)return null;var r=row(c,"select q.revision_review_request_id id,q.scope_hash,d.decision_code from contract.revision_review_request q left join contract.revision_review_decision d on d.tenant_id=q.tenant_id and d.request_id=q.revision_review_request_id where q.tenant_id=? and q.contract_revision_id=? and not exists(select 1 from contract.revision_review_request n where n.tenant_id=q.tenant_id and n.previous_request_id=q.revision_review_request_id)",tenant,v.get("contract_revision_id"));if(r==null)return null;String code=r.get("decision_code")==null?"PENDING":r.get("decision_code").toString();return Map.of("selector",selector("contract.revision_review_request",r.get("id")),"status",code,"summary",switch(code){case "CLEAR","WAIVED"->"本版审查已通过";case "NEED_INFO"->"请补充资料后重新提交";case "BLOCKED"->"本版审查未通过";default->"等待有权人员审查";},"scopeHash",hex(r.get("scope_hash")));}
    public byte[] document(Connection c,Actor actor,UUID oid,UUID cid,UUID vid)throws SQLException{var v=row(c,"select v.* from contract.contract_revision v join contract.contract r on r.tenant_id=v.tenant_id and r.contract_id=v.contract_id where v.tenant_id=? and r.opportunity_id=? and r.contract_id=? and v.contract_revision_id=?",actor.tenantId(),oid,cid,vid);if(v==null)throw new Blocked("NOT_FOUND");byte[] bytes=ports.document(c,actor,oid,uuid(v.get("body_evidence_version_id")));try{if(!MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(bytes),(byte[])v.get("body_sha256")))throw new Blocked("STALE_EVIDENCE");}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}return bytes;}
    record Source(ContractPreparationSources.Selection selection,ContractVersionInput.CommercialTerms commercial){}
    private Source source(Connection c,UUID tenant,UUID oid)throws SQLException{var direct=approvedRequest(c,tenant,oid);if(direct!=null)return new Source(new ContractPreparationSources.DirectDecision(uuid(direct.get("decision_id"))),commercial(map(decoded(tenant,oid,direct,"preparation_request_id",ContractProtection.Kind.REQUEST).get("commercial"))));var quote=ports.acceptedQuote(c,tenant,oid);return quote==null?null:new Source(new ContractPreparationSources.AcceptedResponse(uuid(quote.get("responseId"))),commercial(map(quote.get("commercial"))));}
    public static ContractVersionInput.CommercialTerms commercial(Map<String,Object> m){var lines=new ArrayList<ContractVersionInput.FeeLine>();for(Object value:(List<?>)m.get("lines")){var l=map(value);lines.add(new ContractVersionInput.FeeLine((String)l.get("description"),number(l.get("amountMinor")),(Boolean)l.get("discount")));}var q=m.get("conditionalFee")==null?null:map(m.get("conditionalFee"));return new ContractVersionInput.CommercialTerms((String)m.get("currency"),(String)m.get("scope"),lines,q==null?null:new ContractVersionInput.ConditionalFee((String)q.get("basis"),Math.toIntExact(number(q.get("rateBasisPoints"))),number(q.get("capMinor"))),(String)m.get("paymentTerms"));}
    // Commands below share one opportunity lock, exact workflow CAS and original receipt transaction.
    public Generation generation(Connection c,Actor actor,Map<String,Object> payload)throws SQLException {
        UUID tenant=actor.tenantId(),oid=uuid(payload.get("opportunityId"));var opening=opportunity(c,tenant,oid,false);if(opening.get("closed_at")!=null)throw new Blocked("OPPORTUNITY_CLOSED");var o=opportunity(opening);var owner=ports.responsibility(c,tenant,o);var conf=confirmation(c,tenant,oid);var root=root(c,tenant,oid);var version=currentVersion(c,tenant,root);var workflow=workflow(c,tenant,oid);var draft=latest(c,tenant,oid,"preparation_draft","previous_draft_id");
        if(owner==null||conf==null||o.revision()!=number(payload.get("expectedOpportunityRevision")))throw stale();
        expect(payload.get("responsibilityBasis"),owner.basis());expect(payload.get("customerConfirmation"),conf);expect(payload.get("expectedContract"),root==null?null:new Subject("contract.contract",uuid(root.get("contract_id")),number(root.get("revision")),null));expect(payload.get("expectedDraft"),draft==null?null:fact("contract.preparation_draft",uuid(draft.get("preparation_draft_id"))));expectVersion(payload.get("expectedVersion"),version);expect(payload.get("expectedWorkflow"),workflow==null?null:fact("contract.preparation_workflow",uuid(workflow.get("preparation_workflow_id"))));
        if(root==null||!actions(c,actor,o,owner,conf,root,workflow).contains("FORM_CONTRACT"))throw new Blocked("NOT_AUTHORIZED");
        negotiation().requireContinued(c,tenant,oid);negotiation().expectCurrent(map(payload.get("values")),negotiation().latest(c,tenant,oid));
        var values=map(payload.get("values"));var doc=map(values.get("document"));if(!((List<?>)doc.get("clauseVersionIds")).isEmpty())throw new Blocked("VALIDATION_FAILED");
        var template=row(c,"select * from contract.template_version where tenant_id=? and template_version_id=?",tenant,uuid(doc.get("templateVersionId")));if(template==null)throw new Blocked("STALE_EVIDENCE");
        var facts=new ArrayList<>(protectedFacts(c,tenant,oid));facts.addAll(approvedDocumentFacts(c,tenant,"template_version",uuid(doc.get("templateVersionId"))));
        if(!ports.permitted(c,actor,owner.organization(),facts,"CONTRACT_READ")||!ports.permitted(c,actor,owner.organization(),facts,"CONTRACT_PREPARE"))throw new Blocked("NOT_AUTHORIZED");
        var source=requireSource(c,tenant,oid);var terms=commercial(map(values.get("commercial")));if(!terms.digest().equals(source.commercial().digest()))throw new Blocked("COMMERCIAL_AUTHORIZATION_REQUIRED");
        var signing=map(values.get("signing"));var signed=new ContractVersionInput.Signing((String)signing.get("partySnapshotDigest"),(String)signing.get("requirements"));if(!versions().partySnapshotDigest(c,tenant,conf.id()).equals(signed.partySnapshotDigest()))throw stale();var rawGate=map(values.get("paymentGate"));var gate=new ContractVersionInput.PaymentGate((Boolean)rawGate.get("receiptRequiredBeforeTransfer"),rawGate.get("requiredMinor")==null?null:number(rawGate.get("requiredMinor")));
        var object=ports.documentObject(c,tenant,uuid(template.get("evidence_version_id")));if(!object.sha256().equals(hex(template.get("body_sha256"))))throw new Blocked("STALE_EVIDENCE");
        var fees=new ArrayList<String>();for(var line:terms.lines())fees.add(line.description()+"：CNY "+money(line.amountMinor())+(line.discount()?"（折扣）":""));fees.add("固定费用合计：CNY "+money(terms.totalMinor()));if(terms.conditionalFee()!=null){var fee=terms.conditionalFee();fees.add("条件性收费："+fee.basis()+"；费率 "+money(fee.rateBasisPoints())+"%；上限 CNY "+money(fee.capMinor())+"（不计入固定合计）");}else fees.add("无条件性费用");
        var fields=Map.of("customer",ports.customerName(c,tenant,oid),"parties",signatures().generationParties(c,tenant,oid,template.get("template_version_id")),"scope",terms.scope(),"fees",String.join("\n",fees),"payment",terms.paymentTerms(),"signing",signed.requirements(),"transfer",gate.receiptRequiredBeforeTransfer()?"本合同明确要求到账 CNY "+money(gate.requiredMinor())+" 后才可转案":"本合同不要求到账后才转案","basis","合同 "+root.get("contract_id")+"；第 "+(version==null?1:number(version.get("revision_no"))+1)+" 版；模板 "+template.get("document_code")+" v"+template.get("version_no"));
        String basis=HexFormat.of().formatHex(ContractCanonicalJson.digest(ContractCanonicalJson.encode(Map.of("input",ContractGenerationBasis.digest(payload),"source",source.selection().toString(),"templateSha256",object.sha256(),"fields",fields))));
        return new Generation(object,basis,fields,ports.now(c).plusSeconds(1800));
    }
    private static String money(long minor){return java.math.BigDecimal.valueOf(minor,2).toPlainString();}
    public Subject execute(Connection c,String action,Actor actor,Map<String,Object> payload)throws SQLException {
        requireCommandScope();
        if(c.getAutoCommit()||c.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED)throw new IllegalStateException("Contract command transaction required");
        UUID tenant=actor.tenantId(),oid=uuid(payload.get("opportunityId"));var o=opportunity(opportunity(c,tenant,oid,true));var owner=ports.responsibility(c,tenant,o);var conf=confirmation(c,tenant,oid);var root=root(c,tenant,oid);var v=currentVersion(c,tenant,root);var w=workflow(c,tenant,oid);var draft=latest(c,tenant,oid,"preparation_draft","previous_draft_id");
        if(owner==null||conf==null||o.revision()!=number(payload.get("expectedOpportunityRevision")))throw stale();expect(payload.get("responsibilityBasis"),owner.basis());expect(payload.get("customerConfirmation"),conf);expect(payload.get("expectedContract"),root==null?null:new Subject("contract.contract",uuid(root.get("contract_id")),number(root.get("revision")),null));expect(payload.get("expectedDraft"),draft==null?null:fact("contract.preparation_draft",uuid(draft.get("preparation_draft_id"))));expectVersion(payload.get("expectedVersion"),v);expect(payload.get("expectedWorkflow"),w==null?null:fact("contract.preparation_workflow",uuid(w.get("preparation_workflow_id"))));
        if(Set.of("REQUEST_CONTRACT_RECEIPT_REVIEW","RECORD_CONTRACT_RECEIPT_REVIEW","SUPPLEMENT_CONTRACT_RECEIPT").contains(action)){if(root==null||v==null)throw stale();return ports.executePayment(c,action,actor,o,uuid(root.get("contract_id")),uuid(v.get("contract_revision_id")),map(payload.get("values")));}
        var handling=negotiation();var currentHandling=handling.latest(c,tenant,oid);handling.expectCurrent(map(payload.get("values")),currentHandling);
        if(JdbcContractNegotiation.COMMANDS.contains(action))return handling.execute(c,action,actor,o,owner,root,v,w,map(payload.get("values")),protectedFacts(c,tenant,oid));
        handling.requireContinued(c,tenant,oid);
        if(action.equals("VERIFY_CONTRACT_EXECUTION_CONDITIONS"))return executions().verify(c,actor,o,owner,conf,root,v,map(payload.get("values")));
        if(JdbcManualSignatureWorkflow.COMMANDS.contains(action))return signatures().execute(c,action,actor,oid,o,owner,conf,root,v,w,map(payload.get("values")));
        if(!actions(c,actor,o,owner,conf,root,w).contains(action))throw new Blocked("NOT_AUTHORIZED");var values=map(payload.get("values"));var protectedSet=new ArrayList<>(protectedFacts(c,tenant,oid));selectedFacts(c,tenant,values,protectedSet);String authority=ContractWorkflowProtocol.authority(ContractWorkflowProtocol.Action.valueOf(action));if(!ports.permitted(c,actor,owner.organization(),protectedSet,authority))throw new Blocked("NOT_AUTHORIZED");
        if(action.equals("FORM_CONTRACT")&&map(values.get("document")).containsKey("generationProof")){
            var doc=map(values.get("document"));if(!Boolean.TRUE.equals(doc.get("humanConfirmed")))throw new Blocked("VALIDATION_FAILED");var candidate=generation(c,actor,payload);
            try{new ContractGenerationProof(protection).verify((String)doc.get("generationProof"),tenant,oid,actor.appointmentId(),candidate.basisDigest(),(String)doc.get("bodySha256"),ports.now(c));}catch(IllegalArgumentException invalid){throw new Blocked("CONTRACT_VERSION_BASIS_CHANGED");}
            var cleanDocument=new LinkedHashMap<>(doc);cleanDocument.remove("generationProof");cleanDocument.remove("humanConfirmed");values=new LinkedHashMap<>(values);values.put("document",cleanDocument);
        }
        boolean nonPassingReturn=action.equals("RECORD_CONTRACT_REVIEW")&&"NEED_INFO".equals(values.get("decision"))||action.equals("RECORD_CONTRACT_DECISION")&&"RETURNED".equals(values.get("decision"));
        if(!nonPassingReturn&&Set.of("REQUEST_CONTRACT_REVIEW","RECORD_CONTRACT_REVIEW","REQUEST_CONTRACT_APPROVAL","RECORD_CONTRACT_DECISION").contains(action))requireCurrentVersion(c,actor,owner,conf,v,protectedSet);
        var basis=new ContractPreparationRepository.Basis(o,owner.basis(),owner.owner(),conf.id());Subject result;String next=null,type=null;UUID nextOwner=owner.owner();
        switch(action){
            case "REQUEST_CONTRACT_PREPARATION" -> {var previous=latest(c,tenant,oid,"preparation_request","previous_request_id");result=drafts().request(c,tenant,basis,previous==null?null:uuid(previous.get("preparation_request_id")),commercial(map(values.get("commercial"))),(String)values.get("reason"));next="DIRECT_REVIEW";type="DECIDE_CONTRACT_PREPARATION";nextOwner=unique(ports.eligible(c,tenant,owner.organization(),protectedSet,"CONTRACT_PREPARATION_DECIDE"));}
            case "RECORD_CONTRACT_PREPARATION_DECISION" -> {var request=latest(c,tenant,oid,"preparation_request","previous_request_id");if(request==null)throw stale();String decision=decision(values);result=drafts().decide(c,tenant,uuid(request.get("preparation_request_id")),actor.appointmentId(),decision.equals("APPROVED"),null,(String)values.get("reason"));next=decision.equals("APPROVED")?"PREPARE":"DIRECT_RETURNED";type=decision.equals("APPROVED")?"PREPARE_CONTRACT":"REQUEST_CONTRACT_PREPARATION";}
            case "START_CONTRACT_PREPARATION" -> {var s=requireSource(c,tenant,oid);result=versions().startAnchor(c,tenant,actor.appointmentId(),new ContractPreparationSource.Basis(tenant,oid,conf.id(),s.commercial().digest()),s.selection());root=root(c,tenant,oid);next="PREPARE";type="PREPARE_CONTRACT";}
            case "SAVE_CONTRACT_DRAFT","FORM_CONTRACT" -> {
                var s=requireSource(c,tenant,oid);var terms=commercial(map(values.get("commercial")));if(!terms.digest().equals(s.commercial().digest()))throw new Blocked("COMMERCIAL_AUTHORIZATION_REQUIRED");var preparation=new LinkedHashMap<String,Object>();for(String key:List.of("document","signing","paymentGate"))if(values.containsKey(key))preparation.put(key,values.get(key));var saved=drafts().saveDraft(c,tenant,basis,s.selection(),draft==null?null:uuid(draft.get("preparation_draft_id")),terms,preparation);
                if(action.equals("SAVE_CONTRACT_DRAFT")){result=saved;break;}
                if(root==null)throw stale();var doc=map(values.get("document"));var signing=map(values.get("signing"));var gate=map(values.get("paymentGate"));var source=sources.resolve(c,new ContractPreparationSource.Basis(tenant,oid,conf.id(),terms.digest()),s.selection());
                var input=new ContractVersionInput(uuid(root.get("contract_id")),v==null?1:number(v.get("revision_no"))+1,v==null?null:uuid(v.get("contract_revision_id")),source,terms,new ContractVersionInput.Document(uuid(doc.get("evidenceVersionId")),(String)doc.get("bodySha256"),uuid(doc.get("templateVersionId")),((List<?>)doc.get("clauseVersionIds")).stream().map(JdbcContractWorkflowService::uuid).toList()),new ContractVersionInput.Signing((String)signing.get("partySnapshotDigest"),(String)signing.get("requirements")),new ContractVersionInput.PaymentGate((Boolean)gate.get("receiptRequiredBeforeTransfer"),gate.get("requiredMinor")==null?null:number(gate.get("requiredMinor"))));
                var configured=ports.approvalPolicy(c,tenant,owner.organization(),protectedSet);var policy=versions().currentPolicy(c,tenant,owner.organization());if(configured==null||policy==null||!configured.id().equals(policy.id())||!configured.digest().equals(policy.digest()))throw new Blocked("CONTRACT_APPROVAL_POLICY_REQUIRED");
                result=versions().formVersion(c,tenant,saved.id(),actor.appointmentId(),input,policy);next="SUBMIT_REVIEW";type="SUBMIT_CONTRACT_REVIEW";
            }
            case "REQUEST_CONTRACT_REVIEW" -> {if(v==null)throw stale();var previous=reviewContext(c,tenant,v);UUID previousId=previous==null?null:uuid(map(previous.get("selector")).get("id"));result=ContractReviewRepository.databaseBacked(protection,codec,ports::blockFinding,ports::reviewScopeComplete).request(c,tenant,uuid(v.get("contract_revision_id")),actor.appointmentId(),previousId,(String)values.getOrDefault("reason","提交签约前审查"));next="AWAIT_REVIEW";type="REVIEW_CONTRACT";nextOwner=unique(ports.eligible(c,tenant,owner.organization(),protectedSet,"CONTRACT_REVIEW"));}
            case "RECORD_CONTRACT_REVIEW" -> {if(v==null)throw stale();var request=reviewContext(c,tenant,v);if(request==null)throw stale();String decision=(String)values.get("decision");result=ContractReviewRepository.databaseBacked(protection,codec,ports::blockFinding,ports::reviewScopeComplete).decide(c,tenant,uuid(map(request.get("selector")).get("id")),actor.appointmentId(),decision,(String)values.get("reason"));switch(decision){case "CLEAR","WAIVED"->{next="SUBMIT_APPROVAL";type="SUBMIT_CONTRACT_APPROVAL";}case "NEED_INFO"->{next="REVIEW_SUPPLEMENT";type="SUPPLEMENT_CONTRACT_REVIEW";}case "BLOCKED"->{next="REVIEW_BLOCKED";type="PREPARE_CONTRACT";}default->throw new IllegalArgumentException("Invalid review result");}}
            case "REQUEST_CONTRACT_APPROVAL" -> {if(v==null)throw stale();var binding=row(c,"select * from contract.revision_review_binding where tenant_id=? and contract_revision_id=?",tenant,v.get("contract_revision_id"));if(binding==null)throw new Blocked("CONTRACT_REVIEW_REQUIRED");UUID id=newId(c);write(c,"insert into contract.revision_approval_request(tenant_id,revision_approval_request_id,revision,contract_revision_id,review_binding_id,requested_by_appointment_id,created_at) values(?,?,0,?,?,?,clock_timestamp())",tenant,id,v.get("contract_revision_id"),binding.get("revision_review_binding_id"),actor.appointmentId());result=fact("contract.revision_approval_request",id);next="AWAIT_APPROVAL";type="APPROVE_CONTRACT";nextOwner=nextApprover(c,tenant,v);}
            case "RECORD_CONTRACT_DECISION" -> {if(v==null)throw stale();var requirement=pendingRequirement(c,tenant,v);if(requirement==null||!Objects.equals(requirement.get("approver_appointment_id"),actor.appointmentId()))throw stale();var request=row(c,"select * from contract.revision_approval_request where tenant_id=? and contract_revision_id=?",tenant,v.get("contract_revision_id"));if(request==null)throw stale();String decision=decision(values),reason=new ContractPreparationRepository.Comment((String)values.get("reason")).value();UUID id=newId(c);String body=ContractCanonicalJson.encode(Map.of("reason",reason));write(c,"insert into contract.revision_approval_decision(tenant_id,revision_approval_decision_id,revision,requirement_id,review_binding_id,decision_code,decided_by_appointment_id,body_ciphertext,body_digest,approval_request_id,created_at) values(?,?,0,?,?,?,?,?,?,?,clock_timestamp())",tenant,id,requirement.get("revision_approval_requirement_id"),request.get("review_binding_id"),decision,actor.appointmentId(),protection.seal(tenant,oid,id,ContractProtection.Kind.DECISION,body),ContractCanonicalJson.digest(body),request.get("revision_approval_request_id"));result=fact("contract.revision_approval_decision",id);
                if(decision.equals("RETURNED")){next="RETURNED";type="PREPARE_CONTRACT";}else if(pendingRequirement(c,tenant,v)!=null){next="AWAIT_APPROVAL";type="APPROVE_CONTRACT";nextOwner=nextApprover(c,tenant,v);}else{write(c,"update contract.contract set approved_revision_id=?,revision=revision+1,changed_at=clock_timestamp() where tenant_id=? and contract_id=? and current_revision_id=? and revision=?",v.get("contract_revision_id"),tenant,root.get("contract_id"),v.get("contract_revision_id"),root.get("revision"));write(c,"insert into contract.signature_readiness(tenant_id,signature_readiness_id,revision,contract_revision_id,review_binding_id,state_code,created_at) values(?,?,0,?,?,'READY_FOR_SIGNATURE',clock_timestamp())",tenant,newId(c),v.get("contract_revision_id"),request.get("review_binding_id"));next="READY_FOR_SIGNATURE";}
            }
            default -> throw new IllegalArgumentException("Unknown contract action");
        }
        if(next!=null)transition(c,actor,o,owner,root,w,next,type,nextOwner,result,action);
        return result;
    }
    Source requireSource(Connection c,UUID tenant,UUID oid)throws SQLException{var source=source(c,tenant,oid);if(source==null)throw new Blocked("CONTRACT_PREPARATION_SOURCE_REQUIRED");return source;}
    private boolean versionDocumentsUsable(Connection c,UUID tenant,Map<String,Object> v)throws SQLException {
        if(!ports.documentUsable(c,tenant,uuid(v.get("body_evidence_version_id")),hex(v.get("body_sha256"))))return false;
        var documents=new ArrayList<>(rows(c,"select evidence_version_id,body_sha256 from contract.template_version where tenant_id=? and template_version_id=?",tenant,v.get("template_version_id")));
        documents.addAll(rows(c,"select d.evidence_version_id,d.body_sha256 from contract.revision_clause r join contract.clause_version d on d.tenant_id=r.tenant_id and d.clause_version_id=r.clause_version_id where r.tenant_id=? and r.contract_revision_id=?",tenant,v.get("contract_revision_id")));
        for(var d:documents)if(!ports.documentUsable(c,tenant,uuid(d.get("evidence_version_id")),hex(d.get("body_sha256"))))return false;
        return true;
    }
    void requireCurrentVersion(Connection c,Actor actor,Responsibility owner,Subject confirmation,Map<String,Object> v,List<Subject> facts)throws SQLException{
        if(v==null||!Objects.equals(v.get("customer_confirmation_id"),confirmation.id())||!versions().partySnapshotDigest(c,actor.tenantId(),confirmation.id()).equals(hex(v.get("party_snapshot_digest"))))throw new Blocked("CONTRACT_VERSION_BASIS_CHANGED");
        if(!versionDocumentsUsable(c,actor.tenantId(),v))throw new Blocked("STALE_EVIDENCE");
        var configured=ports.approvalPolicy(c,actor.tenantId(),owner.organization(),facts);if(configured==null)throw new Blocked("CONTRACT_APPROVAL_POLICY_REQUIRED");
        var requirements=rows(c,"select policy_id,policy_digest from contract.revision_approval_requirement where tenant_id=? and contract_revision_id=?",actor.tenantId(),v.get("contract_revision_id"));
        if(requirements.isEmpty()||requirements.stream().anyMatch(r->!configured.id().equals(r.get("policy_id"))||!configured.digest().equals(hex(r.get("policy_digest")))))throw new Blocked("CONTRACT_APPROVAL_POLICY_CHANGED");
    }
    private static String decision(Map<String,Object> values){String value=(String)values.get("decision");if(!Set.of("APPROVED","RETURNED").contains(value))throw new IllegalArgumentException("Explicit decision required");return value;}
    private static UUID unique(List<UUID> list){if(list.size()!=1)throw new Blocked("CONTRACT_RESPONSIBILITY_REQUIRED");return list.getFirst();}
    private static UUID newId(Connection c)throws SQLException{return uuid(row(c,"select uuidv7() id").get("id"));}
    private Map<String,Object> pendingRequirement(Connection c,UUID tenant,Map<String,Object> v)throws SQLException{return row(c,"select q.* from contract.revision_approval_requirement q where q.tenant_id=? and q.contract_revision_id=? and not exists(select 1 from contract.revision_approval_decision d where d.tenant_id=q.tenant_id and d.requirement_id=q.revision_approval_requirement_id) order by q.requirement_code limit 1",tenant,v.get("contract_revision_id"));}
    private UUID nextApprover(Connection c,UUID tenant,Map<String,Object> v)throws SQLException{var q=pendingRequirement(c,tenant,v);if(q==null)throw stale();return uuid(q.get("approver_appointment_id"));}
    static void expect(Object raw,Subject exact){if(raw==null){if(exact!=null)throw stale();return;}if(exact==null)throw stale();var m=map(raw);if(!Objects.equals(uuid(m.get("id")),exact.id())||number(m.get("revision"))!=exact.revision())throw stale();}
    static void expectVersion(Object raw,Map<String,Object> v){if(raw==null){if(v!=null)throw stale();return;}var m=map(raw);if(v==null||!uuid(m.get("id")).equals(uuid(v.get("contract_revision_id")))||!hex(v.get("content_digest")).equals(m.get("hash")))throw stale();}
    private void selectedFacts(Connection c,UUID tenant,Map<String,Object> values,List<Subject> facts)throws SQLException{if(values.get("document") instanceof Map<?,?> d){for(String key:List.of("evidenceVersionId","templateVersionId")){Object id=d.get(key);if(id instanceof String s&&!s.isBlank())facts.addAll(key.equals("evidenceVersionId")?ports.documentFacts(c,tenant,uuid(id)):approvedDocumentFacts(c,tenant,"template_version",uuid(id)));}if(d.get("clauseVersionIds") instanceof List<?> ids)for(Object id:ids)facts.addAll(approvedDocumentFacts(c,tenant,"clause_version",uuid(id)));}}
    void transition(Connection c,Actor actor,Subject o,Responsibility owner,Map<String,Object> root,Map<String,Object> previous,String stage,String type,UUID nextOwner,Subject result,String action)throws SQLException {
        UUID tenant=actor.tenantId();Instant now=ports.now(c);Task prior=previous==null||previous.get("task_id")==null?null:ports.currentTask(c,tenant,uuid(previous.get("task_id")));Task next=null;
        if(prior==null&&previous==null&&action.equals("START_CONTRACT_PREPARATION")){var inherited=ports.active(c,tenant,o).stream().filter(t->t.type().equals("PREPARE_CONTRACT")&&t.owner().equals(owner.owner())&&t.state().equals("OPEN")).toList();if(inherited.size()>1)throw stale();if(!inherited.isEmpty())prior=inherited.getFirst();}
        if(type!=null){var authority=type.equals("DECIDE_CONTRACT_PREPARATION")?"CONTRACT_PREPARATION_DECIDE":type.equals("REVIEW_CONTRACT")?"CONTRACT_REVIEW":type.equals("APPROVE_CONTRACT")?"CONTRACT_APPROVE":"CONTRACT_PREPARE";if(!ports.eligible(c,tenant,owner.organization(),protectedFacts(c,tenant,o.id()),authority).contains(nextOwner))throw new Blocked("CONTRACT_RESPONSIBILITY_REQUIRED");}
        // Starting an anchor is not completion of the existing prepare-version responsibility.
        if(prior!=null&&action.equals("START_CONTRACT_PREPARATION")&&prior.type().equals("PREPARE_CONTRACT")&&prior.state().equals("OPEN"))next=prior;
        else {
            Task takeover=null;
            if(prior!=null){
                if(!prior.owner().equals(actor.appointmentId())||!prior.state().equals("OPEN"))throw stale();
                if((action.equals("REQUEST_CONTRACT_PREPARATION")&&!prior.type().equals("REQUEST_CONTRACT_PREPARATION"))||(action.equals("FORM_CONTRACT")&&!prior.type().equals("PREPARE_CONTRACT")))ports.cancelForContract(c,tenant,prior,"CONTRACT_SUPERSEDED",now);
                else ports.complete(c,tenant,prior,result,now);
            }else for(var task:ports.active(c,tenant,o))if(task.owner().equals(owner.owner())&&(task.type().equals("PROGRESS_OPPORTUNITY")||task.type().contains("QUOTE"))){
                ports.cancelForContract(c,tenant,task,"CONTRACT_WORKFLOW_TAKEOVER",now);
                if(task.owner().equals(nextOwner)&&takeover==null)takeover=task;
            }
            if(type!=null)next=takeover==null?ports.create(c,tenant,type,nextOwner,o,ZONE,now):ports.createContractTakingOver(c,tenant,type,nextOwner,o,takeover,ZONE,now);
        }
        write(c,"insert into contract.preparation_workflow(tenant_id,preparation_workflow_id,revision,opportunity_id,contract_id,previous_workflow_id,stage_code,owner_appointment_id,task_id,prior_task_id,created_by_appointment_id,created_at) values(?,?,0,?,?,?,?,?,?,?,?,clock_timestamp())",tenant,newId(c),o.id(),root==null?null:root.get("contract_id"),previous==null?null:previous.get("preparation_workflow_id"),stage,nextOwner,next==null?null:next.selector().id(),prior==null?null:prior.selector().id(),actor.appointmentId());
    }
}














