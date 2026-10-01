"""Reproduce the historical migration failure under the HEAD runtime, then restore source."""
from pathlib import Path
import subprocess,os,shutil
root=Path(__file__).resolve().parents[2]
runtime=root/'.superpowers/haihua-uat-runtime'
source=root/'backend/src/main/java/io/github/windyzhu3/ontologylaw/execution/CommandRuntime.java'
saved=source.read_bytes()
archive=runtime/'P3-workflow-failsafe-reports'
if archive.exists():raise RuntimeError('Preserve prior baseline verification')
shutil.copytree(root/'backend/target/failsafe-reports',archive)
original=subprocess.run(['git','show','HEAD:backend/src/main/java/io/github/windyzhu3/ontologylaw/execution/CommandRuntime.java'],cwd=root,capture_output=True,check=True).stdout
env=os.environ.copy();env['JAVA_HOME']='C:/Users/Jacob/.cache/codex-runtimes/ontology-law-prb/jdk-25.0.4.1+1'
try:
 source.write_bytes(original)
 with (runtime/'HH003-v960-baseline.log').open('wb') as out:
  result=subprocess.run(['pwsh','-NoProfile','-Command',"& ./mvnw.cmd -f backend/pom.xml -Pit '-Dit.test=TeamQuoteCoverageIT#v960_backfilled_quote_facts_are_not_new_command_writes' '-Dtest=IdentityBusinessRolesTest' verify"],cwd=root,env=env,stdout=out,stderr=subprocess.STDOUT)
 print('Historical baseline execution exit '+str(result.returncode)+'; inspect log before conclusion')
finally:
 source.write_bytes(saved)
 print('Current scoped runtime source restored exactly')
