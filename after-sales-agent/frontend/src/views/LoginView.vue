<script setup lang="ts">
import { nextTick, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import AppIcon from '../components/AppIcon.vue'
import BrandMark from '../components/BrandMark.vue'
import RequestFeedback from '../components/RequestFeedback.vue'
import { validateCredentials } from '../features/auth/validation'
import { auth } from '../stores/auth'

const router = useRouter()
const username = ref('')
const password = ref('')
const visible = ref(false)
const capsLock = ref(false)
const errors = reactive<{ username?: string; password?: string }>({})
const usernameInput = ref<HTMLInputElement>()
const passwordInput = ref<HTMLInputElement>()
const isDevelopment = import.meta.env.DEV

function validateField(field: 'username' | 'password'): void {
  errors[field] = validateCredentials({ username: username.value, password: password.value })[field]
}

async function submit(): Promise<void> {
  if (auth.state.busy) return
  Object.assign(errors, { username: undefined, password: undefined }, validateCredentials({ username: username.value, password: password.value }))
  if (errors.username || errors.password) {
    await nextTick()
    ;(errors.username ? usernameInput : passwordInput).value?.focus()
    return
  }
  const success = await auth.signIn({ username: username.value.trim(), password: password.value })
  password.value = ''
  visible.value = false
  if (success) await router.replace({ name: 'workspace' })
  else passwordInput.value?.focus()
}

async function retrySession(): Promise<void> {
  await auth.restore()
  if (auth.isAuthenticated.value) await router.replace({ name: 'workspace' })
}
</script>

<template>
  <div class="login-page">
    <header class="public-header">
      <BrandMark />
      <span class="environment-badge"><span class="status-dot" />{{ isDevelopment ? '本地开发环境' : '账号登录' }}</span>
    </header>

    <main class="login-layout">
      <section class="intro-panel" aria-labelledby="intro-title">
        <div class="eyebrow"><span />更有条理的售后体验</div>
        <h1 id="intro-title">每一个问题，<br />都有清晰的<span class="accent-text">下一步。</span></h1>
        <p class="intro-description">从问题咨询到售后处理，让信息有据可查，<br class="desktop-break" />让服务进展一目了然。</p>

        <div class="journey-illustration" aria-label="规划中的售后服务流程：描述问题、核对信息、确认售后方案">
          <div class="journey-toolbar"><span class="mini-dots"><i /><i /><i /></span><span>服务流程 · 规划预览</span><AppIcon name="spark" :size="15" /></div>
          <div class="journey-line" />
          <div class="journey-step step-one">
            <span class="journey-icon lavender"><AppIcon name="chat" :size="21" /></span>
            <div><strong>先说说遇到的问题</strong><span>描述商品情况与服务需求</span></div>
            <span class="step-number">01</span>
          </div>
          <div class="journey-step step-two">
            <span class="journey-icon mint"><AppIcon name="order" :size="21" /></span>
            <div><strong>把关键信息核对清楚</strong><span>关联订单，查阅适用政策</span></div>
            <span class="step-number">02</span>
          </div>
          <div class="journey-step step-three">
            <span class="journey-icon peach"><AppIcon name="check" :size="21" /></span>
            <div><strong>由你确认下一步</strong><span>确认方案后，再提交售后申请</span></div>
            <span class="step-number">03</span>
          </div>
          <span class="journey-caption">服务能力将随项目开发逐步接入</span>
        </div>

        <div class="intro-assurance"><AppIcon name="shield" :size="18" /><span>身份由服务端校验，关键操作由你确认</span></div>
      </section>

      <section class="login-card" aria-labelledby="login-title">
        <div class="card-heading-icon"><AppIcon name="headset" :size="26" /></div>
        <span class="card-eyebrow">WELCOME BACK</span>
        <h2 id="login-title">欢迎回来</h2>
        <p class="card-description">登录你的账号，进入售后服务工作台。</p>

        <div class="form-feedback" aria-live="polite">
          <RequestFeedback :message="auth.state.notice" tone="success" />
          <RequestFeedback :message="auth.state.error" :request-id="auth.state.requestId" />
          <button v-if="auth.state.error" class="text-button retry-session" type="button" :disabled="auth.state.busy" @click="retrySession">重新检查登录状态</button>
        </div>

        <form novalidate :aria-busy="auth.state.busy" @submit.prevent="submit">
          <div class="form-field">
            <label for="username">账号</label>
            <div class="input-wrap" :class="{ 'input-invalid': errors.username }">
              <AppIcon name="user" :size="19" />
              <input id="username" ref="usernameInput" v-model="username" name="username" type="text" placeholder="请输入账号" autocomplete="username" autocapitalize="none" :spellcheck="false" maxlength="64" :disabled="auth.state.busy" :aria-invalid="!!errors.username" :aria-describedby="errors.username ? 'username-error' : undefined" required @blur="validateField('username')" @input="errors.username = undefined; auth.clearError()" />
            </div>
            <p v-if="errors.username" id="username-error" class="field-error">{{ errors.username }}</p>
          </div>

          <div class="form-field">
            <label for="password">密码</label>
            <div class="input-wrap" :class="{ 'input-invalid': errors.password }">
              <AppIcon name="lock" :size="18" />
              <input id="password" ref="passwordInput" v-model="password" name="password" :type="visible ? 'text' : 'password'" placeholder="请输入密码" autocomplete="current-password" maxlength="128" :disabled="auth.state.busy" :aria-invalid="!!errors.password" :aria-describedby="errors.password ? 'password-error' : capsLock ? 'caps-warning' : undefined" required @blur="validateField('password'); capsLock = false" @input="errors.password = undefined; auth.clearError()" @keydown="capsLock = $event.getModifierState('CapsLock')" @keyup="capsLock = $event.getModifierState('CapsLock')" />
              <button class="icon-button password-toggle" type="button" :aria-label="visible ? '隐藏密码' : '显示密码'" :aria-pressed="visible" :disabled="auth.state.busy" @click="visible = !visible"><AppIcon :name="visible ? 'eyeOff' : 'eye'" :size="19" /></button>
            </div>
            <p v-if="errors.password" id="password-error" class="field-error">{{ errors.password }}</p>
            <p v-else-if="capsLock" id="caps-warning" class="field-hint">大写锁定已开启</p>
          </div>

          <div class="account-note"><AppIcon name="info" :size="15" /><span>请使用管理员分配的账号登录</span></div>
          <button class="primary-button login-submit" type="submit" :disabled="auth.state.busy">
            <span v-if="auth.state.busy" class="spinner" />
            <span>{{ auth.state.busy ? '正在验证身份…' : '登录工作台' }}</span>
            <AppIcon v-if="!auth.state.busy" name="arrow" :size="18" />
          </button>
        </form>

        <div class="login-card-footer"><AppIcon name="shield" :size="15" /><span>安全会话保护 · 退出后需重新登录</span></div>
        <p class="access-help">无法登录？请联系管理员核实账号或重置密码。</p>
      </section>
    </main>

    <footer class="public-footer"><span>售后服务工作台</span><span>把复杂留给流程，把清晰留给你。</span></footer>
  </div>
</template>
