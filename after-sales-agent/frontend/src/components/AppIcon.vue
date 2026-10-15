<script setup lang="ts">
defineProps<{ name: string; size?: number }>()
const paths: Record<string, string> = {
  headset: 'M4 13v-2a8 8 0 0 1 16 0v2M4 12H3a1 1 0 0 0-1 1v5a1 1 0 0 0 1 1h3v-7H4Zm16 0h1a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1h-3v-7h2Zm0 7c0 3-4 3-7 3',
  arrow: 'M4 12h16m-6-6 6 6-6 6',
  user: 'M20 21v-2a7 7 0 0 0-14 0v2M17 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0Z',
  lock: 'M6 10V7a6 6 0 0 1 12 0v3M5 10h14v12H5V10Zm7 5v3',
  eye: 'M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12Zm13 0a3 3 0 1 1-6 0 3 3 0 0 1 6 0Z',
  eyeOff: 'm3 3 18 18M10 5c7-2 12 7 12 7a20 20 0 0 1-4 4M6 6a20 20 0 0 0-4 6s4 7 10 7a12 12 0 0 0 5-1M10 10a3 3 0 0 0 4 4',
  shield: 'M12 2 3 6v6c0 5 9 10 9 10s9-5 9-10V6l-9-4Zm-4 10 3 3 5-6',
  check: 'm5 12 4 4L19 6',
  info: 'M12 11v6m0-10v.1M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0Z',
  logout: 'M9 21H4V3h5m6 14 5-5-5-5M9 12h12',
  refresh: 'M21 4v6h-6M3 20v-6h6M5 7a8 8 0 0 1 13-2l3 5M3 14l3 5a8 8 0 0 0 13-2',
  chat: 'M21 11a9 9 0 0 1-9 9H3l1.7-4A9 9 0 1 1 21 11ZM8 10h8M8 14h5',
  order: 'M5 3h14v19l-3-2-4 2-4-2-3 2V3Zm4 5h6m-6 5h6',
  book: 'M3 3h7l2 2 2-2h7v17h-7l-2 2-2-2H3V3Zm9 2v17',
  spark: 'm12 2 3 7 7 3-7 3-3 7-3-7-7-3 7-3 3-7Z',
}
</script>

<template>
  <svg :width="size ?? 20" :height="size ?? 20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
    <path :d="paths[name] ?? paths.info" />
  </svg>
</template>
