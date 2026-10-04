// Cross-checks the SPA's API calls against the endpoints the controllers actually
// expose. Run from the repository root:  node scripts/audit-api.mjs
//
// A mismatch here is a bug the type checker cannot catch, because the frontend
// types are hand maintained and nothing validates them against the Java side.

import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join, relative } from 'node:path'

const BACKEND = 'backend'
const FRONTEND = join('frontend', 'src')

/** Every .java file under backend, excluding build output. */
function walk(root, predicate) {
  const out = []
  for (const entry of readdirSync(root)) {
    if (entry === 'target' || entry === 'node_modules' || entry.startsWith('.')) continue
    const full = join(root, entry)
    if (statSync(full).isDirectory()) out.push(...walk(full, predicate))
    else if (predicate(full)) out.push(full)
  }
  return out
}

const javaFiles = walk(BACKEND, (f) => f.endsWith('Controller.java') || f.endsWith('Application.java'))
const tsFiles = walk(FRONTEND, (f) => f.endsWith('.ts') && !f.endsWith('.d.ts'))
const vueFiles = walk(FRONTEND, (f) => f.endsWith('.vue'))

/** Normalises a path so /orders/{id} and /orders/${id} compare equal. */
function normalise(path) {
  return path
    .replace(/\$\{[^}]+\}/g, '{id}')
    .replace(/\{[^}]+\}/g, '{id}')
    .replace(/\/+$/, '')
}

// ---------------------------------------------------------------- backend routes

const served = new Set()

for (const file of javaFiles) {
  const source = readFileSync(file, 'utf8')
  const base = /@RequestMapping\(\s*(?:value\s*=\s*)?"([^"]*)"/.exec(source)?.[1] ?? ''

  for (const match of source.matchAll(
    /@(Get|Post|Put|Patch|Delete)Mapping\(\s*(?:value\s*=\s*)?(?:"([^"]*)"|\{([^}]*)\})/g,
  )) {
    const verb = match[1].toUpperCase()
    const literals = [
      ...(match[2] ? [match[2]] : []),
      ...(match[3] ? [...match[3].matchAll(/"([^"]*)"/g)].map((m) => m[1]) : []),
    ]
    for (const literal of literals) {
      served.add(`${verb} ${normalise(base + literal)}`)
    }
  }
}

// ---------------------------------------------------------------- frontend calls

const candidates = new Map()

const VERBS = { del: 'DELETE', get: 'GET', post: 'POST', put: 'PUT', patch: 'PATCH' }

function record(method, rawPath, where) {
  if (!rawPath || !rawPath.startsWith('/')) return
  // The SPA helper is named `del`, not `delete`.
  const verb = VERBS[method.toLowerCase()] ?? method.toUpperCase()
  // Every SPA call is written relative to the gateway's /api base.
  const path = normalise(`/api${rawPath}`)
  const key = `${verb} ${path}`
  if (!candidates.has(key)) candidates.set(key, [])
  candidates.get(key).push(where)
}

for (const file of [...tsFiles, ...vueFiles]) {
  const source = readFileSync(file, 'utf8')
  const where = relative('.', file)

  for (const match of source.matchAll(/\b(get|post|put|patch|del)<[^>]*>?\(\s*`([^`]+)`/g)) {
    record(match[1].toUpperCase(), match[2], where)
  }
  for (const match of source.matchAll(/\b(get|post|put|patch|del)\(\s*`([^`]+)`/g)) {
    record(match[1].toUpperCase(), match[2], where)
  }
  for (const match of source.matchAll(/\b(get|post|put|patch|del)\(\s*'([^']+)'/g)) {
    record(match[1].toUpperCase(), match[2], where)
  }
}

// -------------------------------------------------------------- gateway routing

// The gateway's route table lives in its own application.yml, not in compose.
// A single rule can carry several comma separated prefixes, as the warehouse and
// delivery routes do.
const gatewayConfig = readFileSync(join(BACKEND, 'api-gateway', 'src', 'main', 'resources', 'application.yml'), 'utf8')
const gatewayRoutes = [...gatewayConfig.matchAll(/Path=(\/[^\n]+)/g)].flatMap((match) =>
  match[1].split(',').map((prefix) => prefix.trim()),
)

// ------------------------------------------------------------------------ report

console.log(`served endpoints (public): ${served.size}`)
console.log(`distinct calls in the SPA: ${candidates.size}`)
console.log(`gateway path prefixes: ${gatewayRoutes.length}`)
console.log('')

// Both sides were normalised to {id}, so an exact comparison is the correct test:
// the path parameter names are irrelevant and every other segment is a literal.
const unrouted = []
const missing = []

for (const [call, where] of candidates) {
  const path = call.slice(call.indexOf(' ') + 1)
  if (!gatewayRoutes.some((prefix) => path.startsWith(prefix.replace('/**', '')))) {
    unrouted.push(`${call}  <- ${[...new Set(where)].join(', ')}`)
  }
  if (!served.has(call)) {
    missing.push(`${call}  <- ${[...new Set(where)].join(', ')}`)
  }
}

if (missing.length === 0) {
  console.log('PASS  every SPA API call matches an exposed endpoint')
} else {
  console.log(`FAIL  ${missing.length} SPA call(s) with no matching endpoint:`)
  for (const line of missing) console.log(`  ${line}`)
}

if (unrouted.length > 0) {
  console.log('')
  console.log(`WARN  ${unrouted.length} SPA call(s) not covered by a gateway route prefix:`)
  for (const line of unrouted) console.log(`  ${line}`)
}

process.exit(missing.length === 0 && unrouted.length === 0 ? 0 : 1)