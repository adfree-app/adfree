<script setup lang="ts">
defineProps<{
  src: string
  alt: string
}>()
</script>

<template>
  <div
    class="relative mx-auto w-full max-w-[19rem] rounded-[2.6rem] border border-white/15 bg-black p-2 shadow-[0_40px_120px_-30px_rgba(0,0,0,0.9)] ring-1 ring-white/5"
  >
    <div class="absolute left-1/2 top-2 z-10 h-5 w-24 -translate-x-1/2 rounded-b-2xl bg-black" />
    <img
      :src="src"
      :alt="alt"
      loading="lazy"
      class="block w-full rounded-[2.1rem]"
    />
  </div>
</template>
