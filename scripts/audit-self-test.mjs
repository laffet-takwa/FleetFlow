// Proves the audits can fail. A guard that has never been observed to fire is not a
// guard, so each check is fed a known defect and must report it.
//
// Run from the repository root:  node scripts/audit-self-test.mjs

import { execFileSync } from 'node:child_process'
import { mkdtempSync, writeFileSync, readFileSync, cpSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

let failures = 0

function expectFailure(label, script, mutate) {
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
    } else {
      console.log(`  PASS  ${label}: detected and exited ${code}`)
    }
  } finally {
    rmSync(workspace, { recursive: true, force: true })
  }
}

console.log('Self-test for the repository audits\n')

expectFailure('duplicate top-level YAML key', 'audit-repo.mjs', (workspace) => {
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
})

expectFailure('de-indented YAML key', 'audit-repo.mjs', (workspace) => {
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
})

expectFailure('ambiguous request mapping', 'audit-repo.mjs', (workspace) => {
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
  const source = readFileSync(target, 'utf8')
  writeFileSync(
    target,
    `${source}\nclass DuplicateMapping { @GetMapping("/orders") void duplicate() {} }\n`,
  )
})

expectFailure('committed credential', 'audit-repo.mjs', (workspace) => {
  const target = join(workspace, 'docker-compose.yml')
  writeFileSync(
    target,
    `${readFileSync(target, 'utf8')}\n# DB_PASSWORD=a-real-looking-production-value\n`,
  )
})

expectFailure('SPA call with no endpoint', 'audit-api.mjs', (workspace) => {
  const target = join(workspace, 'frontend', 'src', 'services', 'productApi.ts')
  writeFileSync(
    target,
    `${readFileSync(target, 'utf8')}\nexport const injected = () => get('/products/does-not-exist')\n`,
  )
})

expectFailure('SPA call outside the gateway route table', 'audit-api.mjs', (workspace) => {
  const target = join(
    workspace,
    'frontend',
    'src',
    'services',
    'productApi.ts',
  )
  writeFileSync(
    target,
    `${readFileSync(target, 'utf8')}\nexport const injected = () => get('/inventories/9')\n`,
  )
})

console.log('')
if (failures === 0) {
  console.log('All audits detect a known defect.')
} else {
  console.log(`${failures} audit check(s) could not detect their own defect.`)
}
process.exit(failures === 0 ? 0 : 1)