import { createApp } from 'vue'
import { createPinia } from 'pinia'

import App from './App.vue'
import router, { startNotificationStream } from './router'
import './styles/main.css'

const app = createApp(App)

app.use(createPinia())
app.use(router)

/**
 * The session is restored before the first navigation so the router guard can make an
 * informed decision: without this, a page reload would bounce an authenticated user to
 * the login screen before their token had been checked.
 */
router
  .isReady()
  .then(() => {
    app.mount('#app')
    startNotificationStream()
  })
  .catch((error) => {
    // A router failure must still leave the user with something renderable.
    console.error('FleetFlow failed to start', error)
  })