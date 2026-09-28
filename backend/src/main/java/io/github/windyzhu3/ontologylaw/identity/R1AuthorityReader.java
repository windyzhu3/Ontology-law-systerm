package io.github.windyzhu3.ontologylaw.identity;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;

/** Owner selection of independently complete authority paths; no SQL or generated types escape. */
public interface R1AuthorityReader {
    record Candidate(UUID appointmentId, UUID principalId, UUID organizationId, Instant startsAt, Request authorization) {}
    Request select(Connection connection, Actor actor, Subject subject, UUID organization, String slot, String authorityCode) throws SQLException;
    /** Select and finally evaluate one complete authority path without evaluating the winning path twice. */
    default AuthorizationSnapshot authorize(Connection c,Actor actor,Subject subject,UUID organization,String slot,String code)throws SQLException {
        var request=select(c,actor,subject,organization,slot,code);
        return request==null?null:AuthorizationService.databaseBacked().evaluate(c,request,true);
    }
    /** Every subject must have one complete path; an empty result means at least one was denied. */
    default List<AuthorizationSnapshot> authorizeAll(Connection c,Actor actor,List<Subject> subjects,UUID organization,String slot,String code)throws SQLException {
        var result=new ArrayList<AuthorizationSnapshot>();for(var subject:subjects){var snapshot=authorize(c,actor,subject,organization,slot,code);if(snapshot==null||!snapshot.allowed())return List.of();result.add(snapshot);}return List.copyOf(result);
    }
    List<Candidate> candidates(Connection connection, UUID tenant, Subject subject, UUID organization, String slot, String authorityCode) throws SQLException;
    static R1AuthorityReader databaseBacked() {return new io.github.windyzhu3.ontologylaw.identity.internal.persistence.JooqR1AuthorityReader();}
}
