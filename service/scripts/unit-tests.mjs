import { spawnSync } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';

// No credentials or external adapters are loaded by this entrypoint.
const seed = process.env.PBT_SEED || '20261009';
const count = process.env.PBT_CASES || '100';
mkdirSync('target', { recursive: true });
const result = spawnSync(process.execPath, ['node_modules/shadow-cljs/cli/runner.js', 'compile', 'test'], {
  encoding: 'utf8', env: { ...process.env, RF_FORCE_MOCK: '1', PBT_SEED: seed, PBT_CASES: count },
  maxBuffer: 8 * 1024 * 1024,
});
const output = `${result.stdout || ''}${result.stderr || ''}`;
writeFileSync('target/unit-results.txt', `seed=${seed}\ncases=${count}\n${output}`);
process.stdout.write(output);
process.exit(result.status ?? 1);
