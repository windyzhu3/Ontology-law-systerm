import {render,screen,fireEvent,waitFor,within} from '@testing-library/react';
import {expect,it} from 'vitest';
import {AuditRecordPage} from './AuditRecordPage';
import {createAuditRecordApi,type AuditRecord} from './auditRecordApi';
import {testSession,deferred} from '../../test/fixtures';
const first='11111111-1111-4111-8111-111111111111',second='22222222-2222-4222-8222-222222222222';
const row=(id=first):AuditRecord=>({id,trustedAt:id===first?'2026-10-01T01:00:00Z':'2026-10-01T02:00:00Z',authorizationPathLabel:'直接授权',recordOrganizationLabel:'销售二部',onBehalfLabel:'本人办理',actorLabel:id===first?'销售五':'主管二',appointmentLabel:'销售二部 · 销售',objectLabel:'线索',actionLabel:'执行操作',scopeLabel:'对象',resultLabel:'成功',summary:'安全摘要',verificationLabel:'当前权限已核验',hasCorrelation:true,hasCorrection:false});
const response=(body:unknown)=>new Response(JSON.stringify(body),{status:200,headers:{'Content-Type':'application/json','Cache-Control':'no-store'}});
it('queries an explicit historical date window rather than only recent presets',async()=>{
 const requests:Request[]=[];const api=createAuditRecordApi(async request=>{requests.push(request);return response({items:[]});});render(<AuditRecordPage session={testSession()} api={api}/>);await screen.findByText('当前条件下没有可显示的记录。');
 fireEvent.change(screen.getByLabelText('开始时间'),{target:{value:'2026-08-20T09:00'}});fireEvent.change(screen.getByLabelText('结束时间'),{target:{value:'2026-08-27T09:00'}});fireEvent.click(screen.getByRole('button',{name:'查询'}));
 await waitFor(()=>expect(requests.some(r=>new URL(r.url).searchParams.get('start')===new Date('2026-08-20T09:00').toISOString()&&new URL(r.url).searchParams.get('end')===new Date('2026-08-27T09:00').toISOString())).toBe(true));
});
it('filters, pages, loads read-only detail and existing chains with no identifiers or write controls',async()=>{
 const requests:Request[]=[];const api=createAuditRecordApi(async request=>{requests.push(request);const url=new URL(request.url);if(url.pathname.endsWith('/related'))return response({items:[row(second)]});if(url.pathname.endsWith(first)||url.pathname.endsWith(second))return response(row(url.pathname.endsWith(second)?second:first));return response({items:[row(url.searchParams.has('cursor')?second:first)],...(url.searchParams.has('cursor')?{}:{nextCursor:'next'})});});
 render(<AuditRecordPage session={testSession()} api={api}/>);
 await screen.findByRole('heading',{name:'安全摘要'});fireEvent.click(screen.getByRole('button',{name:'相关链'}));expect(await screen.findByText('主管二 · 线索')).toBeVisible();
 fireEvent.click(screen.getByRole('button',{name:'下一页'}));await waitFor(()=>expect(within(screen.getByLabelText('审计记录详情')).getByText('主管二')).toBeVisible());
 fireEvent.change(screen.getByLabelText('搜索'),{target:{value:'销售'}});fireEvent.change(screen.getByLabelText('结果'),{target:{value:'SUCCEEDED'}});fireEvent.click(screen.getByRole('button',{name:'查询'}));await waitFor(()=>expect(requests.some(r=>new URL(r.url).searchParams.get('search')==='销售')).toBe(true));
 expect(screen.queryByText(first)).not.toBeInTheDocument();expect(screen.queryByRole('button',{name:/导出|新增|更正记录|保存/})).not.toBeInTheDocument();expect(requests.every(r=>r.method==='GET')).toBe(true);
});
it('cancels a stale detail response after choosing another record',async()=>{
 const stale=deferred<Response>();const api=createAuditRecordApi(async request=>{const url=new URL(request.url);if(url.pathname.endsWith(first))return stale.promise;if(url.pathname.endsWith(second))return response(row(second));return response({items:[row(),row(second)]});});
 render(<AuditRecordPage session={testSession()} api={api}/>);await screen.findByRole('button',{name:/02:00|10:00/});fireEvent.click(screen.getByRole('button',{name:/02:00|10:00/}));await waitFor(()=>expect(within(screen.getByLabelText('审计记录详情')).getByText('主管二')).toBeVisible());stale.resolve(response({...row(),summary:'过期私密说明'}));await waitFor(()=>expect(screen.queryByText('过期私密说明')).not.toBeInTheDocument());
});
