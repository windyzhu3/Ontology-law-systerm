import fs from 'node:fs';
import path from 'node:path';

// Reserve before opening a form; record its exact request before route.continue.
// An uncertain outcome never clears this reservation or permits a fresh key.
export function reserveAttempt(runtime, name, initial) {
  if (!/^[A-Za-z0-9_-]+$/.test(name)) throw Error('Invalid attempt name');
  const file = path.join(runtime, name + '-attempt.json');
  const record = { ...initial, state: 'RESERVED' };
  const fd = fs.openSync(file, 'wx');
  try { fs.writeFileSync(fd, JSON.stringify(record, null, 2)); fs.fsyncSync(fd); } finally { fs.closeSync(fd); }
  function persist() {
    const temporary = file + '.tmp';
    const handle = fs.openSync(temporary, 'w');
    try { fs.writeFileSync(handle, JSON.stringify(record, null, 2)); fs.fsyncSync(handle); } finally { fs.closeSync(handle); }
    fs.renameSync(temporary, file);
  }
  return {
    dispatch(request) {
      if (record.state !== 'RESERVED') throw Error('Original dispatch already recorded; refuse second submission');
      const headers = request.headers();
      const commandId = headers['idempotency-key'];
      if (!commandId) throw Error('Original command key missing; refuse dispatch');
      Object.assign(record, { state: 'DISPATCHED', commandId,
        method: request.method(), endpoint: new URL(request.url()).pathname,
        body: request.postDataJSON(), preconditions: {
          ifMatch: headers['if-match'] ?? null, ifNoneMatch: headers['if-none-match'] ?? null,
        } });
      persist();
    },
    complete(status, receipt) {
      if (record.state !== 'DISPATCHED') throw Error('Original dispatch not recorded');
      Object.assign(record, { state: 'OBSERVED', status, receipt }); persist();
    },
  };
}
