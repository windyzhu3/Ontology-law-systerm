"""T06 exact successor: immutable upload basis, check history and material versions."""
from dataclasses import replace
from ..helpers import *
from ..model import Column, ContractEvolution
from ..render import _render_table, _render_foreign_key
from .v920_r2_customer_requirements import fk, checks, body

BASIS=tenant_table('evidence','material_upload_basis','material_upload_basis_id','材料上传准确责任及受保护元数据；不改变既有证据链。',(
 revision_col(),uuid_col('upload_session_id','唯一既有上传会话。'),uuid_col('opportunity_id','准确商机。'),bigint_col('opportunity_revision','准确商机版本。'),code_col('responsibility_type','准确责任类型。'),uuid_col('responsibility_id','准确责任身份。'),bigint_col('responsibility_revision','准确责任版本。'),uuid_col('owner_appointment_id','当前责任任职。'),uuid_col('customer_confirmation_id','可选准确客户确认不可变版本。',nullable=True),uuid_col('original_task_id','原跟进事项。',nullable=True),bigint_col('original_task_revision','原事项准确版本。',nullable=True),uuid_col('expected_previous_version_id','补交所期望的准确前版。',nullable=True),*body(),time_col('created_at','冻结时间。')),
 constraints=(*checks('material_upload_basis'),unique('uq_material_upload_basis__session',('tenant_id','upload_session_id'),'一会话唯一依据。'),check('ck_material_upload_basis__identity','material_upload_basis_id=upload_session_id','会话与不可变依据共享身份但版本语义独立。'),check('ck_material_upload_basis__body','octet_length(body_ciphertext) BETWEEN 29 AND 131072','受保护文件名及说明大小。'),check('ck_material_upload_basis__selectors',"opportunity_revision BETWEEN 0 AND 9007199254740991 AND responsibility_revision BETWEEN 0 AND 9007199254740991 AND responsibility_type IN ('opportunity.opportunity','opportunity.responsibility_handoff') AND ((original_task_id IS NULL AND original_task_revision IS NULL) OR (original_task_id IS NOT NULL AND original_task_revision IS NOT NULL AND original_task_revision BETWEEN 0 AND 9007199254740991))",'完整准确选择器。')),
 foreign_keys=(fk('material_upload_basis','upload_session_id','evidence','upload_session'),fk('material_upload_basis','opportunity_id','opportunity','opportunity'),fk('material_upload_basis','owner_appointment_id','identity','appointment'),fk('material_upload_basis','customer_confirmation_id','opportunity','customer_requirement_confirmation'),fk('material_upload_basis','original_task_id','responsibility','task_occurrence'),fk('material_upload_basis','expected_previous_version_id','opportunity','material_version')))
CHECK=tenant_table('evidence','material_upload_check','material_upload_check_id','技术处理状态不可变历史；未知禁止静默重传。',(
 revision_col(),uuid_col('upload_basis_id','准确上传依据。'),uuid_col('previous_check_id','准确前次检查状态。',nullable=True),code_col('status','技术状态。',length=32),code_col('result_code','静态安全结果码，不保存正文。',nullable=True),time_col('created_at','状态形成时间。')),
 constraints=(*checks('material_upload_check'),unique('uq_material_upload_check__previous',('tenant_id','previous_check_id'),'检查状态单一后继。'),check('ck_material_upload_check__status',"status IN ('CHECKING','UNKNOWN','SCAN_UNAVAILABLE','PASSED','REJECTED')",'技术状态域。'),check('ck_material_upload_check__code',"result_code IS NULL OR result_code ~ '^[A-Z][A-Z0-9_]{0,63}$'",'有界安全结果代码。')),
 foreign_keys=(fk('material_upload_check','upload_basis_id','evidence','material_upload_basis'),fk('material_upload_check','previous_check_id','evidence','material_upload_check')))
VERSION=tenant_table('opportunity','material_version','material_version_id','材料条目不可变版本；当前版以无后继派生，历史引用保留。',(
 revision_col(),uuid_col('material_item_id','首版本身份作为稳定条目身份。'),uuid_col('previous_version_id','准确前版。',nullable=True),uuid_col('upload_basis_id','准确上传依据。'),uuid_col('upload_session_id','既有上传会话。'),uuid_col('received_source_object_id','既有扫描来源。'),uuid_col('evidence_submission_id','既有不可变提交。'),uuid_col('evidence_binding_id','既有准确绑定。'),uuid_col('opportunity_id','所属商机。'),code_col('purpose_code','静态材料用途。'),*body(),uuid_col('received_by_appointment_id','实际接收任职。'),time_col('received_at','人工接收时间。')),
 constraints=(*checks('material_version'),*(unique('uq_material_version__'+n,('tenant_id',n),'准确链节点只能接收一次。') for n in ('previous_version_id','upload_basis_id','upload_session_id','evidence_submission_id','evidence_binding_id')),check('ck_material_version__purpose',"purpose_code IN ('CONTRACT_BUSINESS','CORRESPONDENCE','OTHER')",'用途不是案件分类。'),check('ck_material_version__body','octet_length(body_ciphertext) BETWEEN 29 AND 131072','加密材料元数据有界。')),
 foreign_keys=(fk('material_version','previous_version_id','opportunity','material_version'),fk('material_version','upload_basis_id','evidence','material_upload_basis'),fk('material_version','upload_session_id','evidence','upload_session'),fk('material_version','received_source_object_id','evidence','received_source_object'),fk('material_version','evidence_submission_id','evidence','evidence_submission'),fk('material_version','evidence_binding_id','evidence','evidence_binding'),fk('material_version','opportunity_id','opportunity','opportunity'),fk('material_version','received_by_appointment_id','identity','appointment')))
TABLES=(BASIS,CHECK,VERSION)
def apply_evolution(schemas):
 return tuple(replace(s,tables=(*s.tables,*(t for t in TABLES if t.schema==s.name))) for s in schemas)
def render_sql(before,after):
 sql='\n'.join(_render_table(t) for t in TABLES)+'\n'
 for t in TABLES:
  sql+='\n'.join(_render_foreign_key(t,f) for f in t.foreign_keys)+'\n'
  sql+=f'''CREATE TRIGGER trg_{t.name}__mutation_guard BEFORE UPDATE OR DELETE ON {t.schema}.{t.name} FOR EACH ROW EXECUTE FUNCTION platform_meta.fn_reject_fact_mutation();
COMMENT ON TRIGGER trg_{t.name}__mutation_guard ON {t.schema}.{t.name} IS '不可变材料事实禁止改写或删除。';
REVOKE ALL ON {t.schema}.{t.name} FROM PUBLIC, ${{app_command_role}}, ${{app_query_role}}, ${{app_worker_role}}, ${{audit_append_role}};
GRANT SELECT, INSERT ON {t.schema}.{t.name} TO ${{app_command_role}};
GRANT SELECT ON {t.schema}.{t.name} TO ${{app_query_role}};
'''
 return sql+CONSISTENCY_SQL

CONSISTENCY_SQL=r'''
CREATE FUNCTION evidence.fn_check_material_basis_current(b evidence.material_upload_basis) RETURNS void
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE o opportunity.opportunity%ROWTYPE;
BEGIN
 SELECT * INTO o FROM opportunity.opportunity WHERE tenant_id=b.tenant_id AND opportunity_id=b.opportunity_id FOR UPDATE;
 IF o.opportunity_id IS NULL OR o.closed_at IS NOT NULL OR o.revision IS DISTINCT FROM b.opportunity_revision THEN RAISE EXCEPTION 'material opportunity differs' USING ERRCODE='23514'; END IF;
 IF b.responsibility_type='opportunity.opportunity' THEN
  IF b.responsibility_id<>b.opportunity_id OR b.responsibility_revision<>b.opportunity_revision OR b.owner_appointment_id<>o.owner_appointment_id OR EXISTS(SELECT 1 FROM opportunity.responsibility_handoff WHERE tenant_id=b.tenant_id AND opportunity_id=b.opportunity_id) THEN RAISE EXCEPTION 'material responsibility differs' USING ERRCODE='23514'; END IF;
 ELSE
  IF NOT EXISTS(SELECT 1 FROM opportunity.responsibility_handoff h WHERE h.tenant_id=b.tenant_id AND h.responsibility_handoff_id=b.responsibility_id AND h.revision=b.responsibility_revision AND h.opportunity_id=b.opportunity_id AND h.to_appointment_id=b.owner_appointment_id AND NOT EXISTS(SELECT 1 FROM opportunity.responsibility_handoff n WHERE n.tenant_id=h.tenant_id AND n.prior_basis_type='opportunity.responsibility_handoff' AND n.prior_basis_id=h.responsibility_handoff_id)) THEN RAISE EXCEPTION 'material responsibility differs' USING ERRCODE='23514'; END IF;
 END IF;
END;
$fn$;
COMMENT ON FUNCTION evidence.fn_check_material_basis_current(evidence.material_upload_basis) IS '上传及接收均锁商机重验准确当前责任。';
REVOKE ALL ON FUNCTION evidence.fn_check_material_basis_current(evidence.material_upload_basis) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION evidence.fn_check_material_basis_current(evidence.material_upload_basis) TO ${app_command_role};
CREATE FUNCTION evidence.fn_check_material_basis() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
BEGIN
 PERFORM evidence.fn_check_material_basis_current(NEW);
 IF NOT EXISTS(SELECT 1 FROM evidence.upload_session u WHERE u.tenant_id=NEW.tenant_id AND u.upload_session_id=NEW.upload_session_id AND u.target_type='opportunity.opportunity' AND u.target_id=NEW.opportunity_id AND u.target_revision=NEW.opportunity_revision AND u.target_hash IS NULL AND u.created_by_appointment_id=NEW.owner_appointment_id AND u.status='OPEN' AND u.expires_at>clock_timestamp() AND u.purpose_code IN ('CONTRACT_BUSINESS','CORRESPONDENCE','OTHER')) THEN RAISE EXCEPTION 'material session differs' USING ERRCODE='23514'; END IF;
 IF NEW.customer_confirmation_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM opportunity.customer_requirement_confirmation c WHERE c.tenant_id=NEW.tenant_id AND c.customer_requirement_confirmation_id=NEW.customer_confirmation_id AND c.opportunity_id=NEW.opportunity_id) THEN RAISE EXCEPTION 'material confirmation differs' USING ERRCODE='23514'; END IF;
 IF NEW.original_task_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM responsibility.task_occurrence t WHERE t.tenant_id=NEW.tenant_id AND t.task_occurrence_id=NEW.original_task_id AND t.revision=NEW.original_task_revision AND t.subject_type='opportunity.opportunity' AND t.subject_id=NEW.opportunity_id) THEN RAISE EXCEPTION 'material original task differs' USING ERRCODE='23514'; END IF;
 IF NEW.expected_previous_version_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM opportunity.material_version v JOIN evidence.upload_session u ON u.tenant_id=NEW.tenant_id AND u.upload_session_id=NEW.upload_session_id WHERE v.tenant_id=NEW.tenant_id AND v.material_version_id=NEW.expected_previous_version_id AND v.opportunity_id=NEW.opportunity_id AND v.purpose_code=u.purpose_code AND NOT EXISTS(SELECT 1 FROM opportunity.material_version n WHERE n.tenant_id=v.tenant_id AND n.previous_version_id=v.material_version_id)) THEN RAISE EXCEPTION 'material predecessor differs' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END;
$fn$;
COMMENT ON FUNCTION evidence.fn_check_material_basis() IS '会话依据冻结准确归属、原事项和可选确认版本。';
REVOKE ALL ON FUNCTION evidence.fn_check_material_basis() FROM PUBLIC;
CREATE TRIGGER trg_material_upload_basis__source BEFORE INSERT ON evidence.material_upload_basis FOR EACH ROW EXECUTE FUNCTION evidence.fn_check_material_basis();
COMMENT ON TRIGGER trg_material_upload_basis__source ON evidence.material_upload_basis IS '上传前准确依据守卫。';
CREATE FUNCTION evidence.fn_check_material_check() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE p evidence.material_upload_check%ROWTYPE;
BEGIN
 PERFORM 1 FROM evidence.upload_session u JOIN evidence.material_upload_basis b ON b.tenant_id=u.tenant_id AND b.upload_session_id=u.upload_session_id WHERE b.tenant_id=NEW.tenant_id AND b.material_upload_basis_id=NEW.upload_basis_id FOR UPDATE OF u;
 IF NEW.previous_check_id IS NULL THEN
  IF NEW.status<>'CHECKING' OR EXISTS(SELECT 1 FROM evidence.material_upload_check WHERE tenant_id=NEW.tenant_id AND upload_basis_id=NEW.upload_basis_id) THEN RAISE EXCEPTION 'material check transition differs' USING ERRCODE='23514'; END IF;
 ELSE
  SELECT * INTO p FROM evidence.material_upload_check WHERE tenant_id=NEW.tenant_id AND material_upload_check_id=NEW.previous_check_id;
  IF p.upload_basis_id IS DISTINCT FROM NEW.upload_basis_id OR p.status IN ('PASSED','REJECTED') OR NEW.status='CHECKING' OR NEW.created_at<p.created_at THEN RAISE EXCEPTION 'material check transition differs' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END;
$fn$;
COMMENT ON FUNCTION evidence.fn_check_material_check() IS '技术检查历史单一后继，不允许未知后重发字节。';
REVOKE ALL ON FUNCTION evidence.fn_check_material_check() FROM PUBLIC;
CREATE TRIGGER trg_material_upload_check__source BEFORE INSERT ON evidence.material_upload_check FOR EACH ROW EXECUTE FUNCTION evidence.fn_check_material_check();
COMMENT ON TRIGGER trg_material_upload_check__source ON evidence.material_upload_check IS '检查结果不可倒退。';
CREATE FUNCTION opportunity.fn_check_material_version() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,pg_temp AS $fn$
DECLARE b evidence.material_upload_basis%ROWTYPE;
BEGIN
 SELECT * INTO b FROM evidence.material_upload_basis WHERE tenant_id=NEW.tenant_id AND material_upload_basis_id=NEW.upload_basis_id;
 IF b.material_upload_basis_id IS NULL THEN RAISE EXCEPTION 'material basis absent' USING ERRCODE='23514'; END IF;
 PERFORM evidence.fn_check_material_basis_current(b);
 IF b.opportunity_id<>NEW.opportunity_id OR b.upload_session_id<>NEW.upload_session_id OR b.owner_appointment_id<>NEW.received_by_appointment_id OR b.expected_previous_version_id IS DISTINCT FROM NEW.previous_version_id OR b.body_ciphertext<>NEW.body_ciphertext OR b.body_digest<>NEW.body_digest THEN RAISE EXCEPTION 'material basis differs' USING ERRCODE='23514'; END IF;
 IF NEW.previous_version_id IS NULL THEN
  IF NEW.material_item_id<>NEW.material_version_id OR EXISTS(SELECT 1 FROM opportunity.material_version v WHERE v.tenant_id=NEW.tenant_id AND v.material_item_id=NEW.material_item_id) THEN RAISE EXCEPTION 'material predecessor differs' USING ERRCODE='23514'; END IF;
 ELSE
  IF NOT EXISTS(SELECT 1 FROM opportunity.material_version p WHERE p.tenant_id=NEW.tenant_id AND p.material_version_id=NEW.previous_version_id AND p.material_item_id=NEW.material_item_id AND p.opportunity_id=NEW.opportunity_id AND p.purpose_code=NEW.purpose_code) THEN RAISE EXCEPTION 'material predecessor differs' USING ERRCODE='23514'; END IF;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM evidence.upload_session u JOIN evidence.received_source_object r ON r.tenant_id=u.tenant_id AND r.upload_session_id=u.upload_session_id JOIN evidence.evidence_submission s ON s.tenant_id=r.tenant_id AND s.received_source_object_id=r.received_source_object_id JOIN evidence.evidence_binding e ON e.tenant_id=s.tenant_id AND e.evidence_submission_id=s.evidence_submission_id WHERE u.tenant_id=NEW.tenant_id AND u.upload_session_id=NEW.upload_session_id AND u.status='FINALIZED' AND u.purpose_code=NEW.purpose_code AND u.target_type='opportunity.opportunity' AND u.target_id=NEW.opportunity_id AND u.target_revision=b.opportunity_revision AND u.expires_at>clock_timestamp() AND r.received_source_object_id=NEW.received_source_object_id AND r.scan_result='PASSED' AND r.size_bytes BETWEEN 1 AND 20971520 AND r.detected_media_type IN ('application/pdf','image/jpeg','image/png') AND s.evidence_submission_id=NEW.evidence_submission_id AND s.submitted_by_appointment_id=NEW.received_by_appointment_id AND e.evidence_binding_id=NEW.evidence_binding_id AND e.purpose_code=NEW.purpose_code AND e.bound_by_appointment_id=NEW.received_by_appointment_id AND e.target_type=u.target_type AND e.target_id=u.target_id AND e.target_revision=u.target_revision AND e.target_hash IS NULL AND e.revoked_at IS NULL) THEN RAISE EXCEPTION 'material evidence chain differs' USING ERRCODE='23514'; END IF;
 IF NOT EXISTS(SELECT 1 FROM evidence.material_upload_check c WHERE c.tenant_id=NEW.tenant_id AND c.upload_basis_id=NEW.upload_basis_id AND c.status='PASSED' AND NOT EXISTS(SELECT 1 FROM evidence.material_upload_check n WHERE n.tenant_id=c.tenant_id AND n.previous_check_id=c.material_upload_check_id)) THEN RAISE EXCEPTION 'material check not passed' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END;
$fn$;
COMMENT ON FUNCTION opportunity.fn_check_material_version() IS '接收原子引用原证据链、重验责任与单一材料后继。';
REVOKE ALL ON FUNCTION opportunity.fn_check_material_version() FROM PUBLIC;
CREATE TRIGGER trg_material_version__source BEFORE INSERT ON opportunity.material_version FOR EACH ROW EXECUTE FUNCTION opportunity.fn_check_material_version();
COMMENT ON TRIGGER trg_material_version__source ON opportunity.material_version IS '接收版本准确链守卫。';
DO $v930$
BEGIN
 UPDATE platform_meta.deployment_state SET schema_contract_version='52-plus-2-r2-v7',revision=revision+1,changed_at=clock_timestamp() WHERE deployment_state_key='PRIMARY' AND schema_contract_version='52-plus-2-r2-v6';
 IF NOT FOUND THEN RAISE EXCEPTION 'V930 requires 52-plus-2-r2-v6' USING ERRCODE='55000'; END IF;
END;
$v930$;
'''
EVOLUTION=ContractEvolution(version=930,migration_name='V930__r2_materials.sql',contract_version='52-plus-2-r2-v7',apply=apply_evolution,render_sql=render_sql)
