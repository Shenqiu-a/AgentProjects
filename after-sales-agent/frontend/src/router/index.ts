import { createRouter, createWebHistory } from 'vue-router'
import { auth } from '../stores/auth'
import LoginView from '../views/LoginView.vue'
import WorkspaceView from '../views/WorkspaceView.vue'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/workspace' },
    { path: '/login', name: 'login', component: LoginView },
    { path: '/workspace', name: 'workspace', component: WorkspaceView, meta: { requiresAuth: true } },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

router.beforeEach(async (to) => {
  await auth.initialize()
  if (to.meta.requiresAuth && !auth.isAuthenticated.value) return { name: 'login' }
  if (to.name === 'login' && auth.isAuthenticated.value) return { name: 'workspace' }
})

router.afterEach((to) => {
  document.title = `${to.name === 'login' ? '登录' : '我的工作台'} · 售后服务工作台`
})
