import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    // 监听局域网：手机扫同一 Wi-Fi 打开 http://<电脑IP>:5173 就能现场拍成分表
    host: true,
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8082',
        changeOrigin: true,
        // 浏览器对 PUT 会带 Origin，后端没开 CORS 会直接 403；
        // 前端与后端在本项目里始终同源（经 Vite 代理），这里去掉即可。
        configure: (proxy) => {
          proxy.on('proxyReq', (proxyReq) => {
            proxyReq.removeHeader('origin')
            proxyReq.removeHeader('referer')
          })
        }
      }
    }
  }
})
