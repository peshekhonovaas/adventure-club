import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  build: {
    // Emit the production build straight into Spring Boot's static folder so the
    // packaged jar serves the React app at "/". `emptyOutDir` is required because
    // the target lives outside this project root.
    outDir: '../src/main/resources/static',
    emptyOutDir: true,
  },
  server: {
    // `npm run dev` serves on :5173; proxy the API calls to the Spring Boot
    // backend on :8080 so relative fetches ("/session/...", "/auth/...") work in
    // dev too — including the session cookie set by the auth endpoints.
    proxy: {
      '/session': 'http://localhost:8080',
      '/auth': 'http://localhost:8080',
    },
  },
})