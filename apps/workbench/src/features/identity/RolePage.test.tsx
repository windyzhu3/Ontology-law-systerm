import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { expect, it } from "vitest";
import { IdentityAdminApplication } from "./IdentityAdminApplication";
import { fixture, rows, tag } from "./identityWriteFixtures";
const path="/admin/identity/roles" as const;
function mount(f:ReturnType<typeof fixture>){render(<IdentityAdminApplication session={f.session} api={f.api} path={path} onNavigate={()=>{}} sessionActions={null}/>);}
it("creates a role with a stable code, no grants, and a proven receipt",async()=>{
 const f=fixture(path);mount(f);fireEvent.click(await screen.findByRole("button",{name:"新增岗位"}));
 fireEvent.change(screen.getByLabelText("岗位代码"),{target:{value:"CUSTOM_REVIEWER"}});
 fireEvent.change(screen.getByLabelText("显示名称"),{target:{value:"独立审核员"}});
 fireEvent.click(screen.getByRole("button",{name:"确认创建"}));
 await waitFor(()=>expect(f.writes).toHaveLength(1));
 expect(await f.writes[0].clone().json()).toEqual({code:"CUSTOM_REVIEWER",displayName:"独立审核员"});
 await waitFor(()=>expect(f.api.recovery.read()).toBeNull());
});
it.each([['ACTIVE','停用岗位','停用','deactivate'],['INACTIVE','恢复岗位','恢复','reactivate']])("%s catalogue state requires confirmation and exact version",async(state,label,verb,action)=>{
 const f=fixture(path,{row:{...rows[path],state}});mount(f);fireEvent.click(await screen.findByRole("button",{name:label}));
 const dialog=screen.getByRole("dialog");expect(dialog).toHaveTextContent(verb==="停用"?"已有任职、授权和业务责任不会被暂停":"不自动授予任何权限");
 fireEvent.change(within(dialog).getByLabelText("操作原因"),{target:{value:"ADMINISTRATIVE_ACTION"}});
 fireEvent.click(within(dialog).getByRole("button",{name:`确认${verb}`}));
 await waitFor(()=>expect(f.writes).toHaveLength(1));expect(f.writes[0].url).toContain(`/roles/${rows[path].id}/${action}`);expect(f.writes[0].headers.get("If-Match")).toBe(tag);
 await waitFor(()=>expect(f.api.recovery.read()).toBeNull());
});
