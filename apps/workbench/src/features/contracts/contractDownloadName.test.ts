import {expect,it} from 'vitest';
import {contractDownloadName} from './contractDownloadName';
it('uses only an allowed suffix from the exact evidence version',()=>{
 expect(contractDownloadName(2,'exact',[{id:'other',label:'other.pdf'},{id:'exact',label:'private/path/name.PNG'}])).toBe('委托合同-第2版.png');
});
it('falls back to bin for missing evidence or unsupported/disguised suffixes',()=>{
 expect(contractDownloadName(1,'missing',[{id:'other',label:'other.pdf'}])).toBe('委托合同-第1版.bin');
 for(const label of ['body.pdf.exe','body.docx','body.pdf/other'])expect(contractDownloadName(1,'exact',[{id:'exact',label}])).toBe('委托合同-第1版.bin');
});
