package io.github.windyzhu3.ontologylaw.api.security;

import io.github.windyzhu3.ontologylaw.identity.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.security.interfaces.RSAPublicKey;
import java.sql.*;
import java.util.*;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import io.github.windyzhu3.ontologylaw.execution.CredentialIdentityRuntime;

/** Trusted deployment registry, never token-supplied identity or authority. */
public final class ActorContextResolver {
    @FunctionalInterface public interface Connections { Connection open() throws SQLException; }
    public record Registration(String issuer,String audience,String provider,Actor actor) {
        public Registration{required(issuer);required(audience);required(provider);Objects.requireNonNull(actor);}
        public String toString(){return "CredentialRegistration[restricted]";}
    }
    public record Trust(String issuer,String audience,RSAPublicKey verificationKey) {
        public Trust{required(issuer);required(audience);Objects.requireNonNull(verificationKey);if(verificationKey.getModulus().bitLength()<2048)throw new IllegalArgumentException("Invalid credential trust");}
    }
    public record CertificateRegistration(String sha256,String provider,Actor actor) {
        public CertificateRegistration{if(sha256==null||!sha256.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid certificate registry");required(provider);Objects.requireNonNull(actor);if(actor.principalKind()!=PrincipalKind.SERVICE||actor.onBehalfPrincipalId()!=null)throw new IllegalArgumentException("Invalid certificate registry");}
        public String toString(){return "CertificateRegistration[restricted]";}
    }
    private record VerifiedTrust(Trust trust,JwtDecoder decoder) {}
    private final Connections connections;private final ExternalSubjectProtection subjects;
    private final java.time.Clock clock;
    private final List<VerifiedTrust> trusts;private final List<Registration> registrations;
    private final Map<String,CertificateRegistration> certificates;
    private HumanCredentialVerifier humans;
    public ActorContextResolver(Connections connections,ExternalSubjectProtection subjects,HumanCredentialVerifier humans) {
        this.connections=Objects.requireNonNull(connections);this.subjects=Objects.requireNonNull(subjects);this.humans=Objects.requireNonNull(humans);
        this.clock=java.time.Clock.systemUTC();this.trusts=List.of();this.registrations=List.of();this.certificates=Map.of();
    }
    public HumanIdentityReader.VerifiedHumanIdentity human(String token) {
        if(humans==null)throw invalid();
        try {var credential=humans.verify(token);try(var c=connections.open()){return new CredentialIdentityRuntime().human(c,credential.tenantId(),credential.provider(),subjects.digest(credential.tenantId(),credential.subject()));}}
        catch(SQLException unavailable){throw new org.springframework.security.authentication.AuthenticationServiceException("SERVICE_UNAVAILABLE");}
        catch(HumanIdentityReader.Failure failure){throw humanFailure(failure);}
        catch(org.springframework.security.core.AuthenticationException classified){throw classified;}
        catch(RuntimeException failure){throw classify(failure);}
    }
    public Object authenticate(String token,UUID selector,boolean self) {
        return authenticate(token,selector,null,self,false);
    }
    public Object authenticate(String token,UUID selector,UUID behalf,boolean self,boolean administration) {
        return selectAuthenticated(authenticatePrincipal(token),selector,behalf,self,administration);
    }
    public Object authenticatePrincipal(String token) {
        return humans!=null&&(trusts.isEmpty()||humans.acceptsIssuer(token))?human(token):bearer(token);
    }
    public Object selectAuthenticated(Object principal,UUID selector,UUID behalf,boolean self,boolean administration) {
        if(principal instanceof HumanIdentityReader.VerifiedHumanIdentity identity) {
            if(administration&&behalf!=null)throw ActorSelectionFailure.denied();if(self)return identity;
            try(var c=connections.open()){return new CredentialIdentityRuntime().selectHuman(c,identity,selector,behalf);}
            catch(SQLException unavailable){throw new org.springframework.security.authentication.AuthenticationServiceException("SERVICE_UNAVAILABLE");}
            catch(HumanIdentityReader.Failure failure){throw humanFailure(failure);}
            catch(org.springframework.security.core.AuthenticationException classified){throw classified;}
            catch(RuntimeException failure){throw classify(failure);}
        }
        if(!(principal instanceof Actor actor))throw invalid();
        if(behalf!=null||selector!=null&&!selector.equals(actor.appointmentId()))throw ActorSelectionFailure.denied();return actor;
    }
    private static org.springframework.security.core.AuthenticationException humanFailure(HumanIdentityReader.Failure failure) {
        return switch(failure.code()){case "SERVICE_UNAVAILABLE"->new org.springframework.security.authentication.AuthenticationServiceException("SERVICE_UNAVAILABLE");case "NOT_AUTHORIZED"->ActorSelectionFailure.denied();default->invalid();};
    }
    public ActorContextResolver(Connections connections,ExternalSubjectProtection subjects,List<Trust> trusts,List<Registration> registrations) {
        this(connections,subjects,trusts,registrations,List.of());
    }
    public ActorContextResolver(Connections connections,ExternalSubjectProtection subjects,List<Trust> trusts,List<Registration> registrations,List<CertificateRegistration> certificates) {
        this(connections,subjects,trusts,registrations,certificates,java.time.Clock.systemUTC());
    }
    /** Production composite: only SERVICE may use the exact static registry. */
    public ActorContextResolver(Connections connections,ExternalSubjectProtection subjects,HumanCredentialVerifier humans,List<Trust> serviceTrusts,List<Registration> services,List<CertificateRegistration> certificates) {
        this(connections,subjects,serviceTrusts,services,certificates,java.time.Clock.systemUTC());this.humans=Objects.requireNonNull(humans);
        if(services.stream().anyMatch(r->r.actor().principalKind()!=PrincipalKind.SERVICE||r.actor().onBehalfPrincipalId()!=null)||serviceTrusts.stream().anyMatch(t->humans.trustsIssuer(t.issuer())))throw new IllegalArgumentException("Ambiguous HUMAN/SERVICE trust");
    }
    ActorContextResolver(Connections connections,ExternalSubjectProtection subjects,List<Trust> trusts,List<Registration> registrations,List<CertificateRegistration> certificates,java.time.Clock clock) {
        this.connections=Objects.requireNonNull(connections);this.subjects=Objects.requireNonNull(subjects);this.clock=Objects.requireNonNull(clock);
        if(trusts==null||trusts.isEmpty()||registrations==null||registrations.isEmpty())throw new IllegalArgumentException("Credential registry unavailable");
        var pairs=new HashSet<List<String>>();var verified=new ArrayList<VerifiedTrust>();
        for(var trust:trusts) {
            if(!pairs.add(List.of(trust.issuer(),trust.audience())))throw new IllegalArgumentException("Ambiguous credential trust");
            var decoder=NimbusJwtDecoder.withPublicKey(trust.verificationKey()).signatureAlgorithm(SignatureAlgorithm.RS256).build();
            var timestamp=new JwtTimestampValidator(java.time.Duration.ZERO);timestamp.setClock(clock);
            decoder.setJwtValidator(JwtValidators.createDefaultWithValidators(timestamp,new JwtIssuerValidator(trust.issuer())));verified.add(new VerifiedTrust(trust,decoder));
        }
        if(new HashSet<>(registrations).size()!=registrations.size())throw new IllegalArgumentException("Ambiguous credential registry");
        for(var registration:registrations)if(!pairs.contains(List.of(registration.issuer(),registration.audience())))throw new IllegalArgumentException("Untrusted credential registry");
        this.trusts=List.copyOf(verified);this.registrations=List.copyOf(registrations);
        var certs=new HashMap<String,CertificateRegistration>();for(var certificate:certificates)if(certs.put(certificate.sha256(),certificate)!=null)throw new IllegalArgumentException("Ambiguous certificate registry");this.certificates=Map.copyOf(certs);
    }
    /** Chain must originate only from the TLS container's already verified request attribute. */
    public Actor certificate(java.security.cert.X509Certificate[] verifiedChain) {
        try {
            if(verifiedChain==null||verifiedChain.length==0)throw invalid();verifiedChain[0].checkValidity();
            String fingerprint=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(verifiedChain[0].getEncoded()));
            var registration=certificates.get(fingerprint);if(registration==null)throw invalid();
            try(var c=connections.open()){var actor=new CredentialIdentityRuntime().certificate(c,registration.actor(),registration.provider());if(actor==null)throw invalid();return actor;}
        }catch(SQLException unavailable){throw new org.springframework.security.authentication.AuthenticationServiceException("SERVICE_UNAVAILABLE");}
        catch(java.security.GeneralSecurityException failed){throw invalid();}
        catch(RuntimeException failed){throw classify(failed);}
    }
    public Actor bearer(String token) {
        if(humans!=null&&(trusts.isEmpty()||humans.acceptsIssuer(token)))return (Actor)authenticate(token,null,false);
        if(token==null||token.isEmpty())throw invalid();
        try {
            var candidates=new ArrayList<CredentialIdentityRuntime.Candidate>();
            for(var configured:trusts) {
                Jwt jwt;try {jwt=configured.decoder().decode(token);}catch(JwtException invalid){continue;}
                var now=clock.instant();
                if(jwt.getExpiresAt()==null||!now.isBefore(jwt.getExpiresAt())||(jwt.getNotBefore()!=null&&now.isBefore(jwt.getNotBefore()))
                        ||jwt.getSubject()==null||jwt.getSubject().isEmpty()||!jwt.getAudience().contains(configured.trust().audience()))continue;
                for(var registration:registrations)if(registration.issuer().equals(configured.trust().issuer())&&registration.audience().equals(configured.trust().audience()))
                    candidates.add(new CredentialIdentityRuntime.Candidate(registration.actor(),registration.provider(),subjects.digest(registration.actor().tenantId(),jwt.getSubject())));
            }
            if(candidates.isEmpty())throw invalid();
            try(var c=connections.open()) {var actor=new CredentialIdentityRuntime().unique(c,candidates);if(actor==null)throw invalid();return actor;}
        }catch(SQLException unavailable){throw new org.springframework.security.authentication.AuthenticationServiceException("SERVICE_UNAVAILABLE");}
        catch(RuntimeException failed){throw classify(failed);}
    }
    private static org.springframework.security.core.AuthenticationException classify(RuntimeException failure) {
        var seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
        for(Throwable cause=failure;cause!=null&&seen.add(cause);cause=cause.getCause())
            if(cause instanceof SQLException)return new org.springframework.security.authentication.AuthenticationServiceException("SERVICE_UNAVAILABLE");
        return invalid();
    }
    private static void required(String value){if(value==null||value.isBlank())throw new IllegalArgumentException("Invalid credential registry");}
    private static BadCredentialsException invalid(){return new BadCredentialsException("UNAUTHENTICATED");}
}
