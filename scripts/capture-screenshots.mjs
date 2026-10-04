// Captures a screenshot of every page in the application.
//
//   cd scripts && npm install && npx playwright install chromium
//   node capture-screenshots.mjs
//
// The stack must already be running (docker compose up --build). Every page is
// reached through the real UI - the script signs in through the login form rather
// than injecting a token - so a broken route or a guard that rejects the wrong role
// fails here instead of producing a misleading picture of a blank page.
//
// Output format is PNG by default; JPEG is for when the shots are going somewhere
// that cannot carry a lossless PNG:
//
//   SHOT_FORMAT=jpeg SHOT_QUALITY=88 SHOTS_DIR=../docs/screenshots-jpg \
//     node capture-screenshots.mjs

import { chromium } from 'playwright'
import { mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'

const BASE_URL = process.env.SPA_URL ?? 'http://localhost:18088'
const API_URL = process.env.API_URL ?? 'http://localhost:18080'
const OUT = process.env.SHOTS_DIR ?? 'screenshots'

// `jpeg` is the name Chromium knows the format by; `jpg` is the name the file
// gets. Accept either spelling for the setting so the command line reads naturally.
const FORMAT = (() => {
  const requested = (process.env.SHOT_FORMAT ?? 'png').toLowerCase()
  if (requested === 'jpg' || requested === 'jpeg') return { type: 'jpeg', ext: 'jpg' }
  if (requested === 'png') return { type: 'png', ext: 'png' }
  throw new Error(`SHOT_FORMAT must be png or jpeg, got "${requested}"`)
})()
const QUALITY = Number(process.env.SHOT_QUALITY ?? 90)

const DESKTOP = { width: 1440, height: 900 }
const TABLET = { width: 1024, height: 800 }
const MOBILE = { width: 390, height: 844 }

const ACCOUNTS = {
  operations: 'operations@fleetflow.local',
  customer: 'customer1@fleetflow.local',
  driver: 'driver1@fleetflow.local',
}
const PASSWORD = 'Password123!'

// Animations are paused so a screenshot of a page mid-transition is not a coin toss.
const FREEZE_CSS = `
  *, *::before, *::after {
    animation-duration: 0s !important;
    animation-delay: 0s !important;
    transition-duration: 0s !important;
    transition-delay: 0s !important;
    caret-color: transparent !important;
  }
  .skeleton::after { animation: none !important; }
`

const captured = []
const failures = []

function slug(text) {
  return text
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-|-$/g, '')
}

async function api(path, { method = 'GET', token, body } = {}) {
  const send = () =>
    fetch(`${API_URL}/api${path}`, {
      method,
      // The services answer with a chunked response and close the keep-alive socket once
      // it goes idle. Node's fetch pools connections and will happily reuse one that the
      // server has just closed, which aborts the response after the body arrived
      // (UND_ERR_SOCKET) even though curl tolerates it. Asking for a fresh connection
      // per call removes the race; this is tooling, not production traffic.
      headers: {
        Connection: 'close',
        'Content-Type': 'application/json',
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      body: body ? JSON.stringify(body) : undefined,
    })

  for (let attempt = 0; attempt < 3; attempt += 1) {
    try {
      const response = await send()
      if (!response.ok) {
        throw new Error(`${method} ${path} -> ${response.status}`)
      }
      return response.status === 204 ? null : await response.json()
    } catch (error) {
      if (attempt === 2) throw error
      await new Promise((resolve) => setTimeout(resolve, 500 * (attempt + 1)))
    }
  }
  return null
}

async function login(email) {
  const result = await api('/auth/login', {
    method: 'POST',
    body: { email, password: PASSWORD },
  })
  return result.accessToken
}

/** Signs in through the actual login form, so the SPA's own session code is exercised. */
async function signInThroughUi(page, email) {
  await page.goto(`${BASE_URL}/login`, { waitUntil: 'domcontentloaded' })
  await page.getByLabel(/email/i).first().fill(email)
  await page.getByLabel(/password/i).first().fill(PASSWORD)
  await page.getByRole('button', { name: /^sign in$/i }).first().click()
  await page.waitForURL((url) => !url.pathname.startsWith('/login'), { timeout: 20_000 })
  await page.waitForLoadState('networkidle')
}

async function shoot(page, folder, name, { path, fullPage = true, theme, viewport, waitFor } = {}) {
  const target = join(OUT, folder, `${slug(name)}.${FORMAT.ext}`)
  mkdirSync(join(OUT, folder), { recursive: true })

  if (viewport) {
    await page.setViewportSize(viewport)
  }
  if (path) {
    await page.goto(`${BASE_URL}${path}`, { waitUntil: 'domcontentloaded' })
  }
  if (theme) {
    await page.evaluate((value) => localStorage.setItem('fleetflow.theme', value), theme)
    await page.reload({ waitUntil: 'domcontentloaded' })
  }
  if (waitFor) {
    await page.waitForSelector(waitFor, { timeout: 20_000 }).catch(() => {
      throw new Error(`selector not found on ${path ?? 'current page'}: ${waitFor}`)
    })
  }

  // Let data land, and for maps let their tiles arrive, before the shutter.
  await page.waitForLoadState('networkidle').catch(() => {})
  await page.waitForTimeout(1_200)

  const where = new URL(page.url()).pathname
  if (/^\/(403|404)$/.test(where) && !path?.startsWith(where)) {
    // The guard redirected us somewhere unexpected; the picture would be misleading.
    throw new Error(`expected to stay on ${path}, landed on ${where}`)
  }

  await page.screenshot({
    path: target,
    fullPage,
    type: FORMAT.type,
    ...(FORMAT.type === 'jpeg' ? { quality: QUALITY } : {}),
  })
  captured.push({ folder, name, file: target.replace(/\\/g, '/'), url: where })
  console.log(`  captured ${folder}/${slug(name)}.${FORMAT.ext}`)
}

async function main() {
  rmSync(OUT, { recursive: true, force: true })
  mkdirSync(OUT, { recursive: true })

  // Resolve real ids so no screenshot shows an empty state that a demo would not.
  console.log('Resolving demo data from the API…')
  const operationsToken = await login(ACCOUNTS.operations)
  const orders = await api('/orders?page=0&size=5', { token: operationsToken })
  const orderId = orders.content?.[0]?.id ?? 1

  const deliveries = await api('/deliveries?page=0&size=50', { token: operationsToken })
  const deliveriesList = deliveries.content ?? []
  const openDelivery = deliveriesList.find((d) => !['DELIVERED', 'CANCELLED'].includes(d.status))

  let trackedDeliveryId = null
  try {
    const tracked = await api('/tracking/active', { token: operationsToken })
    trackedDeliveryId = tracked?.[0]?.deliveryId ?? null
  } catch {
    // No active tracking is a legitimate state; the page will show its empty state.
  }

  const driverToken = await login(ACCOUNTS.driver)
  const mine = await api('/deliveries/mine', { token: driverToken })
  const driverDelivery = (mine ?? [])[0]

  console.log(`  order #${orderId}, ${deliveriesList.length} deliveries, driver has ${(mine ?? []).length}\n`)

  const browser = await chromium.launch()

  // ------------------------------------------------------------- public pages
  console.log('Public pages')
  {
    const context = await browser.newContext({ viewport: DESKTOP, deviceScaleFactor: 2 })
    const page = await context.newPage()
    await page.addStyleTag({ content: FREEZE_CSS }).catch(() => {})

    try {
      await shoot(page, 'public', 'login', { path: '/login', waitFor: 'input[type="email"]' })
      await shoot(page, 'public', 'register', { path: '/register', waitFor: 'input[type="email"]' })
      await shoot(page, 'public', 'not found', { path: '/this-route-does-not-exist' })
    } catch (error) {
      failures.push(`public: ${error.message}`)
    }
    await context.close()
  }

  // -------------------------------------------------------- operations console
  console.log('\nOperations console')
  {
    const context = await browser.newContext({ viewport: DESKTOP, deviceScaleFactor: 2 })
    const page = await context.newPage()
    try {
      await signInThroughUi(page, ACCOUNTS.operations)

      const pages = [
        ['dashboard', '/admin', 'h1'],
        ['orders', '/admin/orders', 'table'],
        [`order detail ${orderId}`, `/admin/orders/${orderId}`, 'h1'],
        ['inventory', '/admin/inventory', 'h1'],
        ['warehouses', '/admin/warehouses', 'h1'],
        ['drivers', '/admin/drivers', 'h1'],
        ['vehicles', '/admin/vehicles', 'h1'],
        ['deliveries', '/admin/deliveries', 'h1'],
        ['live tracking map', '/admin/tracking', 'h1'],
        ['analytics', '/admin/analytics', 'h1'],
        ['settings', '/admin/settings', 'h1'],
      ]
      for (const [name, path, waitFor] of pages) {
        try {
          await shoot(page, 'operations', name, { path, waitFor })
        } catch (error) {
          failures.push(`operations ${path}: ${error.message}`)
        }
      }

      // A customer must be refused the console; the redirect target is the proof.
      const customerContext = await browser.newContext({ viewport: DESKTOP, deviceScaleFactor: 2 })
      const customerPage = await customerContext.newPage()
      try {
        await signInThroughUi(customerPage, ACCOUNTS.customer)
        await shoot(customerPage, 'public', 'access denied', { path: '/admin' })
      } catch (error) {
        failures.push(`forbidden: ${error.message}`)
      }
      await customerContext.close()
    } finally {
      await context.close()
    }
  }

  // ------------------------------------------------------------ customer app
  console.log('\nCustomer app')
  {
    const context = await browser.newContext({ viewport: DESKTOP, deviceScaleFactor: 2 })
    const page = await context.newPage()
    try {
      await signInThroughUi(page, ACCOUNTS.customer)
      const pages = [
        ['dashboard', '/customer', 'h1'],
        ['orders', '/customer/orders', 'h1'],
        [`order detail ${orderId}`, `/customer/orders/${orderId}`, 'h1'],
        ['checkout', '/customer/checkout', 'h1'],
        ['profile', '/customer/profile', 'h1'],
      ]
      for (const [name, path, waitFor] of pages) {
        try {
          await shoot(page, 'customer', name, { path, waitFor })
        } catch (error) {
          failures.push(`customer ${path}: ${error.message}`)
        }
      }

      if (trackedDeliveryId) {
        try {
          await shoot(page, 'customer', 'live tracking', {
            path: `/customer/tracking/${trackedDeliveryId}`,
            waitFor: '.leaflet-container',
          })
        } catch (error) {
          failures.push(`customer tracking: ${error.message}`)
        }
      }
    } finally {
      await context.close()
    }
  }

  // -------------------------------------------------------------- driver app
  console.log('\nDriver app')
  {
    const context = await browser.newContext({ viewport: MOBILE, deviceScaleFactor: 3, isMobile: true, hasTouch: true })
    const page = await context.newPage()
    try {
      await signInThroughUi(page, ACCOUNTS.driver)
      const pages = [
        ['dashboard', '/driver', 'h1'],
        ['deliveries', '/driver/deliveries', 'h1'],
        ['notifications', '/driver/notifications', 'h1'],
        ['profile', '/driver/profile', 'h1'],
      ]
      for (const [name, path, waitFor] of pages) {
        try {
          await shoot(page, 'driver', name, { path, waitFor })
        } catch (error) {
          failures.push(`driver ${path}: ${error.message}`)
        }
      }
      if (driverDelivery) {
        try {
          await shoot(page, 'driver', 'delivery detail', {
            path: `/driver/deliveries/${driverDelivery.id}`,
            waitFor: 'h1',
          })
        } catch (error) {
          failures.push(`driver delivery detail: ${error.message}`)
        }
      }
    } finally {
      await context.close()
    }
  }

  // ------------------------------------------------------- shared + variants
  console.log('\nShared and variants')
  {
    const context = await browser.newContext({ viewport: DESKTOP, deviceScaleFactor: 2 })
    const page = await context.newPage()
    try {
      await signInThroughUi(page, ACCOUNTS.customer)
      try {
        await shoot(page, 'shared', 'notifications', { path: '/notifications', waitFor: 'h1' })
      } catch (error) {
        failures.push(`notifications: ${error.message}`)
      }
    } finally {
      await context.close()
    }

    const dark = await browser.newContext({ viewport: DESKTOP, deviceScaleFactor: 2 })
    const darkPage = await dark.newPage()
    try {
      await darkPage.addInitScript(() => localStorage.setItem('fleetflow.theme', 'dark'))
      await signInThroughUi(darkPage, ACCOUNTS.operations)
      for (const [name, path] of [
        ['operations dashboard', '/admin'],
        ['deliveries', '/admin/deliveries'],
      ]) {
        try {
          await shoot(darkPage, 'dark', name, { path, theme: 'dark', waitFor: 'h1' })
        } catch (error) {
          failures.push(`dark ${path}: ${error.message}`)
        }
      }
    } finally {
      await dark.close()
    }

    const tablet = await browser.newContext({ viewport: TABLET, deviceScaleFactor: 2 })
    const tabletPage = await tablet.newPage()
    try {
      await signInThroughUi(tabletPage, ACCOUNTS.operations)
      try {
        await shoot(tabletPage, 'responsive', 'operations dashboard tablet', {
          path: '/admin',
          waitFor: 'h1',
        })
      } catch (error) {
        failures.push(`tablet dashboard: ${error.message}`)
      }
    } finally {
      await tablet.close()
    }

    const customerMobile = await browser.newContext({
      viewport: MOBILE,
      deviceScaleFactor: 3,
      isMobile: true,
      hasTouch: true,
    })
    const customerMobilePage = await customerMobile.newPage()
    try {
      await signInThroughUi(customerMobilePage, ACCOUNTS.customer)
      for (const [name, path] of [
        ['customer dashboard', '/customer'],
        ['customer orders', '/customer/orders'],
        ['checkout', '/customer/checkout'],
      ]) {
        try {
          await shoot(customerMobilePage, 'responsive', name, { path, waitFor: 'h1' })
        } catch (error) {
          failures.push(`customer mobile ${path}: ${error.message}`)
        }
      }
    } finally {
      await customerMobile.close()
    }
  }

  await browser.close()

  // ------------------------------------------------------------------ index
  const byFolder = new Map()
  for (const shot of captured) {
    if (!byFolder.has(shot.folder)) byFolder.set(shot.folder, [])
    byFolder.get(shot.folder).push(shot)
  }

  const FOLDER_TITLES = {
    public: 'Public',
    operations: 'Operations console',
    customer: 'Customer app',
    driver: 'Driver app',
    shared: 'Shared',
    dark: 'Dark mode',
    responsive: 'Responsive',
  }

  const lines = [
    '# FleetFlow screenshots',
    '',
    'Every screen in the application, captured from a running stack by',
    '`scripts/capture-screenshots.mjs`. Nothing here is a mock-up: each file is a',
    'real page loaded from the real API.',
    '',
    'Regenerate with:',
    '',
    '```bash',
    'docker compose up --build',
    'cd scripts && npm install && npx playwright install chromium',
    FORMAT.type === 'jpeg'
      ? `SHOT_FORMAT=jpeg SHOT_QUALITY=${QUALITY} node capture-screenshots.mjs`
      : 'node capture-screenshots.mjs',
    '```',
    '',
  ]

  for (const [folder, shots] of byFolder) {
    lines.push(`## ${FOLDER_TITLES[folder] ?? folder}`, '')
    for (const shot of shots) {
      const file = shot.file.replace(`${OUT}/`, '')
      lines.push(`### ${shot.name}`, '', `Route: \`${shot.url}\``, '', `![${shot.name}](${file})`, '')
    }
  }

  if (failures.length > 0) {
    lines.push('## Pages that could not be captured', '')
    for (const failure of failures) lines.push(`- ${failure}`)
    lines.push('')
  }

  writeFileSync(join(OUT, 'README.md'), lines.join('\n'))

  console.log(`\n${captured.length} screenshots written to ${OUT}/`)
  if (failures.length > 0) {
    console.log(`${failures.length} page(s) failed:`)
    for (const failure of failures) console.log(`  - ${failure}`)
    process.exitCode = 1
  } else {
    console.log('Every page captured.')
  }
}

main().catch((error) => {
  console.error(error)
  process.exit(1)
})