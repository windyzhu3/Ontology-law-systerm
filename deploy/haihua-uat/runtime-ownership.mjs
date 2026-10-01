import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { execFileSync } from 'node:child_process';

const prefixes = {
  'haihua-uat-runtime': 'ontology-law-haihua-uat',
  'haihua-restore-e1': 'ontology-law-haihua-restore-e1',
  'haihua-restore-g_completed': 'ontology-law-haihua-restore-g_completed',
  'haihua-restore-perf_e1': 'ontology-law-haihua-restore-perf_e1',
};
const normalize = value => String(value).replaceAll('\\', '/').toLowerCase();
const processInfo = pid => JSON.parse(execFileSync('pwsh', ['-NoProfile', '-NonInteractive', '-Command',
  `$p=Get-CimInstance Win32_Process -Filter 'ProcessId=${pid}'; if($p){$p | Select-Object ExecutablePath,CommandLine | ConvertTo-Json -Compress}`], { encoding: 'utf8' }));
const containersInfo = names => JSON.parse(execFileSync('docker', ['inspect', ...names], { encoding: 'utf8' }));

// Canonical clone URLs identify the logical tenant, not the physical instance.
// Verify ownership independently of successful login and shared credentials.
export function assertRuntimeActive(runtime, inspectProcess = processInfo, inspectContainers = containersInfo) {
  try {
    const prefix = prefixes[path.basename(runtime)];
    if (!prefix) throw Error();
    const registry = JSON.parse(fs.readFileSync(path.join(runtime, 'processes.json'), 'utf8'));
    const deployment = JSON.parse(fs.readFileSync(path.join(runtime, 'deployment.json'), 'utf8'));
    const jar = fs.readFileSync(path.join(runtime, 'app.jar'));
    if (crypto.createHash('sha256').update(jar).digest('hex') !== deployment.releaseDigest) throw Error();
    for (const name of ['spa', 'api']) {
      const record = registry[name];
      if (!record || !Number.isSafeInteger(record.pid) || record.pid <= 0) throw Error();
      const actual = inspectProcess(record.pid);
      if (!actual || normalize(path.resolve(actual.ExecutablePath)) !== normalize(path.resolve(record.args[0]))) throw Error();
      const command = normalize(actual.CommandLine);
      const file = name === 'api' ? 'app.jar' : 'server.mjs';
      if (!command.includes(normalize(path.join(runtime, file)))) throw Error();
      if (name === 'api' && !command.includes(normalize(path.join(runtime, 'application.properties')))) throw Error();
    }
    const expected = [[prefix + '-business-db', '5432/tcp', '20546'],
      [prefix + '-keycloak', '8443/tcp', '20543']];
    const containers = inspectContainers(expected.map(([name]) => name));
    for (const [name, port, hostPort] of expected) {
      const container = containers.find(item => item.Name === '/' + name);
      if (!container?.State?.Running || !container.NetworkSettings?.Ports?.[port]?.some(
        binding => binding.HostPort === hostPort && binding.HostIp === '127.0.0.1')) throw Error();
    }
    return true;
  } catch {
    // Never leak process/configuration or Docker environment output into errors.
    throw Error('Selected Haihua runtime is not the active verified SPA/API/database/identity instance; refuse requests');
  }
}
