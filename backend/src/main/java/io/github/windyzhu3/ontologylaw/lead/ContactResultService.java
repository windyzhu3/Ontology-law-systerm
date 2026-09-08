package io.github.windyzhu3.ontologylaw.lead;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Immutable contact facts owned by Lead, on the caller's transaction. */
public interface ContactResultService {
    long nextNumber(Connection c,UUID tenant,UUID lead)throws SQLException;
    CurrentLeadReader.ContactResult latest(Connection c,UUID tenant,UUID lead)throws SQLException;
    Subject append(Connection c,UUID tenant,UUID lead,UUID assignment,UUID task,long number,
            String channel,String code,String summary,UUID evidence,Instant now)throws SQLException;
    static ContactResultService databaseBacked(){return new io.github.windyzhu3.ontologylaw.lead.internal.persistence.JooqContactResultRepository();}
}
