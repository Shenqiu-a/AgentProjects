<script setup lang="ts">
import { useRoute } from 'vue-router'
import BrandMark from './components/BrandMark.vue'
const route = useRoute()
</script>

<template>
  <div v-if="!route.matched.length" class="app-loading" role="status">
    <BrandMark />
    <p>正在检查登录状态…</p>
  </div>
  <RouterView v-else />
</template>
