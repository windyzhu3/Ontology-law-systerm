package io.github.windyzhu3.ontologylaw.opportunity.internal.persistence;
import io.github.windyzhu3.ontologylaw.identity.AuthorizationService.ReadScope;
import java.sql.*;import java.time.*;import java.util.*;
/** Opportunity-owned raw rows, only inside an exact fenced query scope. No decrypted values or authorization decisions. */
final class OpportunityReadRows {
 private OpportunityReadRows(){}
    private record RowKey(String query,List<Object> arguments) {}
    private record LedgerFacts(Connection connection,UUID tenant,Map<RowKey,List<Map<String,Object>>> rows,Map<UUID,List<UUID>> groups,Map<Object,Object> facts) {}
    private static final ThreadLocal<LedgerFacts> ledgerFacts=new ThreadLocal<>();
    @FunctionalInterface interface Loader<T>{T read()throws SQLException;}
    @SuppressWarnings("unchecked") static <T>T fact(Connection c,UUID tenant,Object key,Loader<T> loader)throws SQLException{
        var scope=ledgerFacts.get();if(scope==null||scope.connection()!=c||!scope.tenant().equals(tenant))return loader.read();
        if(scope.facts().containsKey(key))return (T)scope.facts().get(key);
        T result=loader.read();if(scope.facts().size()<4096)scope.facts().put(key,result);return result;
    }
    public static ReadScope lockedLedgerFacts(Connection c,UUID tenant)throws SQLException {
        if(c.getAutoCommit()||c.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED||ledgerFacts.get()!=null)throw new SQLException("Fenced query scope required","25001");
        ledgerFacts.set(new LedgerFacts(c,tenant,new HashMap<>(),new HashMap<>(),new HashMap<>()));return ()->ledgerFacts.remove();
    }
    public static void prepareLedgerCandidates(Connection c,UUID tenant,List<UUID> candidates,int batchSize)throws SQLException{
        var scope=ledgerFacts.get();if(scope==null||scope.connection()!=c||!scope.tenant().equals(tenant)||c.getAutoCommit())throw new SQLException("Exact fenced Owner scope required","25001");
        if(candidates.size()>100||new HashSet<>(candidates).size()!=candidates.size()||batchSize<1||batchSize>20)throw new IllegalArgumentException("Bounded unique candidates required");
        for(int i=0;i<candidates.size();i+=batchSize){var group=List.copyOf(candidates.subList(i,Math.min(i+batchSize,candidates.size())));for(var id:group)scope.groups().put(id,group);}
    }
    static void requireCommandScope()throws SQLException {if(ledgerFacts.get()!=null)throw new SQLException("Command in ledger read scope","25001");}
    static List<Map<String,Object>> rows(Connection c,String query,Object...args)throws SQLException {
        // Reuse only business rows inside the caller's fenced read, never permission
        // decisions or clock-dependent queries. Bound memory even with long histories.
        var scope=ledgerFacts.get();String normalized=query.toLowerCase(Locale.ROOT);
        boolean reuse=scope!=null&&scope.connection()==c&&args.length>0&&scope.tenant().equals(args[0])
                &&normalized.startsWith("select ")&&!normalized.contains("clock_timestamp")&&!normalized.contains("now()")&&!normalized.contains("current_timestamp")&&!normalized.contains("for update")&&!normalized.contains("for share");
        var key=reuse?new RowKey(query,Collections.unmodifiableList(new ArrayList<>(Arrays.asList(args)))):null;
        if(reuse&&scope.rows().containsKey(key))return scope.rows().get(key);
        if(reuse&&args.length>=2&&args[1] instanceof UUID candidate&&scope.groups().containsKey(candidate)&&repeatedPair(args,scope.tenant(),candidate)){
            var group=scope.groups().get(candidate);var missing=group.stream().filter(id->!scope.rows().containsKey(new RowKey(query,pairArguments(scope.tenant(),id,args.length)))).toList();
            if(missing.size()>1){
                var batchResults=new HashMap<UUID,List<Map<String,Object>>>();
                try(var p=c.prepareStatement(String.join(";",Collections.nCopies(missing.size(),query)))){
                    int n=1;for(var id:missing)for(var value:pairArguments(scope.tenant(),id,args.length))p.setObject(n++,value);
                    boolean result=p.execute();
                    for(int i=0;i<missing.size();i++){
                        if(!result)throw new SQLException("Missing bounded Owner result","22000");
                        try(var r=p.getResultSet()){var values=new ArrayList<Map<String,Object>>();while(r.next()){var row=new LinkedHashMap<String,Object>();for(int k=1;k<=r.getMetaData().getColumnCount();k++)row.put(r.getMetaData().getColumnLabel(k),r.getObject(k));values.add(Collections.unmodifiableMap(row));}batchResults.put(missing.get(i),List.copyOf(values));}
                        result=p.getMoreResults(Statement.CLOSE_CURRENT_RESULT);
                    }
                    if(result||p.getUpdateCount()!=-1)throw new SQLException("Unexpected bounded Owner result","22000");
                }
                registerRelatedGroups(scope.groups(),batchResults.values(),missing.size());
                batchResults.forEach((id,rows)->{if(scope.rows().size()<4096)scope.rows().put(new RowKey(query,pairArguments(scope.tenant(),id,args.length)),rows);});
                return batchResults.get(candidate);
            }
        }
        try(var p=c.prepareStatement(query)){bind(p,args);try(var r=p.executeQuery()){var list=new ArrayList<Map<String,Object>>();while(r.next()){var m=new LinkedHashMap<String,Object>();for(int i=1;i<=r.getMetaData().getColumnCount();i++)m.put(r.getMetaData().getColumnLabel(i),r.getObject(i));list.add(reuse?Collections.unmodifiableMap(m):m);}var result=reuse?List.copyOf(list):list;if(reuse&&scope.rows().size()<4096)scope.rows().put(key,result);return result;}}
    }
    private static boolean repeatedPair(Object[] args,UUID tenant,UUID candidate){
        if(args.length%2!=0)return false;
        for(int i=0;i<args.length;i+=2)if(!tenant.equals(args[i])||!candidate.equals(args[i+1]))return false;
        return true;
    }
    private static List<Object> pairArguments(UUID tenant,UUID id,int count){
        var result=new ArrayList<Object>(count);for(int i=0;i<count;i+=2){result.add(tenant);result.add(id);}return List.copyOf(result);
    }
    /** Discover only identifiers already returned by this bounded Owner page. Each
     * follow-up still executes its original SQL with each exact tenant/identifier pair. */
    private static void registerRelatedGroups(Map<UUID,List<UUID>> groups,Collection<List<Map<String,Object>>> batches,int maximum){
        var columns=new LinkedHashMap<String,Set<UUID>>();
        for(var batch:batches)for(var row:batch)for(var field:row.entrySet())
            if(!field.getKey().equals("tenant_id")&&field.getValue() instanceof UUID id)
                columns.computeIfAbsent(field.getKey(),ignored->new LinkedHashSet<>()).add(id);
        for(var ids:columns.values()){
            var ordered=List.copyOf(ids);for(int offset=0;offset<ordered.size();offset+=maximum){
                var group=List.copyOf(ordered.subList(offset,Math.min(offset+maximum,ordered.size())));
                for(var id:group)if(groups.size()<4096)groups.putIfAbsent(id,group);
            }
        }
    }
    static Map<String,Object> row(Connection c,String query,Object...args)throws SQLException{var list=rows(c,query,args);return list.isEmpty()?null:list.getFirst();}
 private static void bind(PreparedStatement p,Object...args)throws SQLException{for(int i=0;i<args.length;i++)p.setObject(i+1,args[i] instanceof Instant at?at.atOffset(ZoneOffset.UTC):args[i]);}
 static Instant instant(Object value){return value instanceof Timestamp t?t.toInstant():value instanceof OffsetDateTime t?t.toInstant():value==null?null:Instant.parse(value.toString());}
}
