package io.github.windyzhu3.ontologylaw.api;

import io.github.windyzhu3.ontologylaw.api.security.*;
import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.execution.*;
import io.github.windyzhu3.ontologylaw.audit.AuditAppender;
import java.io.PrintStream;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Separate offline main: never starts Spring, an HTTP listener, or a Worker. All inputs are protected files. */
public final class IdentityBootstrapCommand {
    private IdentityBootstrapCommand(){}
    record Settings(String semanticBaseline,UUID tenantId,String tenantCode,String identityProviderCode,String issuer,String apiAudience,
            String directoryClientId,String directorySecretPath,String operatorAssertion,String node,String activeBootstrapKeyId,
            Map<String,String> bootstrapKeyPaths,String subjectHmacPath,String identityTrustStorePath,String identityTrustStorePasswordPath,Database database) {
        public String toString(){return "OfflineBootstrapSettings[restricted]";}
    }
    record Database(String url,String username,String passwordPath,String schemaVersion,String releaseDigest,String manifestHash) {}
    private static final JsonMapper JSON=JsonMapper.builder().enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).disable(tools.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    public static void main(String[] args){System.exit(run(args,System.out,System.err));}
    /** Public for an in-process offline operator harness; no exception body or secret is printed. */
    public static int run(String[] args,PrintStream output,PrintStream errors) {
        try {
            if(args.length<3||args.length>4||!Set.of("candidate","dry-run","execute","verify").contains(args[0])
                    ||args[0].equals("execute")&&(args.length!=4||!args[3].equals("--confirm-bootstrap"))||!args[0].equals("execute")&&args.length!=3)throw new IllegalArgumentException();
            var settings=JSON.readValue(IdentityDeploymentFiles.read(args[1],65536),Settings.class);
            if(!"MVP-2026-09-08.3".equals(settings.semanticBaseline())||settings.directoryClientId().equals(settings.apiAudience())||settings.node()==null||!settings.node().matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}"))throw new IllegalArgumentException();
            var binding=new BootstrapCandidateProtection.Binding(settings.operatorAssertion(),settings.tenantCode(),settings.identityProviderCode(),settings.issuer());
            var keys=new HashMap<String,byte[]>();var distinct=new HashSet<String>();for(var entry:settings.bootstrapKeyPaths().entrySet()){byte[] key=IdentityDeploymentFiles.key(entry.getValue());if(!distinct.add(Base64.getEncoder().encodeToString(key)))throw new IllegalArgumentException();keys.put(entry.getKey(),key);}
            byte[] subjectKey=IdentityDeploymentFiles.key(settings.subjectHmacPath());if(!distinct.add(Base64.getEncoder().encodeToString(subjectKey)))throw new IllegalArgumentException();
            var candidates=new BootstrapCandidateProtection(settings.activeBootstrapKeyId(),keys);
            var directory=new KeycloakDirectoryReader(new KeycloakDirectoryReader.Trust(settings.issuer(),settings.directoryClientId(),IdentityDeploymentFiles.secret(settings.directorySecretPath())),IdentityDeploymentFiles.tls(settings.identityTrustStorePath(),settings.identityTrustStorePasswordPath()));
            if(args[0].equals("candidate")) {
                // Output is intentionally confidential. Operator captures it in a restricted file, never a log.
                String account=IdentityDeploymentFiles.secret(args[2]);output.println(JSON.writeValueAsString(Map.of("providerUserSelector",candidates.issue(binding,account,directory,Instant.now()))));return 0;
            }
            var manifest=JSON.readValue(IdentityDeploymentFiles.read(args[2],32768),IdentityBootstrapService.Manifest.class);var db=settings.database();
            var database=RuntimeDatabase.databaseBacked(RuntimeDatabase.jdbc(new RuntimeDatabase.JdbcLogin(db.url(),db.username(),IdentityDeploymentFiles.secret(db.passwordPath()).toCharArray())),RuntimeDatabase.Role.API,new RuntimeDatabase.Expected(db.schemaVersion(),digest(db.releaseDigest()),digest(db.manifestHash())));
            if(!database.healthy())throw new IllegalStateException();
            var runtime=new IdentityBootstrapRuntime(settings.tenantId(),binding,candidates,new ExternalSubjectProtection(t->{if(!t.equals(settings.tenantId()))throw new IllegalArgumentException();return subjectKey;}),directory,AuditAppender.databaseBacked(settings.node()));
            try(var connection=database.open()) {
                var result=args[0].equals("verify")?runtime.verifyOriginal(connection,manifest):runtime.run(connection,manifest,args[0].equals("dry-run"));
                var response=new TreeMap<String,Object>();response.put("mode",result.mode());response.put("plannedDelta",result.plannedDelta());
                if(args[0].equals("dry-run")&&result.mode().equals("DRY_RUN"))response.put("preview",Map.of(
                        "tenant",Map.of("id",result.tenantId().toString(),"code",manifest.tenantCode(),"displayName",manifest.tenantDisplayName()),
                        "rootOrganization",Map.of("code",manifest.rootCode(),"displayName",manifest.rootDisplayName()),
                        "administrator",Map.of("displayName",manifest.principalDisplayName(),"principalKind","HUMAN","identityProviderCode",manifest.identityProviderCode()),
                        "appointment",Map.of("roleCode","IDENTITY_ADMIN","organizationCode",manifest.rootCode(),"effectiveFrom",manifest.effectiveFrom().toString()),
                        "authorityGrants",IdentityBootstrapService.MANAGEMENT_CODES.stream().map(code->Map.of("authorityCode",code,"path","DIRECT","scope","ROOT","scopeOrganizationCode",manifest.rootCode())).toList()));
                output.println(JSON.writeValueAsString(response));return 0;
            }
        }catch(Exception failure){errors.println("IDENTITY_BOOTSTRAP_FAILED_OR_UNCERTAIN: retain the original manifest and command; do not replace the key or infer rollback.");return 2;}
    }
    private static byte[] digest(String text){if(text==null||!text.matches("[0-9a-f]{64}"))throw new IllegalArgumentException();return HexFormat.of().parseHex(text);}
}
