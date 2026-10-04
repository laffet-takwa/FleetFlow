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

for (const file of walk('backend', (f) => f.endsWith('Controller.java'))) {
  const source = readFileSync(file, 'utf8')
  const base = /@RequestMapping\(\s*(?:value\s*=\s*)?"([^"]*)"/.exec(source)?.[1] ?? ''
  // Accept both @GetMapping("/x") and @GetMapping(value = "/x", produces = ...).
  for (const match of source.matchAll(
    /@(Get|Post|Put|Patch|Delete)Mapping\(\s*(?:value\s*=\s*)?"([^"]*)"/g,
  )) {
    const path = (base + match[2]).replace(/\{[^}]+\}/g, '{p}')
    const key = `${match[1].toUpperCase()} ${path}`
    if (!seen.has(key)) seen.set(key, [])
    seen.get(key).push(relative('.', file).split('\\').join('/'))
  }
}

for (const [key, files] of seen) {
  if (files.length > 1) {
    note('ambiguous mapping', files.join(', '), `${key} is declared more than once; Spring will refuse to start`)
  }
}

// -------------------------------------------------- credentials in source

// A literal that looks like a credential. Both quoted and bare forms are matched:
// `password: "x"` and `DB_PASSWORD=x` are both realistic ways to commit one, and a
// check that only catches the quoted form is a check that quietly misses half the cases.
const SECRET_LITERAL =
  /(?:password|secret|token|api[-_]?key)[A-Za-z0-9_.-]*\s*[:=]\s*(?:"([^"\s]{8,})"|'([^'\s]{8,})'|([^\s"']{8,}))/gi
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
  for (const match of source.matchAll(SECRET_LITERAL)) {
    const value = match[1]
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