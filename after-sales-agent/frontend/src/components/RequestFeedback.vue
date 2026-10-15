<script setup lang="ts">
import AppIcon from './AppIcon.vue'
defineProps<{ message: string; requestId?: string; tone?: 'error' | 'success' }>()
</script>

<template>
  <div v-if="message" class="feedback" :class="tone === 'success' ? 'feedback-success' : 'feedback-error'" :role="tone === 'success' ? 'status' : 'alert'">
    <AppIcon :name="tone === 'success' ? 'check' : 'info'" :size="18" />
    <div>{{ message }}<small v-if="requestId">请求编号：{{ requestId }}</small></div>
  </div>
</template>
