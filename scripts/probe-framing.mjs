// Reads one HTTP response straight off the socket and prints the framing verbatim, so a
// malformed chunked terminator is visible rather than inferred from a client error.

import net from 'node:net'

const [, , portArg, pathArg, tokenArg] = process.argv
const port = Number(portArg ?? '18080')
const path = pathArg ?? '/api/orders/1'
const token = tokenArg ?? ''

const socket = net.connect(port, '127.0.0.1', () => {
  socket.write(
    `GET ${path} HTTP/1.1\r\n` +
      `Host: 127.0.0.1:${port}\r\n` +
      `Authorization: Bearer ${token}\r\n` +
      `Accept: application/json\r\n` +
      `Connection: close\r\n` +
      `\r\n`,
  )
})

let buffer = Buffer.alloc(0)
socket.on('data', (chunk) => {
  buffer = Buffer.concat([buffer, chunk])
})
socket.on('close', () => {
  const text = buffer.toString('binary')
  const separator = text.indexOf('\r\n\r\n')
  const head = text.slice(0, separator === -1 ? text.length : separator)
  const body = separator === -1 ? '' : text.slice(separator + 4)

  console.log(`--- ${port}${path} ---`)
  console.log(head)
  console.log(`--- body: ${body.length} bytes ---`)
  console.log(`first 80: ${JSON.stringify(body.slice(0, 80))}`)
  console.log(`last  80: ${JSON.stringify(body.slice(-80))}`)
  console.log(`terminated by 0-chunk: ${/\r\n0\r\n\r\n$/.test(body)}`)
  console.log(`chunk headers present: ${/\r\n[0-9a-f]+\r\n/.test(body)}`)
  process.exit(0)
})
socket.on('error', (error) => {
  console.error(error)
  process.exit(1)
})
setTimeout(() => {
  console.error('timed out')
  process.exit(1)
}, 15000)