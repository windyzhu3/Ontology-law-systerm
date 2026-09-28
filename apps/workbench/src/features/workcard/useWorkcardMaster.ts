import {useEffect} from 'react';
import master from '../opportunities/ledgerMaster.css?inline';
import controls from '../contracts/contractControls.css?inline';
/** Each mounted workcard owns its frozen styles; ledger unmount must not remove its layout. */
export function useWorkcardMaster(){
 useEffect(()=>{const style=document.createElement('style');style.textContent=master+controls;document.head.append(style);return()=>style.remove();},[]);
}
