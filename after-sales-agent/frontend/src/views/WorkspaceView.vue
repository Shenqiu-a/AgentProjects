<script setup lang="ts">
import { computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import AppIcon from '../components/AppIcon.vue'
import BrandMark from '../components/BrandMark.vue'
import RequestFeedback from '../components/RequestFeedback.vue'
import { auth } from '../stores/auth'

const router = useRouter()
const roleLabel = computed(() => ({ ADMIN: '管理员', CUSTOMER: '客户' })[auth.state.user?.role ?? ''] ?? auth.state.user?.role)

async function verifySession(): Promise<void> {
  await auth.restore()
  if (!auth.isAuthenticated.value) await router.replace({ name: 'login' })
}

async function signOut(): Promise<void> {
  if (await auth.signOut()) await router.replace({ name: 'login' })
}

function onVisible(): void {
  if (document.visibilityState === 'visible') void verifySession()
}

onMounted(() => {
  window.addEventListener('focus', verifySession)
  document.addEventListener('visibilitychange', onVisible)
})
onUnmounted(() => {
  window.removeEventListener('focus', verifySession)
  document.removeEventListener('visibilitychange', onVisible)
})
</script>

<template>
  <div class="workspace-page">
    <header class="workspace-header">
      <BrandMark />
      <div class="workspace-header-actions"><span class="role-badge">{{ roleLabel }}</span><button class="secondary-button" :disabled="auth.state.busy" @click="signOut"><AppIcon name="logout" :size="17" />退出登录</button></div>
    </header>

    <main class="workspace-main">
      <div class="workspace-welcome"><span class="eyebrow">MY WORKSPACE</span><h1>你好，{{ auth.state.user?.username }}<span class="welcome-dot">。</span></h1><p>欢迎来到你的售后服务工作台。</p></div>
      <RequestFeedback :message="auth.state.error" :request-id="auth.state.requestId" />

      <section class="session-card" aria-labelledby="session-title" :aria-busy="auth.state.busy">
        <div class="session-card-heading"><div class="session-title"><span class="session-icon"><AppIcon name="shield" :size="24" /></span><div><h2 id="session-title">当前登录会话</h2><p>身份信息来自服务端验证</p></div></div><span class="session-status"><span class="status-dot" />{{ auth.state.busy ? '正在处理' : auth.state.error ? '等待重新核验' : '已登录' }}</span></div>
        <dl class="user-details"><div><dt>登录账号</dt><dd>{{ auth.state.user?.username }}</dd></div><div><dt>账号角色</dt><dd>{{ roleLabel }} <span class="role-code">{{ auth.state.user?.role }}</span></dd></div><div class="user-id"><dt>用户 ID</dt><dd>{{ auth.state.user?.id }}</dd></div></dl>
        <div class="session-card-footer"><span>刷新页面可恢复会话；会话到期后需要重新登录。</span><button class="text-button" :disabled="auth.state.busy" @click="verifySession"><AppIcon name="refresh" :size="16" />{{ auth.state.busy ? '正在核验…' : '重新核验会话' }}</button></div>
      </section>

      <section class="upcoming-section" aria-labelledby="upcoming-title">
        <div class="section-heading"><h2 id="upcoming-title">接下来，在这里处理售后</h2><span>功能规划</span></div>
        <div class="upcoming-grid">
          <article class="upcoming-card"><span class="feature-icon lavender"><AppIcon name="chat" :size="23" /></span><span class="planned-badge">待接入</span><h3>售后咨询</h3><p>描述问题，获取有政策依据的解答与处理建议。</p></article>
          <article class="upcoming-card"><span class="feature-icon mint"><AppIcon name="order" :size="23" /></span><span class="planned-badge">待接入</span><h3>订单与工单</h3><p>核对订单信息，查看售后申请和处理进展。</p></article>
          <article class="upcoming-card"><span class="feature-icon peach"><AppIcon name="book" :size="23" /></span><span class="planned-badge">待接入</span><h3>服务知识</h3><p>查阅商品使用指南、常见问题与售后政策。</p></article>
        </div>
        <p class="scope-note"><AppIcon name="info" :size="16" />当前已接入账号登录与会话管理，业务功能将在后续开发中开放。</p>
      </section>
    </main>
    <footer class="public-footer"><span>售后服务工作台</span><span>让每一次售后，都有清晰的下一步。</span></footer>
  </div>
</template>
