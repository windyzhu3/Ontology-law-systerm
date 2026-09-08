package io.github.windyzhu3.ontologylaw.execution;

import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.IdentityBootstrapService.*;
import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
import java.sql.*;
import java.util.*;
import java.time.*;
import io.github.windyzhu3.ontologylaw.execution.internal.persistence.JooqIdentityBootstrapStore;
import io.github.windyzhu3.ontologylaw.execution.internal.persistence.SensitiveReadClock;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;

/** Invoked only by the separate closed-HTTP offline command, never an API bean. */
public final class IdentityBootstrapRuntime {
    public record Outcome(String mode,UUID tenantId,UUID receiptId,Map<String,Integer> plannedDelta) {}
    private final UUID tenant;private final BootstrapCandidateProtection.Binding binding;private final BootstrapCandidateProtection candidates;
    private final ExternalSubjectProtection subjects;private final IdentityProviderDirectory directory;private final AuditAppender audit;
    private static final Map<String,Integer> DELTA=Map.of("tenant",1,"organization_unit",1,"principal",1,"appointment",1,"authority_grant",4,"command_execution_slot",1,"command_receipt",1,"audit_entry",1);
    public IdentityBootstrapRuntime(UUID trustedTenant,BootstrapCandidateProtection.Binding binding,BootstrapCandidateProtection candidates,ExternalSubjectProtection subjects,IdentityProviderDirectory directory,AuditAppender audit){
        tenant=Objects.requireNonNull(trustedTenant);this.binding=Objects.requireNonNull(binding);this.candidates=Objects.requireNonNull(candidates);this.subjects=Objects.requireNonNull(subjects);this.directory=Objects.requireNonNull(directory);this.audit=Objects.requireNonNull(audit);
        if(!binding.issuer().equals(directory.issuer()))throw new IllegalArgumentException("BOOTSTRAP_CONFIGURATION_INVALID");
    }
    public Outcome run(Connection connection,Manifest manifest,boolean dryRun)throws SQLException {
        return run(connection,manifest,dryRun,false);
    }
    public Outcome verifyOriginal(Connection connection,Manifest manifest)throws SQLException{return run(connection,manifest,false,true);}
    private Outcome run(Connection connection,Manifest manifest,boolean dryRun,boolean verifyOnly)throws SQLException {
        if(!binding.equals(manifest.binding()))throw new IllegalArgumentException("BOOTSTRAP_MANIFEST_INVALID");
        var candidate=candidates.verify(manifest.providerUserSelector(),binding);byte[] hmac=subjects.digest(tenant,candidate.subject());
        byte[] digest=CanonicalJson.digest(redacted(manifest,hmac)),scope=CanonicalJson.digest("R1_IDENTITY_BOOTSTRAP_SCOPE_V1:"+tenant+":"+binding.tenantCode());String hex=HexFormat.of().formatHex(digest);
        return inTransaction(connection,Capability.QUERY,c->{
            var store=new JooqIdentityBootstrapStore(c);store.tenantCodeFence(binding.tenantCode());
            var identity=IdentityBootstrapService.databaseBacked();boolean initialized=identity.initialized(c,tenant,binding.tenantCode());
            R1BusinessFence.databaseBacked().exclusive(c,tenant);store.commandFence(tenant,manifest.commandId());AuthorizationService.databaseBacked().lockForMutation(c,tenant);
            // Re-read after the complete lock sequence. An original commit is verified before freshness/network checks.
            initialized=identity.initialized(c,tenant,binding.tenantCode());
            var closure=store.original(tenant,manifest.commandId(),scope,digest);
            if(initialized) {
                var original=audit.bootstrapOriginal(c,tenant,manifest.commandId());
                if(closure==null||original==null||!hex.equals(original.manifestDigest())||!binding.operatorAssertion().equals(original.operatorAssertion())||!closure.completedAt().equals(original.facts().createdAt()))throw new SQLException("BOOTSTRAP_ORIGINAL_STATE_CONFLICT","23000");
                identity.verify(c,manifest,hmac,original.facts());return new Outcome("VERIFIED_ORIGINAL",tenant,closure.receiptId(),Map.of());
            }
            if(verifyOnly||closure!=null||audit.bootstrapOriginal(c,tenant,manifest.commandId())!=null)throw new SQLException("BOOTSTRAP_ORIGINAL_STATE_CONFLICT","23000");
            Instant now=SensitiveReadClock.now(c);candidate.requireFresh(now);if(manifest.effectiveFrom().isAfter(now))throw new IllegalArgumentException("BOOTSTRAP_MANIFEST_INVALID");
            if(!candidate.subject().equals(directory.enabled(candidate.subject()).subject()))throw new IllegalArgumentException("BOOTSTRAP_CANDIDATE_INVALID");
            candidate.requireFresh(SensitiveReadClock.now(c));
            if(dryRun)return new Outcome("DRY_RUN",tenant,null,DELTA);
            setLocalRole(c,Capability.COMMAND);var facts=identity.create(c,tenant,manifest,hmac);UUID receipt=store.write(tenant,manifest.commandId(),scope,digest,facts.createdAt());
            setLocalRole(c,Capability.AUDIT);audit.append(c,new AuditAppender.BootstrapEntry(UUID.randomUUID(),manifest.commandId(),UUID.randomUUID(),hex,binding.operatorAssertion(),facts));
            return new Outcome("CREATED",tenant,receipt,DELTA);
        });
    }
    private String redacted(Manifest m,byte[] hmac) {
        var fields=new TreeMap<String,Object>();fields.put("profile",m.profile());fields.put("commandId",m.commandId().toString());fields.put("tenantId",tenant.toString());fields.put("tenantCode",m.tenantCode());fields.put("tenantDisplayName",m.tenantDisplayName());fields.put("rootCode",m.rootCode());fields.put("rootDisplayName",m.rootDisplayName());fields.put("identityProviderCode",m.identityProviderCode());fields.put("issuer",m.issuer());fields.put("providerUserSelectorDigest",HexFormat.of().formatHex(CanonicalJson.digest(m.providerUserSelector())));fields.put("subjectHmac",Base64.getUrlEncoder().withoutPadding().encodeToString(hmac));fields.put("principalDisplayName",m.principalDisplayName());fields.put("effectiveFrom",m.effectiveFrom().toString());fields.put("operatorAssertion",m.operatorAssertion());return CanonicalJson.encode(fields);
    }
}
