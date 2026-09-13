package io.github.windyzhu3.ontologylaw.identity;

import java.sql.*;
import java.time.*;
import java.util.*;

/** Exact initial identity delta only. No generic lifecycle, arbitrary grants, or online bootstrap. */
public interface IdentityBootstrapService {
    record Manifest(String profile,UUID commandId,String tenantCode,String tenantDisplayName,String rootCode,String rootDisplayName,
            String identityProviderCode,String issuer,String providerUserSelector,String principalDisplayName,Instant effectiveFrom,String operatorAssertion) {
        public Manifest {
            if(!"R1_IDENTITY_BOOTSTRAP_V1".equals(profile)||commandId==null||effectiveFrom==null||providerUserSelector==null||providerUserSelector.isBlank()||providerUserSelector.length()>8192)throw new IllegalArgumentException("BOOTSTRAP_MANIFEST_INVALID");
            for(String code:List.of(tenantCode,rootCode,identityProviderCode))if(!code.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}"))throw new IllegalArgumentException("BOOTSTRAP_MANIFEST_INVALID");
            for(String text:List.of(tenantDisplayName,rootDisplayName,principalDisplayName,operatorAssertion))if(text.isBlank()||text.codePointCount(0,text.length())>200||text.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("BOOTSTRAP_MANIFEST_INVALID");
            tenantDisplayName=tenantDisplayName.strip();rootDisplayName=rootDisplayName.strip();principalDisplayName=principalDisplayName.strip();operatorAssertion=operatorAssertion.strip();
            Objects.requireNonNull(issuer);
        }
        public String toString(){return "BootstrapManifest[restricted]";}
        public BootstrapCandidateProtection.Binding binding(){return new BootstrapCandidateProtection.Binding(operatorAssertion,tenantCode,identityProviderCode,issuer);}
    }
    record Facts(UUID tenant,UUID root,UUID principal,UUID appointment,List<UUID> grants,Instant createdAt) {
        public Facts{grants=List.copyOf(grants);if(grants.size()!=4||new HashSet<>(grants).size()!=4)throw new IllegalArgumentException("Invalid bootstrap facts");}
    }
    List<String> MANAGEMENT_CODES=List.of("IDENTITY_PRINCIPAL_MANAGE","IDENTITY_ORGANIZATION_MANAGE","IDENTITY_APPOINTMENT_MANAGE","IDENTITY_AUTHORITY_MANAGE");
    /** False is an empty target; conflicting trusted ID/code pairs are rejected. */
    boolean initialized(Connection c,UUID trustedTenant,String tenantCode)throws SQLException;
    Facts create(Connection c,UUID trustedTenant,Manifest manifest,byte[] subjectHmac)throws SQLException;
    void verify(Connection c,Manifest manifest,byte[] subjectHmac,Facts original)throws SQLException;
    static IdentityBootstrapService databaseBacked(){return new io.github.windyzhu3.ontologylaw.identity.internal.persistence.JooqIdentityBootstrapService();}
}
