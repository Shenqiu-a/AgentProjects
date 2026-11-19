import { defineConfig, loadEnv, type ProxyOptions } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const direct = env.MICROSERVICES_DIRECT === 'true'
  const rule = (target: string) => ({
    target,
    changeOrigin: true,
    cookieDomainRewrite: '',
  })
  const proxy: Record<string, ProxyOptions> = direct
    ? {
        '/api/v1/auth': rule('http://127.0.0.1:8081'),
		'/api/v1/products': rule('http://127.0.0.1:8082'),
        '/api/v1/orders': rule('http://127.0.0.1:8082'),
        '/api/v1/knowledge': rule('http://127.0.0.1:8083'),
        '/api': rule('http://127.0.0.1:8084'),
      }
    : { '/api': rule(env.BACKEND_URL || 'http://127.0.0.1:8080') }
  return {
    plugins: [vue()],
    server: { port: 5173, strictPort: true, proxy },
    preview: { port: 4173, strictPort: true, proxy },
  }
})
