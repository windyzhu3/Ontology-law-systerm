"""Read-only QUERY-role lineage diagnosis against the owned UAT; no new facts."""
import zipfile,subprocess
from pathlib import Path
import prepare as p
folder=p.RUNTIME/'team-history-diagnostic';folder.mkdir(exist_ok=True)
libs=folder/'libs';libs.mkdir(exist_ok=True)
with zipfile.ZipFile(p.RUNTIME/'app.jar') as archive:
 for name in archive.namelist():
  if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
   dest=libs/Path(name).name
   if not dest.exists():dest.write_bytes(archive.read(name))
source=folder/'TeamHistoryDiagnostic.java'
source.write_text('''package io.github.windyzhu3.ontologylaw.api;
import java.util.*;import java.sql.*;import java.nio.file.*;
import io.github.windyzhu3.ontologylaw.responsibility.*;
import static io.github.windyzhu3.ontologylaw.execution.internal.persistence.CapabilityRoleExecutor.*;
public class TeamHistoryDiagnostic {
 public static void main(String[] args)throws Exception{
  var props=new Properties();try(var input=Files.newInputStream(Path.of(args[0]))){props.load(input);}
  UUID tenant=UUID.fromString("a1db3741-7ca1-480b-b3ea-8e75e8a4b4a2");
  try(var c=DriverManager.getConnection(props.getProperty("ols.api.database.url"),props.getProperty("ols.api.database.username"),props.getProperty("ols.api.database.password"))){
   inTransaction(c,Capability.QUERY,x->{
    UUID after=null;int total=0;
    while(true){var rows=TeamResponsibilityReader.databaseBacked().scan(x,tenant,TeamResponsibilityReader.View.HISTORY,after,100);
     for(var task:rows){total++;try{R2TeamTaskResolver.basis(x,tenant,task);}catch(Exception failure){System.out.println("FAILED type="+task.type()+" state="+task.state()+" task="+task.selector().id()+" exception="+failure.getClass().getSimpleName());failure.printStackTrace();throw failure;}}
     if(rows.size()<100)break;after=rows.getLast().selector().id();
    }System.out.println("ALL_LINEAGES_READ="+total);return null;
   });
  }
 }
}''',encoding='utf-8')
cp=str(p.ROOT/'backend/target/classes')+';'+str(libs/'*')
javac=Path(p.JAVA).with_name('javac.exe')
with (folder/'compile.log').open('wb') as log:
 subprocess.run([str(javac),'-cp',cp,'-d',str(folder),str(source)],stdout=log,stderr=subprocess.STDOUT,check=True)
with (folder/'result.log').open('wb') as log:
 result=subprocess.run([str(p.JAVA),'-cp',str(folder)+';'+cp,'io.github.windyzhu3.ontologylaw.api.TeamHistoryDiagnostic',str(p.RUNTIME/'application.properties')],stdout=log,stderr=subprocess.STDOUT)
print('Read-only diagnosis exit '+str(result.returncode)+'; details preserved in own private diagnostic directory')
