import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

/**
 * 构建目标不是「一个独立前端」，而是「Spring 静态资源目录里的一份产物」：
 *
 * - `base: '/admin/'`：页面挂在 /admin/ 下，资源 URL 必须带着这个前缀，否则浏览器会去根路径找
 *   `/assets/xxx.js` 而那里什么都没有（契约 admin-console.md §2）。
 * - `build.outDir` 直接指到 `oryxos-web/src/main/resources/static/admin`：构建完就是 classpath 资源，
 *   maven-resources-plugin 随后把它拷进 classes/、最终进 fat JAR。产物不入库（.gitignore）。
 * - dev 期把 `/api` 代理到本地 serve 的 8080；发布形态同源，没有代理这一层。
 */
export default defineConfig({
  plugins: [vue()],
  base: '/admin/',
  build: {
    outDir: '../resources/static/admin',
    // 每次构建先清空：不清的话上一版带内容哈希的 assets 会一直堆在目录里，越积越多且没人肯删
    emptyOutDir: true,
    assetsDir: 'assets'
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
