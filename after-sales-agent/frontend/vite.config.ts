import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => {
  // BACKEND_URL 仅供开发服务器使用，不会打包到浏览器。
  const env = loadEnv(mode, process.cwd(), '')
  const proxy = {
    '/api': {
      target: env.BACKEND_URL || 'http://127.0.0.1:8080',
      changeOrigin: true,
      // 浏览器始终只与前端同源地址交换 Cookie。
      cookieDomainRewrite: '',
    },
  }
  return {
    plugins: [vue()],
    server: { port: 5173, strictPort: true, proxy },
    preview: { port: 4173, strictPort: true, proxy },
  }
})
