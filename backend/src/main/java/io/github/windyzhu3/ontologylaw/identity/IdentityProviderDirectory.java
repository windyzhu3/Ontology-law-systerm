package io.github.windyzhu3.ontologylaw.identity;

/** Read-only trusted external directory. It proves an existing account, never provisions it. */
public interface IdentityProviderDirectory {
    record Account(String subject) {
        public Account {if(subject==null||subject.isBlank()||subject.length()>2048)throw new IllegalArgumentException("Invalid directory account");}
        public String toString(){return "VerifiedDirectoryAccount[restricted]";}
    }
    Account exact(String accountIdentifier);
    Account enabled(String exactSubject);
    String issuer();
}
