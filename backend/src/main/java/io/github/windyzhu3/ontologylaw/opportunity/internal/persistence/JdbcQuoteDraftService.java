package io.github.windyzhu3.ontologylaw.opportunity.internal.persistence;
import io.github.windyzhu3.ontologylaw.opportunity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import io.github.windyzhu3.ontologylaw.opportunity.QuoteCanonicalJson;
import java.sql.*;
import java.time.OffsetDateTime;
import java.security.MessageDigest;
import java.util.*;

public final class JdbcQuoteDraftService implements QuoteDraftService {
 private final OpportunityProgressProtection protection;private final Codec codec;
 public JdbcQuoteDraftService(OpportunityProgressProtection protection,Codec codec){this.protection=Objects.requireNonNull(protection);this.codec=Objects.requireNonNull(codec);}
 public Version save(Connection c,UUID tenant,Input in)throws SQLException{
  if(c.getAutoCommit()||c.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED)throw new SQLException("Quote draft requires a command transaction","25001");
  require(in.opportunity(),"opportunity.opportunity");require(in.responsibility(),in.responsibility().type());
  if(!Set.of("opportunity.opportunity","opportunity.responsibility_handoff").contains(in.responsibility().type()))throw new IllegalArgumentException("Invalid responsibility");
  require(in.confirmation(),OpportunityCustomerRequirementsService.CONFIRMATION);
  if(in.confirmation().revision()!=0)throw new IllegalArgumentException("Immutable confirmation required");
  if(in.previous()!=null){require(in.previous(),TYPE);if(in.previous().revision()!=0)throw new IllegalArgumentException("Immutable draft required");}
  Objects.requireNonNull(in.owner());
  if(in.document()==null||!Set.of("currency","scope","lines","paymentTerms","validUntil","conditionalFee").containsAll(in.document().keySet()))throw new IllegalArgumentException("Unsupported quote draft fields");
  String body=codec.encode(in.document());
  if(body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>130000)throw new IllegalArgumentException("Quote draft too large");
  UUID id;try(var p=c.prepareStatement("select uuidv7()")){try(var r=p.executeQuery()){r.next();id=r.getObject(1,UUID.class);}}
  byte[] encrypted=protection.encryptQuote(tenant,in.opportunity().id(),id,true,body);
  try(var p=c.prepareStatement("insert into opportunity.quote_draft(tenant_id,quote_draft_id,revision,opportunity_id,opportunity_revision,responsibility_type,responsibility_id,responsibility_revision,owner_appointment_id,customer_confirmation_id,previous_draft_id,body_ciphertext,body_digest,created_at) values(?,?,0,?,?,?,?,?,?,?,?,?,?,clock_timestamp())")){
   Object[] values={tenant,id,in.opportunity().id(),in.opportunity().revision(),in.responsibility().type(),in.responsibility().id(),in.responsibility().revision(),in.owner(),in.confirmation().id(),in.previous()==null?null:in.previous().id(),encrypted,QuoteCanonicalJson.digest(body)};
   for(int i=0;i<values.length;i++)p.setObject(i+1,values[i]);p.executeUpdate();
  }
  return read(c,tenant,id);
 }
 private static void require(Subject s,String type){if(s==null||!type.equals(s.type())||s.id()==null||s.revision()==null||s.revision()<0||s.revision()>9007199254740991L||s.hash()!=null)throw new IllegalArgumentException("Exact selector required");}
 @SuppressWarnings("unchecked") public Version read(Connection c,UUID tenant,UUID id)throws SQLException{
  try(var p=c.prepareStatement("select * from opportunity.quote_draft where tenant_id=? and quote_draft_id=?")){p.setObject(1,tenant);p.setObject(2,id);try(var r=p.executeQuery()){
   if(!r.next())return null;UUID opportunity=r.getObject("opportunity_id",UUID.class);String clear;
   Map<String,Object> document;
   try{clear=protection.decryptQuote(tenant,opportunity,id,true,r.getBytes("body_ciphertext"));if(!MessageDigest.isEqual(QuoteCanonicalJson.digest(clear),r.getBytes("body_digest")))throw new IllegalArgumentException();document=(Map<String,Object>)QuoteCanonicalJson.freeze(codec.decode(clear));}
   catch(RuntimeException bad){throw new SQLException("Invalid protected quote draft","22000");}
   var previous=r.getObject("previous_draft_id",UUID.class);
   return new Version(new Subject(TYPE,id,0L,null),new Subject("opportunity.opportunity",opportunity,r.getLong("opportunity_revision"),null),new Subject(r.getString("responsibility_type"),r.getObject("responsibility_id",UUID.class),r.getLong("responsibility_revision"),null),r.getObject("owner_appointment_id",UUID.class),new Subject(OpportunityCustomerRequirementsService.CONFIRMATION,r.getObject("customer_confirmation_id",UUID.class),0L,null),previous==null?null:new Subject(TYPE,previous,0L,null),document,r.getObject("created_at",OffsetDateTime.class).toInstant());
  }}
 }
 public Version latest(Connection c,UUID tenant,Subject opportunity,Subject responsibility,UUID owner)throws SQLException{
  try(var p=c.prepareStatement("select d.quote_draft_id from opportunity.quote_draft d where d.tenant_id=? and d.opportunity_id=? and d.responsibility_type=? and d.responsibility_id=? and d.responsibility_revision=? and d.owner_appointment_id=? and not exists(select 1 from opportunity.quote_draft n where n.tenant_id=d.tenant_id and n.previous_draft_id=d.quote_draft_id)")){
   Object[] a={tenant,opportunity.id(),responsibility.type(),responsibility.id(),responsibility.revision(),owner};for(int i=0;i<a.length;i++)p.setObject(i+1,a[i]);try(var r=p.executeQuery()){return r.next()?read(c,tenant,r.getObject(1,UUID.class)):null;}
  }
 }
}
