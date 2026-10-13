import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// Vite 配置：路径别名 @ -> src，开发代理将 /admin-api 转发到后端（默认 48080）
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    host: true,
    port: 5180, // 独立端口，避免与本机其他前端项目（5173 等）冲突
    proxy: {
      // 后端接口代理，联调时打开后端即可使用真实数据
      '/admin-api': {
        target: 'http://127.0.0.1:48080',
        changeOrigin: true,
      },
    },
  },
})
