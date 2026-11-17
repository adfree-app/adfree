import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import ui from '@nuxt/ui/vite'

// Nuxt UI runs in a plain Vue app through its Vite plugin: components, the
// Tailwind layer and the runtime colors all come from here.
//
// `SITE_BASE` is what GitHub Pages needs for a project site (/adfree/); a
// custom domain or a local run leaves it at '/'.
export default defineConfig({
  base: process.env.SITE_BASE || '/',
  plugins: [
    vue(),
    ui({
      ui: {
        colors: { primary: 'green', neutral: 'slate' },
      },
      icon: {
        mode: 'svg',
        clientBundle: { scan: true },
      },
    }),
  ],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
})
