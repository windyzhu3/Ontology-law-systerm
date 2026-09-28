package io.github.windyzhu3.ontologylaw.opportunity;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.Subject;
import java.sql.*;import java.util.*;
/** Exact current approval membership, without reading protected commercial bodies. */
public interface QuoteApprovalResponsibilityReader {
 record Member(Subject selector,UUID taskId,UUID appointment,Subject decision){}
 record Approval(List<Subject> facts,List<Member> members){public Approval{facts=List.copyOf(facts);members=List.copyOf(members);}}
 Approval current(Connection c,UUID tenant,Subject opportunity)throws SQLException;
 static QuoteApprovalResponsibilityReader databaseBacked(){return new io.github.windyzhu3.ontologylaw.opportunity.internal.persistence.JdbcQuoteApprovalResponsibilityReader();}
}
