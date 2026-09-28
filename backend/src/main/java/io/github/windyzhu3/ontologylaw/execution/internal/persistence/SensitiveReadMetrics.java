package io.github.windyzhu3.ontologylaw.execution.internal.persistence;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
/** Opt-in aggregate timings. Never records SQL text, parameters, actor identity or response bodies. */
public final class SensitiveReadMetrics implements AutoCloseable {
 private final boolean enabled=Boolean.getBoolean("ols.read.metrics");
 private final UUID correlation;private final long start=System.nanoTime();private long lap=start,sqlNanos;private int statements;
 private final Map<String,Long> phases=new LinkedHashMap<>();
 private final Map<String,long[]> categories=new HashMap<>();
 private final Map<String,Integer> clockCallers=new HashMap<>();
 private static final java.util.regex.Pattern TABLE=java.util.regex.Pattern.compile("(?i)\\b(?:from|into|update)\\s+(\"?[a-z_]+\"?\\.\"?[a-z_]+\"?)");
 public SensitiveReadMetrics(UUID correlation){this.correlation=correlation;}
 public void mark(String phase){long now=System.nanoTime();phases.put(phase,(now-lap)/1000000);lap=now;}
 public Connection connection(Connection c){
  if(!enabled)return c;
  return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
   Object result=call(c,m,a);
   if(result instanceof Statement s){
    String category="other";
    if(a!=null&&a.length>0&&a[0] instanceof String sql){var match=TABLE.matcher(sql);if(match.find())category=match.group(1).replace("\"","").toLowerCase(Locale.ROOT);else if(sql.equals("select clock_timestamp()"))category="clock";}
    final String group=category;
    Class<?> type=s instanceof CallableStatement?CallableStatement.class:s instanceof PreparedStatement?PreparedStatement.class:Statement.class;
    return Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(sp,sm,sa)->{
     if(!sm.getName().startsWith("execute"))return call(s,sm,sa);
     if(group.equals("clock")){
      var caller=StackWalker.getInstance().walk(frames->frames.filter(f->f.getClassName().startsWith("io.github.windyzhu3.ontologylaw.")&&!f.getClassName().equals(SensitiveReadMetrics.class.getName())).map(f->f.getClassName().substring(f.getClassName().lastIndexOf('.')+1)+"."+f.getMethodName()).limit(7).toList());
      clockCallers.merge(String.join("/",caller),1,Integer::sum);
     }
     long before=System.nanoTime();statements++;try{return call(s,sm,sa);}finally{long elapsed=System.nanoTime()-before;sqlNanos+=elapsed;var totals=categories.computeIfAbsent(group,ignored->new long[2]);totals[0]++;totals[1]+=elapsed;}
    });
   }
   return result;
  });
 }
 private static Object call(Object target,Method method,Object[] args)throws Throwable {
  try{return method.invoke(target,args);}catch(InvocationTargetException failure){throw failure.getCause();}
 }
 public void close(){if(enabled){mark("transactionEnd");var top=categories.entrySet().stream().sorted((a,b)->Long.compare(b.getValue()[1],a.getValue()[1])).limit(10).map(e->e.getKey()+":"+e.getValue()[0]+"/"+e.getValue()[1]/1000000+"ms").toList();var clocks=clockCallers.entrySet().stream().sorted(Map.Entry.<String,Integer>comparingByValue().reversed()).limit(5).toList();org.slf4j.LoggerFactory.getLogger(SensitiveReadMetrics.class).info("SENSITIVE_READ_TIMING correlation={} totalMs={} sqlCount={} sqlMs={} phasesMs={} categories={} clockCallers={}",correlation,(System.nanoTime()-start)/1000000,statements,sqlNanos/1000000,phases,top,clocks);}}
}
