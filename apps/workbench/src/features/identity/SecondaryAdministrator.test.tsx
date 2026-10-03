import {fireEvent,render,screen,within} from '@testing-library/react';
import {expect,it} from 'vitest';
import {IdentityAdminApplication} from './IdentityAdminApplication';
import {fixture,json,authorities} from './identityWriteFixtures';
import {draftId,selectorId} from '../../test/fixtures';
const path='/admin/identity/authority-grants' as const;
const management=['IDENTITY_PRINCIPAL_MANAGE','IDENTITY_ORGANIZATION_MANAGE','IDENTITY_APPOINTMENT_MANAGE','IDENTITY_AUTHORITY_MANAGE'];
it('offers an explicit four-permission management group only from qualified server candidates',async()=>{
 const f=fixture(path,{handle:async request=>{const url=new URL(request.url);if(url.pathname.endsWith('/options')){const kind=url.searchParams.get('optionKind');return json({page:'AUTHORITY_GRANTS',optionKind:kind,candidates:{items:[{id:kind==='ORGANIZATION'?draftId:selectorId,label:kind==='ORGANIZATION'?'律所':'第二管理员'}],nextCursor:null},roleCodes:[],grantableAuthorityCodes:[...authorities,...management]});}}});
 render(<IdentityAdminApplication session={f.session} api={f.api} path={path} onNavigate={()=>{}} sessionActions={null}/>);
 fireEvent.click(await screen.findByRole('button',{name:'新增直接授权'}));const group=await screen.findByRole('group',{name:'系统管理权限'});expect(within(group).getAllByRole('checkbox')).toHaveLength(5);expect(f.writes).toHaveLength(0);
 fireEvent.click(within(group).getByRole('checkbox',{name:'全选系统管理权限'}));expect(screen.getByText('已选择 4 项权限')).toBeInTheDocument();expect(f.writes).toHaveLength(0);
});
it('keeps business-only candidates valid and hides management choices',async()=>{
 const f=fixture(path);render(<IdentityAdminApplication session={f.session} api={f.api} path={path} onNavigate={()=>{}} sessionActions={null}/>);fireEvent.click(await screen.findByRole('button',{name:'新增直接授权'}));await screen.findByRole('option',{name:'陈晓'});expect(screen.queryByRole('group',{name:'系统管理权限'})).not.toBeInTheDocument();
});
