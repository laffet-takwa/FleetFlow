// Cross-cutting checks that belong to the repository rather than to any one
// service: ambiguous request mappings, credentials committed to source, and
// configuration that contradicts itself.
//
// Run from the repository root:  node scripts/audit-repo.mjs

import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join, relative } from 'node:path'

function walk(root, predicate) {
  const out = []
  for (const entry of readdirSync(root)) {
    if (entry === 'target' || entry === 'node_modules' || entry === 'dist' || entry.startsWith('.')) continue
    const full = join(root, entry)
    if (statSync(full).isDirectory()) out.push(...walk(full, predicate))
    else if (predicate(full)) out.push(full)
  }
  return out
}

const problems = []
const note = (kind, where, detail) => problems.push({ kind, where, detail })

const javaFiles = walk('backend', (f) => f.endsWith('.java'))
const yamlFiles = walk('backend', (f) => f.endsWith('.yml'))
const composeFiles = ['docker-compose.yml', '.env.example'].filter((f) => {
  try {
    readFileSync(f)
    return true
  } catch {
    return false
  }
})

// ------------------------------------------------- ambiguous request mappings

const seen = new Map()

// @GetMapping("/x"), @GetMapping(value = "/x", produces = ...), @GetMapping({"/x","/y"})
// and the bare @GetMapping, which maps the class-level path itself.
const MAPPING_WITH_ARGS =
  /@(Get|Post|Put|Patch|Delete)Mapping\(\s*(?:value\s*=\s*)?(?:"([^"]*)"|\{([^}]*)\})[^)]*\)/g
const MAPPING_BARE = /@(Get|Post|Put|Patch|Delete)Mapping(?!\s*\()/g
const CLASS_MARKER = /@RestController\b/g

/** Every mapping declared in one controller segment, as { verb, path }. */
function mappingsOf(segment) {
  const found = []
  for (const match of segment.matchAll(MAPPING_WITH_ARGS)) {
    const literals = [
      ...(match[2] ? [match[2]] : []),
      ...(match[3] ? [...match[3].matchAll(/"([^"]*)"/g)].map((m) => m[1]) : []),
    ]
    for (const literal of literals) found.push({ verb: match[1], path: literal })
  }
  // A bare annotation carries no parentheses, so it cannot collide with the form above.
  for (const match of segment.matchAll(MAPPING_BARE)) {
    found.push({ verb: match[1], path: '' })
  }
  return found
}

for (const file of walk('backend', (f) => f.endsWith('.java'))) {
  const source = readFileSync(file, 'utf8')
  if (!source.includes('@RestController')) continue
  const where = relative('.', file).split('\\').join('/')

  // One file can hold more than one controller, and each has its own base path. Taking
  // the first @RequestMapping in the file would mis-attribute every mapping after the
  // first class, which hides exactly the ambiguity this check exists to find.
  const markers = [...source.matchAll(CLASS_MARKER)].map((match) => match.index)

  markers.forEach((start, position) => {
    const end = position + 1 < markers.length ? markers[position + 1] : source.length
    const segment = source.slice(start, end)

    // The class-level base path is declared immediately after @RestController, inside
    // this segment - searching before the marker would miss it and collapse every
    // mapping to "/{p}", which reads as a false ambiguity between unrelated controllers.
    const bases = [...segment.matchAll(/@RequestMapping\(\s*(?:value\s*=\s*)?"([^"]*)"/g)]
    const base = bases.length > 0 ? bases[0][1] : ''

for (const { verb, path: literal } of mappingsOf(segment)) {
      const path = (base + literal).replace(/\{[^}]+\}/g, '{p}')
      // Scoped to the module: two services may both expose GET /me, because each runs
      // in its own application context. Only a clash inside one module is ambiguous.
      const module = file.split(/[\\/]/).slice(0, 2).join('/')
      const key = `${module} ${verb.toUpperCase()} ${path}`
      if (!seen.has(key)) seen.set(key, [])
      seen.get(key).push(where)
    }
  })
}

for (const [key, files] of seen) {
  if (files.length > 1) {
    note('ambiguous mapping', files.join(', '), `${key} is declared more than once; Spring will refuse to start`)
  }
}

// -------------------------------------------------- credentials in source

// A literal that looks like a credential.
//
// In Java a secret is always a string literal, so only the quoted forms are matched -
// allowing a bare form there would flag every `passwordEncoder.encode(...)` call.
// In YAML and .env files the bare `KEY=value` form is the real syntax, so it is matched
// too: catching half the ways a secret can be committed is worse than not checking.
const SECRET_QUOTED =
  /(?:password|secret|token|api[-_]?key)[A-Za-z0-9_.-]*\s*[:=]\s*(?:"([^"\s]{8,})"|'([^'\s]{8,})')/gi
const SECRET_BARE =
  /(?:password|secret|token|api[-_]?key)[A-Za-z0-9_.-]*\s*[:=]\s*([^\s"',#][^\s"']{7,})/gi
// Development defaults that exist on purpose and are documented as such.
const ALLOWED = new Set([
  'fleetflow-local-development-secret-key-change-me-0123456789',
  'fleetflow-local-internal-token',
  'fleetflow',
  'Password123!',
])

for (const file of [...javaFiles, ...yamlFiles, ...composeFiles]) {
  // Normalise separators: relative() yields backslashes on Windows, and a guard that
  // silently fails to match is worse than no guard at all.
  const where = relative('.', file).split('\\').join('/')
  // A fixture secret in a test is not a committed credential, and the whole point of
  // this check is that it stays quiet on the code it is meant to protect.
  if (/(^|\/)src\/test\//.test(where)) continue

  const source = readFileSync(file, 'utf8')
  const isConfig = /\.(yml|yaml|env)$/.test(file) || file === '.env' || file === '.env.example'
  const patterns = isConfig ? [SECRET_QUOTED, SECRET_BARE] : [SECRET_QUOTED]

  for (const pattern of patterns) {
    for (const match of source.matchAll(pattern)) {
      // Each pattern has one group per quoting style; exactly one is populated.
      const value = match[1] ?? match[2] ?? match[3]
      if (!value) continue
      if (ALLOWED.has(value)) continue
      if (/^\$\{/.test(value)) continue // a property placeholder, not a literal
      if (value.startsWith('REPLACE_ME')) continue // documented placeholder
      note(
        'possible credential',
        where,
        `"${value.slice(0, 12)}…" looks like a committed secret`,
      )
    }
  }
}

// -------------------------------------------------- configuration sanity

for (const file of yamlFiles) {
  const source = readFileSync(file, 'utf8')
  const where = relative('.', file)

  // Spring Boot rejects a duplicate top level key outright, which is a boot failure
  // rather than a style problem - and four of these files were merged by hand.
  const topLevel = [...source.matchAll(/^([A-Za-z][\w.-]*):/gm)].map((match) => match[1])
  const duplicates = topLevel.filter((key, index) => topLevel.indexOf(key) !== index)
  if (duplicates.length > 0) {
    note('duplicate YAML key', where, `top level key(s) ${[...new Set(duplicates)].join(', ')} appear twice`)
  }

  // A de-indented child key parses as YAML but silently changes the structure.
  for (const [index, line] of source.split('\n').entries()) {
    if (/^[A-Za-z][\w.-]*:\s*\S/.test(line) && !/^[A-Za-z][\w.-]*:\s*$/.test(line)) {
      const previous = source.split('\n')[index - 1] ?? ''
      if (/^\s+level:\s*$/.test(previous) || /^\s+\w+:\s*$/.test(previous)) {
        note('de-indented YAML key', where, `line ${index + 1}: ${line.trim()}`)
      }
    }
  }
}

// --------------------------------------------------------------------- report

if (problems.length === 0) {
  console.log('PASS  no ambiguous mappings, committed credentials or configuration defects')
  process.exit(0)
}

console.log(`${problems.length} problem(s) found:\n`)
for (const problem of problems) {
  console.log(`  [${problem.kind}] ${problem.where}\n      ${problem.detail}`)
}
process.exit(1)