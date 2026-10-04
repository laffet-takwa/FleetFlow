import { chromium } from 'playwright'

const BASE = 'http://localhost:18088'
const DESKTOP = { width: 1440, height: 900 }

const browser = await chromium.launch()
const context = await browser.newContext({ viewport: DESKTOP, deviceScaleFactor: 2 })
const page = await context.newPage()

await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded' })
await page.getByLabel(/email/i).first().fill('operations@fleetflow.local')
await page.getByLabel(/password/i).first().fill('Password123!')
await page.getByRole('button', { name: /^sign in$/i }).first().click()
await page.waitForURL((u) => !u.pathname.startsWith('/login'), { timeout: 20000 })
await page.waitForLoadState('networkidle')
console.log('signed in ->', page.url())

const pages = [
  ['dashboard', '/admin', 'h1'],
  ['orders', '/admin/orders', 'table'],
  ['order detail 31', '/admin/orders/31', 'h1'],
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
  const started = Date.now()
  try {
    await page.goto(`${BASE}${path}`, { waitUntil: 'domcontentloaded' })
    await page.waitForSelector(waitFor, { timeout: 20000 })
    await page.waitForLoadState('networkidle').catch(() => {})
    await page.waitForTimeout(1200)
    const where = new URL(page.url()).pathname
    const h1 = await page.locator('h1').allTextContents()
    console.log(`OK   ${path} (${Date.now() - started}ms) landed=${where} h1=${JSON.stringify(h1)}`)
  } catch (error) {
    console.log(`FAIL ${path} (${Date.now() - started}ms) -> ${error.message.split('\n')[0]}`)
    console.log(`     landed=${page.url()} body=${(await page.locator('body').innerText().catch(() => '')).slice(0, 200).replace(/\n/g, ' | ')}`)
  }
}

await browser.close()