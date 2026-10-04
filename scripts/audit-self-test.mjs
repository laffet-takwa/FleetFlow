// Proves the audits can fail. A guard that has never been observed to fire is not a
// guard, so each check is fed a known defect and must report it.
//
// Run from the repository root:  node scripts/audit-self-test.mjs

import { execFileSync } from 'node:child_process'
import { mkdtempSync, writeFileSync, readFileSync, cpSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

let failures = 0

/**
 * Feeds a known defect to an audit and requires that the audit (a) exits non-zero and
 * (b) actually names the defect. Without the keyword check a script that simply crashes
 * on a syntax error would look like a passing detector, which is worse than no check.
 */
function expectDetection(label, script, mutate, expectedKeyword) {
  const workspace = mkdtempSync(join(tmpdir(), 'fleetflow-audit-'))
  try {
    // Both scripts walk from the repository root, so give them a copy to work on.
    cpSync('backend', join(workspace, 'backend'), { recursive: true })
    cpSync('frontend/src', join(workspace, 'frontend', 'src'), { recursive: true })
    cpSync('docker-compose.yml', join(workspace, 'docker-compose.yml'))
    cpSync('scripts', join(workspace, 'scripts'), { recursive: true })
    mutate(workspace)

    let output = ''
    let code = 0
    try {
      output = execFileSync(process.execPath, [join(workspace, 'scripts', script)], {
        cwd: workspace,
        encoding: 'utf8',
        stdio: ['ignore', 'pipe', 'pipe'],
      })
    } catch (error) {
      code = error.status ?? 1
      output = `${error.stdout ?? ''}${error.stderr ?? ''}`
    }

    if (code === 0) {
      console.log(`  FAIL  ${label}: the audit passed on a known defect`)
      failures += 1
    } else if (!output.includes(expectedKeyword)) {
      console.log(`  FAIL  ${label}: exited ${code} but never reported "${expectedKeyword}" (crashed?)`)
      failures += 1
    } else {
      console.log(`  PASS  ${label}: reported "${expectedKeyword}" and exited ${code}`)
    }
  } finally {
    rmSync(workspace, { recursive: true, force: true })
  }
}

console.log('Self-test for the repository audits\n')

expectDetection(
  'duplicate top-level YAML key',
  'audit-repo.mjs',
  (workspace) => {
    const target = join(
      workspace,
      'backend',
      'order-service',
      'src',
      'main',
      'resources',
      'application.yml',
    )
    writeFileSync(target, `${readFileSync(target, 'utf8')}\nfleetflow:\n  injected: true\n`)
  },
  'duplicate YAML key',
)

expectDetection(
  'de-indented YAML key',
  'audit-repo.mjs',
  (workspace) => {
    const target = join(
      workspace,
      'backend',
      'auth-service',
      'src',
      'main',
      'resources',
      'application.yml',
    )
    const source = readFileSync(target, 'utf8').replace(
      '    com.fleetflow: ${LOG_LEVEL_COM_FLEETFLOW:INFO}',
      'com.fleetflow: ${LOG_LEVEL_COM_FLEETFLOW:INFO}',
    )
    writeFileSync(target, source)
  },
  'de-indented YAML key',
)

expectDetection(
  'ambiguous request mapping',
  'audit-repo.mjs',
  (workspace) => {
    const target = join(
      workspace,
      'backend',
      'order-service',
      'src',
      'main',
      'java',
      'com',
      'fleetflow',
      'order',
      'controller',
      'OrderController.java',
    )
    // GET /api/orders already exists there. Declaring a second controller for the same
    // path is the shape of mistake a merge produces, and Spring refuses to start on it.
    writeFileSync(
      target,
      `${readFileSync(target, 'utf8')}\n` +
        '@RestController\n' +
        '@RequestMapping("/api")\n' +
        'class DuplicateMappingFixture {\n' +
        '    @GetMapping(value = "/orders")\n' +
        '    void duplicate() { }\n' +
        '}\n',
    )
  },
  'ambiguous mapping',
)

expectDetection(
  'committed credential',
  'audit-repo.mjs',
  (workspace) => {
    const target = join(workspace, 'docker-compose.yml')
    writeFileSync(
      target,
      `${readFileSync(target, 'utf8')}\n# DB_PASSWORD=a-real-looking-production-value\n`,
    )
  },
  'possible credential',
)

expectDetection(
  'SPA call with no endpoint',
  'audit-api.mjs',
  (workspace) => {
    const target = join(workspace, 'frontend', 'src', 'services', 'productApi.ts')
    writeFileSync(
      target,
      `${readFileSync(target, 'utf8')}\nexport const injected = () => get('/products/does-not-exist')\n`,
    )
  },
  'no matching endpoint',
)

expectDetection(
  'SPA call outside the gateway route table',
  'audit-api.mjs',
  (workspace) => {
    const target = join(workspace, 'frontend', 'src', 'services', 'productApi.ts')
    writeFileSync(
      target,
      `${readFileSync(target, 'utf8')}\nexport const injected = () => get('/inventories/9')\n`,
    )
  },
  'not covered by a gateway route prefix',
)

console.log('')
if (failures === 0) {
  console.log('All audits detect a known defect.')
} else {
  console.log(`${failures} audit check(s) could not detect their own defect.`)
}
process.exit(failures === 0 ? 0 : 1)