import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  base: './',
  plugins: [vue()],
  build: {
    target: 'chrome89',
    rollupOptions: {
      input: { hub: 'index.html', browser: 'browser.html' },
    },
  },
})
