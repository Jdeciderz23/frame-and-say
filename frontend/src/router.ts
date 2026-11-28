import { createRouter, createWebHistory } from 'vue-router'
import HomeView from './views/HomeView.vue'
import HealthView from './views/HealthView.vue'
import NotFoundView from './views/NotFoundView.vue'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', component: HomeView, meta: { title: '照有商量 · 协同选片' } },
    { path: '/health', component: HealthView, meta: { title: '服务连接 · 照有商量' } },
    { path: '/events', component: () => import('./views/EventsView.vue'), meta: { title: '我的活动 · 照有商量' } },
    { path: '/album', component: () => import('./views/AlbumView.vue'), meta: { title: '活动相册 · 照有商量' } },
    { path: '/:pathMatch(.*)*', component: NotFoundView, meta: { title: '页面未找到 · 照有商量' } },
  ],
  scrollBehavior: () => ({ top: 0 }),
})

router.afterEach((to) => {
  document.title = String(to.meta.title)
})
