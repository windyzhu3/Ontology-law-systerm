/** T06 currently accepts PDF/JPEG/PNG. Never reuse a supplied basename or path. */
export function contractDownloadName(version:number,evidenceId:string|undefined,documents:readonly {id:string;label:string}[]|undefined):string {
 const label=documents?.find(document=>document.id===evidenceId)?.label;
 const extension=label?.match(/\.(pdf|png|jpg|jpeg)$/i)?.[1].toLowerCase()??'bin';
 return `委托合同-第${version}版.${extension}`;
}
