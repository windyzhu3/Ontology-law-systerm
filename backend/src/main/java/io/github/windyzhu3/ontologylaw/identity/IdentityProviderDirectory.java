package io.github.windyzhu3.ontologylaw.identity;

/** Read-only trusted external directory. It proves an existing account, never provisions it. */
public interface IdentityProviderDirectory {
    record Account(String subject) {
        public Account {if(subject==null||subject.isBlank()||subject.length()>2048)throw new IllegalArgumentException("Invalid directory account");}
        public String toString(){return "VerifiedDirectoryAccount[restricted]";}
    }
    Account exact(String accountIdentifier);
    /** Exact username online lookup; absence/non-HUMAN/disabled is empty, outage is never absence. */
    default Account candidate(String username){return exact(username);}
    /** Online final recheck, retaining bounded HUMAN proof separately from offline bootstrap. */
    default Account candidateEnabled(String subject){return enabled(subject);}
    Account enabled(String exactSubject);
    String issuer();
}
