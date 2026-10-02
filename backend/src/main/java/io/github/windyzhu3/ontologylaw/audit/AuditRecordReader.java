package io.github.windyzhu3.ontologylaw.audit;

import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Only safe projections leave Audit Owner; selectors are retained for exact reauthorization. */
public interface AuditRecordReader {
 enum Relation {CORRELATION,CORRECTION}
 record Query(Instant start,Instant end,String scope,String result,String search,int limit,String cursor){}
 record Position(Instant trustedAt,UUID id,Instant watermark){}
 record SafeRecord(Subject fact,Subject source,UUID organization,Instant trustedAt,UUID correlation,Subject correction,Map<String,Object> values){public SafeRecord{values=Map.copyOf(values);}}
 record Page(List<SafeRecord> items,boolean hasMore){public Page{items=List.copyOf(items);}}
 SafeRecord find(Connection c,Actor actor,UUID id)throws SQLException;
 Page list(Connection c,Actor actor,Query query,Position position)throws SQLException;
 Page related(Connection c,Actor actor,SafeRecord seed,Relation relation,Query query,Position position)throws SQLException;
 static AuditRecordReader databaseBacked(){return new io.github.windyzhu3.ontologylaw.audit.internal.persistence.JooqAuditRecordReader();}
}
