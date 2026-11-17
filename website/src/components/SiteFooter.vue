<script setup lang="ts">
const year = new Date().getFullYear()

const product = [
  { label: 'Features', href: '#features' },
  { label: 'Screenshots', href: '#screenshots' },
  { label: 'Download', href: '#download' },
  { label: 'FAQ', href: '#faq' },
]

const project = [
  { label: 'Source', href: 'https://github.com/yuuki-kagura/adfree' },
  { label: 'Releases', href: 'https://github.com/yuuki-kagura/adfree/releases' },
  { label: 'Issues', href: 'https://github.com/yuuki-kagura/adfree/issues' },
  { label: 'Changelog', href: 'https://github.com/yuuki-kagura/adfree/blob/main/CHANGELOG.md' },
  { label: 'Design notes', href: 'https://github.com/yuuki-kagura/adfree/blob/main/DESIGN.md' },
]
</script>

<template>
  <footer class="border-t border-default bg-elevated/20">
    <UContainer class="py-14">
      <div class="grid gap-10 md:grid-cols-[1.6fr_1fr_1fr]">
        <div>
          <div class="flex items-center gap-2.5">
            <img src="/favicon.svg" alt="" class="size-7" />
            <span class="text-lg font-semibold text-highlighted">AdFree</span>
          </div>
          <p class="mt-4 max-w-sm text-sm/6 text-muted">
            An on-device ad and tracker blocker built on a Rust core. Filters on your device —
            no backend, no API key, no telemetry.
          </p>
          <p class="mt-4 max-w-sm text-xs/5 text-dimmed">
            AdFree is an independent project. It is not affiliated with, endorsed by, or connected
            to AdGuard or any other blocker vendor.
          </p>
        </div>

        <div>
          <h3 class="text-sm font-semibold text-highlighted">Product</h3>
          <ul class="mt-4 space-y-2.5">
            <li v-for="link in product" :key="link.href">
              <a :href="link.href" class="text-sm text-muted transition hover:text-highlighted">
                {{ link.label }}
              </a>
            </li>
          </ul>
        </div>

        <div>
          <h3 class="text-sm font-semibold text-highlighted">Project</h3>
          <ul class="mt-4 space-y-2.5">
            <li v-for="link in project" :key="link.href">
              <a
                :href="link.href"
                target="_blank"
                rel="noreferrer"
                class="text-sm text-muted transition hover:text-highlighted"
              >
                {{ link.label }}
              </a>
            </li>
          </ul>
        </div>
      </div>

      <div
        class="mt-12 flex flex-col gap-3 border-t border-default pt-6 text-xs text-dimmed sm:flex-row sm:items-center sm:justify-between"
      >
        <p>© {{ year }} AdFree.</p>
        <p>Rust core · Compose UI · Nuxt UI</p>
      </div>
    </UContainer>
  </footer>
</template>
