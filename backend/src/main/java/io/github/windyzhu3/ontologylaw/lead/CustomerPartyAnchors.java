package io.github.windyzhu3.ontologylaw.lead;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;import java.util.*;
/** Metadata-only pagination of existing Party references; no name/phone search before authorization. */
public interface CustomerPartyAnchors {
 record Anchor(Subject lead,Subject assignment,UUID owner,UUID party){}
 List<Anchor> page(Connection c,UUID tenant,UUID after,int limit)throws SQLException;
 static CustomerPartyAnchors databaseBacked(){return new io.github.windyzhu3.ontologylaw.lead.internal.persistence.JdbcCustomerPartyAnchors();}
}
