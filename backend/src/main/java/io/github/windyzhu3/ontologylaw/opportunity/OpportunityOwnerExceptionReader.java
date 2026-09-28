package io.github.windyzhu3.ontologylaw.opportunity;
import java.sql.*;
import java.time.Instant;
import java.util.*;
/** Tenant-bounded exception ledger reads; only this Owner accesses its persistence. */
public interface OpportunityOwnerExceptionReader {
    record Position(Instant at,UUID id) {}
    List<Position> scan(Connection c,UUID tenant,Instant observed,Position after,int limit)throws SQLException;
    OpportunityOwnerExceptionService.Snapshot current(Connection c,UUID tenant,UUID id)throws SQLException;
    static OpportunityOwnerExceptionReader databaseBacked(){return new io.github.windyzhu3.ontologylaw.opportunity.internal.persistence.JooqOpportunityOwnerExceptionReader();}
}
