import { createRouter, createWebHistory } from 'vue-router'
import HomeView from './views/HomeView.vue'
import HealthView from './views/HealthView.vue'
import NotFoundView from './views/NotFoundView.vue'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', component: HomeView, meta: { title: '合照都满意 · 协同选片' } },
    { path: '/health', component: HealthView, meta: { title: '服务连接 · 合照都满意' } },
    { path: '/:pathMatch(.*)*', component: NotFoundView, meta: { title: '页面未找到 · 合照都满意' } },
  ],
  scrollBehavior: () => ({ top: 0 }),
})

router.afterEach((to) => {
  document.title = String(to.meta.title)
})
