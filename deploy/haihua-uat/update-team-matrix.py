"""Register observed UI states only; never infer absent states from commands."""
import json,re
import prepare as p
source=(p.ROOT/'backend/src/main/java/io/github/windyzhu3/ontologylaw/query/CurrentWorkCardQuery.java').read_text(encoding='utf-8')
labels={label:code for code,label in re.findall(r'case (\w+)->"([^"]+)"',source)}
pages=json.loads((p.RUNTIME/'team-state-ui-pages.json').read_text())
passed=json.loads((p.RUNTIME/'team-state-ui-PASS.json').read_text())
assert passed['status']=='PASS' and passed['pageCount']==len(pages)
observed={}
for page in pages:
 for item in page['items']:
  if item['purposeLabel'] not in labels:
   assert page['view']=='exceptions' # Exceptions without task placeholders are separate.
   continue
  code=labels[item['purposeLabel']]
  observed.setdefault(code,set()).add((item['stateLabel'],page['actor']))
dest=p.ROOT/'docs/evidence/haihua-uat/HH-UAT-20261001-01/team-task-matrix.md'
lines=dest.read_text(encoding='utf-8').splitlines();new=[];rows=0
for line in lines:
 cells=[v.strip() for v in line.split('|')]
 if len(cells)==5 and cells[1] in labels.values():
  rows+=1;code=cells[1]
  if code in observed:
   states='；'.join(sorted({state for state,actor in observed[code]}));actors='/'.join(sorted({actor for state,actor in observed[code]}))
   cells[3]=f'实际观察 {states}（{actors}）；其他状态未覆盖'
   line='| '+' | '.join(cells[1:4])+' |'
 new.append(line)
assert rows==36
new.append(f'\nHH009及B19补测后复查：三角色四视图共{len(pages)}页，实际观察{len(observed)}/36类型；本条仅登记本次原响应与渲染，旧23页快照保留。')
dest.write_text('\n'.join(new)+'\n',encoding='utf-8')
print(f'Observed team states registered: {len(observed)}/36 types, {len(pages)} actual UI pages')
