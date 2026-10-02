/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Base path every API call is prefixed with. `/api` routes through the gateway. */
  readonly VITE_API_BASE_URL?: string
  readonly VITE_APP_NAME?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}