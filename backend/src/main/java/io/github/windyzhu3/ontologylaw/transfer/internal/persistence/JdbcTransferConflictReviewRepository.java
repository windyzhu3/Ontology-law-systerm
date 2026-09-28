package io.github.windyzhu3.ontologylaw.transfer.internal.persistence;
import io.github.windyzhu3.ontologylaw.transfer.*;
import java.security.*;import java.nio.charset.StandardCharsets;
import java.sql.*;import java.util.*;
public final class JdbcTransferConflictReviewRepository implements TransferConflictReviewRepository {
 private final Completeness completeness;
 private final Protection protection;private final BlockingDecision blocking;private final Codec codec;
 public JdbcTransferConflictReviewRepository(Completeness completeness,Codec codec){this(completeness,codec,null,null);}
 public JdbcTransferConflictReviewRepository(Completeness completeness,Codec codec,Protection protection,BlockingDecision blocking){this.completeness=Objects.requireNonNull(completeness);this.protection=protection;this.blocking=blocking;this.codec=Objects.requireNonNull(codec);}
 private static PreparedStatement prepare(Connection c,String sql,Object...args)throws SQLException{var p=c.prepareStatement(sql);for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);return p;}
 private static byte[] digest(String value){try{return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
 private static boolean known(ScopeParty p){return Set.of("CLIENT","OPPONENT").contains(p.role());}
 private static String hex(byte[] bytes){return HexFormat.of().formatHex(bytes);}
 private String hash(Object value){return hex(digest(codec.encode(value)));}
 private static List<Map<String,Object>> canonical(List<ScopeParty> parties){return parties.stream().map(p->Map.<String,Object>of("id",p.sourceId().toString(),"sourceType",p.sourceType(),"partyId",p.partyId().toString(),"revision",p.revision(),"role",p.role(),"hash",p.digest())).toList();}
 private static List<ScopeParty> read(Connection c,String sql,Object...args)throws SQLException{var out=new ArrayList<ScopeParty>();try(var p=prepare(c,sql,args);var r=p.executeQuery()){while(r.next())out.add(new ScopeParty(r.getObject(1,UUID.class),r.getString(2),r.getObject(3,UUID.class),r.getLong(4),r.getString(5),hex(r.getBytes(6))));}return out;}
 public Scan inspect(Connection c,UUID tenant,UUID submission)throws SQLException{
  if(c.getAutoCommit()||c.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED)throw new IllegalStateException("Transfer review transaction required");
  UUID opportunity,version,confirmation;String legalNeed,body;
  try(var p=prepare(c,"""
   select s.opportunity_id,s.contract_revision_id,s.customer_confirmation_id,s.legal_need_context_digest,s.body_digest
   from transfer.submission s join transfer.transfer_request r on r.tenant_id=s.tenant_id and r.transfer_request_id=s.transfer_request_id
   join contract.contract k on k.tenant_id=r.tenant_id and k.contract_id=r.contract_id
   join opportunity.opportunity o on o.tenant_id=s.tenant_id and o.opportunity_id=s.opportunity_id
   where s.tenant_id=? and s.submission_id=? and r.accepted_snapshot_id is null and o.closed_at is null
    and k.current_revision_id=s.contract_revision_id and k.approved_revision_id=s.contract_revision_id and k.contract_termination_id is null
    and k.contract_execution_id=r.contract_execution_id and k.activation_source_hash=r.deal_activation_digest and k.deal_activated_at=r.deal_activated_at
    and o.legal_need_digest=s.legal_need_context_digest
    and not exists(select 1 from opportunity.customer_requirement_confirmation n where n.tenant_id=s.tenant_id and n.previous_confirmation_id=s.customer_confirmation_id)
    and exists(select 1 from transfer.workflow w where w.tenant_id=s.tenant_id and w.submission_id=s.submission_id and w.target_stage_code in ('REVIEW_TRANSFER','INTAKE') and not exists(select 1 from transfer.workflow n where n.tenant_id=w.tenant_id and n.previous_workflow_id=w.workflow_id))
   """,tenant,submission);var r=p.executeQuery()){
   if(!r.next())throw new TransferWorkflowService.Blocked("STALE_TRANSFER_REVIEW_BASIS");opportunity=r.getObject(1,UUID.class);version=r.getObject(2,UUID.class);confirmation=r.getObject(3,UUID.class);legalNeed=hex(r.getBytes(4));body=hex(r.getBytes(5));
  }
  var scope=new ArrayList<>(read(c,"select contract_participation_id,'contract.contract_participation',party_id,party_revision,context_role_code,party_snapshot_digest from contract.contract_participation where tenant_id=? and contract_revision_id=? order by contract_participation_id",tenant,version));
  try(var p=prepare(c,"""
   select x.customer_requirement_participant_id,x.party_id,x.party_revision,x.role,x.profile_version_id,p.revision,p.status
   from opportunity.customer_requirement_participant x join party.party p on p.tenant_id=x.tenant_id and p.party_id=x.party_id
   where x.tenant_id=? and x.confirmation_id=? order by x.customer_requirement_participant_id
   """,tenant,confirmation);var r=p.executeQuery()){
   while(r.next()){
    if(r.getLong(3)!=r.getLong(6)||!"ACTIVE".equals(r.getString(7)))throw new TransferWorkflowService.Blocked("STALE_TRANSFER_PARTY");
    scope.add(new ScopeParty(r.getObject(1,UUID.class),"opportunity.customer_requirement_participant",r.getObject(2,UUID.class),r.getLong(3),r.getString(4),hex(digest(r.getObject(5,UUID.class).toString()))));
   }
  }
  var corpus=read(c,"""
   select p.opportunity_participation_id,'opportunity.opportunity_participation' source_type,p.party_id,p.party_revision,p.context_role_code,p.party_snapshot_digest
   from opportunity.opportunity_participation p where p.tenant_id=? and p.opportunity_id<>? and p.participation_set_revision=(select max(n.participation_set_revision) from opportunity.opportunity_participation n where n.tenant_id=p.tenant_id and n.opportunity_id=p.opportunity_id)
   union all select p.contract_participation_id,'contract.contract_participation',p.party_id,p.party_revision,p.context_role_code,p.party_snapshot_digest
   from contract.contract_participation p join contract.contract_revision v on v.tenant_id=p.tenant_id and v.contract_revision_id=p.contract_revision_id join contract.contract r on r.tenant_id=v.tenant_id and r.contract_id=v.contract_id
   where p.tenant_id=? and r.opportunity_id<>? order by source_type,opportunity_participation_id
   """,tenant,opportunity,tenant,opportunity);
  var matters=new ArrayList<Map<String,Object>>();try(var p=prepare(c,"select transfer_request_id,revision,matter_id,accepted_snapshot_id from transfer.transfer_request where tenant_id=? order by transfer_request_id",tenant);var r=p.executeQuery()){while(r.next()){var value=new TreeMap<String,Object>();for(int i=1;i<=4;i++)value.put(r.getMetaData().getColumnName(i),Objects.toString(r.getObject(i),null));matters.add(value);}}
  var candidates=new LinkedHashMap<String,Candidate>();for(var party:scope)for(var match:corpus)if(party.partyId().equals(match.partyId())&&known(party)&&known(match)&&!party.role().equals(match.role()))candidates.put(party.partyId()+"|"+party.role()+"|"+match.sourceId(),new Candidate(party,match));
  boolean complete=completeness.complete(c,tenant,opportunity)&&!scope.isEmpty()&&scope.stream().anyMatch(p->p.role().equals("CLIENT"))&&scope.stream().allMatch(JdbcTransferConflictReviewRepository::known)&&corpus.stream().filter(p->scope.stream().anyMatch(a->a.partyId().equals(p.partyId()))).allMatch(JdbcTransferConflictReviewRepository::known);
  return new Scan(submission,opportunity,body,legalNeed,scope,corpus,List.copyOf(candidates.values()),complete,hash(Map.of("profile","R2_TRANSFER_SCOPE_V1","submission",submission.toString(),"body",body,"parties",canonical(scope))),hash(Map.of("profile","R2_TRANSFER_CORPUS_V1","parties",canonical(corpus),"matters",matters)));
 }

 private static void write(Connection c,String sql,Object...args)throws SQLException{try(var p=prepare(c,sql,args)){if(p.executeUpdate()!=1)throw new TransferWorkflowService.Blocked("STALE_SUBJECT");}}
 private static UUID fresh(Connection c)throws SQLException{try(var p=c.prepareStatement("select uuidv7()");var r=p.executeQuery()){r.next();return r.getObject(1,UUID.class);}}
 public io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject record(Connection c,UUID tenant,UUID workflow,UUID actor,TransferConflictReviewInput input,TransferWorkflowService.Draft draft)throws SQLException{
  Objects.requireNonNull(protection);UUID submission,request;
  try(var p=prepare(c,"select submission_id,transfer_request_id from transfer.workflow where tenant_id=? and workflow_id=? and stage_code='REVIEW_TRANSFER' and owner_appointment_id=?",tenant,workflow,actor);var r=p.executeQuery()){if(!r.next())throw new TransferWorkflowService.Blocked("NOT_AUTHORIZED");submission=r.getObject(1,UUID.class);request=r.getObject(2,UUID.class);}
  var scan=inspect(c,tenant,submission);if(!scan.permittedOutcomes().contains(input.outcome()))throw new TransferWorkflowService.Blocked("TRANSFER_REVIEW_OUTCOME_UNAVAILABLE");
  UUID conflict=fresh(c);String initial=input.outcome().equals("BLOCKED")?"FINDINGS":input.outcome();
  String rule="R2_TRANSFER_EXACT_PARTY_ROLE_V1";byte[] ruleHash=digest(rule+"|exact opposing CLIENT OPPONENT identities|unknown scope requires information|manual review|no automatic waiver");
  write(c,"insert into conflict.conflict_review(tenant_id,conflict_review_id,review_type_code,legal_need_digest,review_contract_code,review_contract_version,scope_hash,rule_set_code,rule_set_revision,rule_set_hash,corpus_code,corpus_revision,corpus_hash,initial_conclusion_code,finding_count,reviewed_at,revision,created_at,trigger_fact_type,trigger_fact_id,trigger_fact_hash) values(?,?,'PRE_TRANSFER',?,'R2_TRANSFER_ID_REVIEW_V1',1,?,?,1,?,'R2_TRANSFER_CORPUS_V1',0,?,?,?,clock_timestamp(),0,clock_timestamp(),'transfer.submission',?,?)",tenant,conflict,HexFormat.of().parseHex(scan.legalNeedDigest()),HexFormat.of().parseHex(scan.scopeDigest()),rule,ruleHash,HexFormat.of().parseHex(scan.corpusDigest()),initial,initial.equals("FINDINGS")?scan.candidates().size():0,submission,HexFormat.of().parseHex(scan.submissionDigest()));
  var ids=new HashMap<UUID,UUID>();var grouped=new LinkedHashMap<String,ScopeParty>();for(var party:scan.scope())grouped.put(party.partyId()+"|"+party.role(),party);for(var party:grouped.values()){UUID id=fresh(c);for(var source:scan.scope())if(source.partyId().equals(party.partyId())&&source.role().equals(party.role()))ids.put(source.sourceId(),id);write(c,"insert into conflict.conflict_review_party(tenant_id,conflict_review_party_id,conflict_review_id,party_id,scope_role_code,party_snapshot_hash,created_at,source_item_type,source_item_id,source_item_hash) values(?,?,?,?,?,?,clock_timestamp(),?,?,?)",tenant,id,conflict,party.partyId(),party.role(),HexFormat.of().parseHex(party.digest()),party.sourceType(),party.sourceId(),HexFormat.of().parseHex(party.digest()));}
  if(initial.equals("FINDINGS")){
   var findings=new ArrayList<io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject>();int no=0;
   for(var candidate:scan.candidates()){
    var matched=scan.corpus().stream().filter(p->p.sourceId().equals(candidate.matched().sourceId())).findFirst().orElseThrow();UUID id=fresh(c);byte[] digest=digest(conflict+"|"+candidate.scope().sourceId()+"|"+matched.sourceId()+"|"+rule);
    write(c,"insert into conflict.conflict_finding(tenant_id,conflict_finding_id,conflict_review_id,finding_no,conflict_review_party_id,rule_code,rule_revision,risk_classification_code,finding_summary,finding_digest,created_at,matched_fact_type,matched_fact_id,matched_fact_hash) values(?,?,?,?,?,?,1,'EXACT_OPPOSING_PARTY_ROLE','Exact opposing identity in protected business context',?,clock_timestamp(),?,?,?)",tenant,id,conflict,++no,ids.get(candidate.scope().sourceId()),rule,digest,matched.sourceType(),matched.sourceId(),HexFormat.of().parseHex(matched.digest()));
    findings.add(new io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject("conflict.conflict_finding",id,null,Base64.getUrlEncoder().withoutPadding().encodeToString(digest)));
   }
   var decision=Objects.requireNonNull(blocking).block(c,tenant,scan.opportunityId(),actor,findings.getFirst(),input.explanation());
   byte[] resolution=digest(codec.encode(Map.of("review",conflict.toString(),"scope",scan.scopeDigest(),"corpus",scan.corpusDigest(),"decision",decision.id().toString(),"decisionHash",decision.hash(),"findings",findings.stream().map(f->f.id().toString()).toList())));
   write(c,"update conflict.conflict_review set resolution_code='BLOCKED',resolution_digest=?,resolved_at=clock_timestamp(),revision=revision+1 where tenant_id=? and conflict_review_id=? and revision=0",resolution,tenant,conflict);
  }
  UUID id=fresh(c);String body=codec.encode(Map.of("outcome",input.outcome(),"explanation",input.explanation(),"scopeChecked",true,"scope",scan.scopeDigest(),"corpus",scan.corpusDigest(),"submission",submission.toString()));
  write(c,"insert into transfer.review(tenant_id,review_id,transfer_request_id,opportunity_id,workflow_id,submission_id,conflict_review_id,outcome_code,confirmed_action_draft_id,action_draft_digest,scope_digest,corpus_digest,recorded_by,body_ciphertext,body_digest,created_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,clock_timestamp())",tenant,id,request,scan.opportunityId(),workflow,submission,conflict,input.outcome(),draft.id(),HexFormat.of().parseHex(draft.digest()),HexFormat.of().parseHex(scan.scopeDigest()),HexFormat.of().parseHex(scan.corpusDigest()),actor,protection.seal(tenant,scan.opportunityId(),id,body),digest(body));
  if(!input.outcome().equals("CLEAR")){
   UUID item=fresh(c);String content=codec.encode(Map.of("requirement",input.outcome().equals("BLOCKED")?"RESOLVE_CONFLICT":"COMPLETE_SUBJECT_EVIDENCE","explanation",input.explanation()));
   write(c,"insert into transfer.review_return_item(tenant_id,review_return_item_id,transfer_request_id,opportunity_id,review_id,requirement_code,body_ciphertext,body_digest,created_at) values(?,?,?,?,?,?,?,?,clock_timestamp())",tenant,item,request,scan.opportunityId(),id,input.outcome().equals("BLOCKED")?"RESOLVE_CONFLICT":"COMPLETE_SUBJECT_EVIDENCE",protection.seal(tenant,scan.opportunityId(),item,content),digest(content));
  }
  return new io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject("transfer.review",id,0L,null);
 }
}
