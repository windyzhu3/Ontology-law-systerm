package io.github.windyzhu3.ontologylaw.responsibility;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;
import java.time.*;
import java.util.*;

public interface TaskFactory {
    enum Type {
        RESOLVE_LEAD_DUPLICATE("RESOLVE_DUPLICATE_LEAD","SOURCE_INTAKE_OWNER","LEAD_INGRESS_RESOLVE","ResolveDuplicateLeadV1","responsibility.decision_record"),
        COMPLETE_LEAD_INGRESS("COMPLETE_LEAD_INGRESS","SOURCE_INTAKE_OWNER","LEAD_INGRESS_COMPLETE","CompleteLeadIngressV1","lead.lead"),
        ASSIGN_LEAD("ASSIGN_LEAD","ROUTING_SUPERVISOR","LEAD_ASSIGN","AssignLeadV1","lead.lead_assignment"),
        RESOLVE_LEAD_ROUTING_GAP("RECORD_ROUTING_DISPOSITION","ROUTING_SUPERVISOR","LEAD_ROUTING_DECIDE","RecordRoutingDispositionV1","responsibility.decision_record"),
        ACK_SOURCE_INTAKE_STOP_REQUEST("ACKNOWLEDGE_SOURCE_INTAKE_STOP_REQUEST","SOURCE_INTAKE_OWNER","SOURCE_INTAKE_REQUEST_ACK","AcknowledgeSourceIntakeStopRequestV1","responsibility.decision_record"),
        CONTACT_LEAD("RECORD_CONTACT_RESULT","ASSIGNMENT_OWNER","SALES_CONTACT_OWNER","RecordContactResultV1","lead.lead_contact_result"),
        REVIEW_LEAD_VALIDITY("REVIEW_LEAD_VALIDITY","ROUTING_SUPERVISOR","LEAD_VALIDITY_REVIEW","ReviewLeadValidityV1","responsibility.decision_record");
        public final String command,slot,authority,schema,completionType;
        Type(String command,String slot,String authority,String schema,String completionType){this.command=command;this.slot=slot;this.authority=authority;this.schema=schema;this.completionType=completionType;}
        public long slaSeconds(){return this==CONTACT_LEAD?1800:this==ACK_SOURCE_INTAKE_STOP_REQUEST?32400:14400;}
        public String slaCode(){return this==CONTACT_LEAD?"R1_CONTACT_30M_V1":this==ACK_SOURCE_INTAKE_STOP_REQUEST?"R1_BUSINESS_1D_V1":"R1_BUSINESS_4H_V1";}
    }
    record Task(Subject selector,UUID owner,Type type,Subject lead,String state,Instant createdAt,Subject completion) {}
    Task read(Connection c,UUID tenant,UUID id) throws SQLException;
    List<Task> activeForLead(Connection c,UUID tenant,Subject lead) throws SQLException;
    void lock(Connection c,UUID tenant,UUID id) throws SQLException;
    Task create(Connection c,UUID tenant,Type type,UUID owner,Subject lead,ZoneId zone,Instant now) throws SQLException;
    void complete(Connection c,UUID tenant,Task task,Subject fact,Instant now) throws SQLException;
    Subject decision(Connection c,UUID tenant,Task task,UUID actor,String contract,String decision,String rationale,Map<String,Object> digestValues,Instant now) throws SQLException;
    Subject waitUntil(Connection c,UUID tenant,Task task,UUID actor,Instant due,Instant now) throws SQLException;
    Subject causalStop(Connection c,UUID tenant,Task task) throws SQLException;
    static TaskFactory databaseBacked(){return new io.github.windyzhu3.ontologylaw.responsibility.internal.persistence.JooqTaskRepository();}
}
