import type { IdentityCommand } from "./useIdentityCommand";
import { IdentityDialog } from "./IdentityActionConfirmation";
import { authorityLabels,formatIdentityInstant } from "./identityLabels";
export function IdentityBatchConfirmation({command}:{command:IdentityCommand}){
 const preview=command.batchPreview;if(!preview)return null;
 return <IdentityDialog title="确认批量授权" onCancel={command.cancelBatchPreview} locked={false} returnFocus={command.dialogTrigger}>
  <p>授权任职：<strong>{preview.summary.appointment}</strong></p><p>组织范围：<strong>{preview.summary.organization}</strong></p>
  <p>有效期：{formatIdentityInstant(preview.summary.validFrom)} 至 {preview.summary.validUntil?formatIdentityInstant(preview.summary.validUntil):"长期"}</p>
  <p>本次共 {preview.drafts.length} 项权限：</p><ul>{preview.drafts.map(item=><li key={item.body.authorityCode}>{authorityLabels[item.body.authorityCode]}</li>)}</ul>
  <p>每项分别生成授权和回执。遇到拒绝或结果不明即暂停，已成功项保留；本次不自动修改已有授权。</p>
  <div className="identity-form-actions"><button className="identity-primary" onClick={()=>void command.startBatch()}>确认授予 {preview.drafts.length} 项权限</button><button onClick={command.cancelBatchPreview}>返回修改</button></div>
 </IdentityDialog>;
}
const labels={PENDING:"待提交",SENDING:"正在提交",SUCCEEDED:"成功",REJECTED:"拒绝",UNKNOWN:"结果未确认",NOT_SUBMITTED:"未提交"};
export function IdentityBatchResults({command}:{command:IdentityCommand}){
 const batch=command.batch;if(!batch)return null;
 const count=(state:keyof typeof labels)=>batch.entries.filter(item=>item.state===state).length;
 return <section className="identity-command-feedback" aria-label="批量授权结果">
  <h2>批量授权结果</h2><p>{batch.summary.appointment} · {batch.summary.organization}</p>
  <p aria-live="polite">批量办理{batch.running?"进行中":batch.paused?"暂停":"结束"}：成功 {count("SUCCEEDED")} 项，拒绝 {count("REJECTED")} 项，结果未确认 {count("UNKNOWN")} 项，未提交 {count("NOT_SUBMITTED")} 项。</p>
  <ul className="identity-batch-results">{batch.entries.map(item=><li key={item.authorityCode}><span>{authorityLabels[item.authorityCode]}</span><strong>{labels[item.state]}</strong></li>)}</ul>
  {!batch.running&&batch.paused&&<p>已成功项无需再次提交。未确认项请核对原回执；未提交项需重新选择并确认，系统不会自动继续办理。</p>}
 </section>;
}
