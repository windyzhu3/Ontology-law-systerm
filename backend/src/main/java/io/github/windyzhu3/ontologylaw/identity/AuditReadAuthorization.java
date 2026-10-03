package io.github.windyzhu3.ontologylaw.identity;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.sql.*;
import java.util.*;

/** Independent own-HUMAN audit read authority; no administrator or role inference. */
public interface AuditReadAuthorization {
 record Access(List<UUID> scopes,AuthorizationSnapshot authorization,String binding) {
  public Access {scopes=List.copyOf(scopes);}
 }
 Access scopes(Connection c,Actor actor)throws SQLException;
 AuthorizationSnapshot authorize(Connection c,Actor actor,Subject auditFact,Subject sourceFact,UUID recordScope)throws SQLException;
 static AuditReadAuthorization databaseBacked(){return new io.github.windyzhu3.ontologylaw.identity.internal.persistence.JooqAuditReadAuthorization();}
}
