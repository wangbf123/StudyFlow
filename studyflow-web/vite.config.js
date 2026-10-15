import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  return {
    plugins: [vue(), Components({ dts: false, resolvers: [ElementPlusResolver({ importStyle: 'css' })] })],
    server: {
      host: '0.0.0.0',
      port: 5173,
      proxy: { '/api': { target: env.STUDYFLOW_PROXY_TARGET || 'http://127.0.0.1:8080', changeOrigin: true } }
    },
    build: {
      rollupOptions: {
        output: { manualChunks: { vue: ['vue'], icons: ['lucide-vue-next'] } }
      }
    }
  }
})
