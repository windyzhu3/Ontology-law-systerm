import type {SessionContext} from './sessionController';
import '../../styles/sharedControls.css';

/** One identity label for every admitted page; labels come from the selected session. */
export function SessionActions({context,onSwitch,onLogout}:{context:SessionContext;onSwitch:()=>void;onLogout:()=>void}) {
 const own=context.appointmentChoices.find(choice=>choice.id===context.selectedAppointmentId)?.label;
 const delegated=context.delegatedAppointmentChoices.find(choice=>choice.id===context.selectedOnBehalfAppointmentId)?.label;
 return <div className="session-actions session-controls"><span className="session-identity">{context.displayName}{own?` · ${own}`:''}{delegated?`（代办：${delegated}）`:''}</span><div className="session-buttons"><button type="button" onClick={onSwitch}>切换任职</button><button type="button" onClick={onLogout}>退出</button></div></div>;
}
