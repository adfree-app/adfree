<script setup lang="ts">
import { ref } from 'vue'
const open = ref(false)

const links = [
  { label: 'Features', href: '#features' },
  { label: 'How it works', href: '#how' },
  { label: 'Screenshots', href: '#screenshots' },
  { label: 'Platforms', href: '#platforms' },
  { label: 'FAQ', href: '#faq' },
]
</script>

<template>
  <header class="sticky top-0 z-50 border-b border-default bg-default/80 backdrop-blur-xl">
    <UContainer>
      <div class="flex h-16 items-center justify-between gap-4">
        <a href="#top" class="flex items-center gap-2.5">
          <img src="/favicon.svg" alt="" class="size-7" />
          <span class="text-lg font-semibold tracking-tight text-highlighted">AdFree</span>
        </a>

        <nav class="hidden items-center gap-1 md:flex">
          <a
            v-for="link in links"
            :key="link.href"
            :href="link.href"
            class="rounded-lg px-3 py-2 text-sm text-muted transition hover:bg-elevated/70 hover:text-highlighted"
          >
            {{ link.label }}
          </a>
        </nav>

        <div class="flex items-center gap-1.5">
          <UButton
            icon="i-simple-icons-github"
            color="neutral"
            variant="ghost"
            to="https://github.com/yuuki-kagura/adfree"
            target="_blank"
            aria-label="AdFree on GitHub"
          />
          <UButton
            label="Download"
            trailing-icon="i-lucide-download"
            to="#download"
            class="hidden sm:inline-flex"
          />
          <UButton
            :icon="open ? 'i-lucide-x' : 'i-lucide-menu'"
            color="neutral"
            variant="ghost"
            class="md:hidden"
            :aria-label="open ? 'Close menu' : 'Open menu'"
            @click="open = !open"
          />
        </div>
      </div>
    </UContainer>

    <div v-if="open" class="border-t border-default md:hidden">
      <UContainer>
        <nav class="flex flex-col py-3">
          <a
            v-for="link in links"
            :key="link.href"
            :href="link.href"
            class="rounded-lg px-3 py-2.5 text-sm text-muted hover:bg-elevated/70 hover:text-highlighted"
            @click="open = false"
          >
            {{ link.label }}
          </a>
        </nav>
      </UContainer>
    </div>
  </header>
</template>
