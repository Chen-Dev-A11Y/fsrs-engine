import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/pages/LoginPage.vue'),
      meta: { public: true, title: '登录' },
    },
    {
      path: '/',
      component: () => import('@/layouts/AppShell.vue'),
      children: [
        { path: '', redirect: '/decks' },
        { path: 'decks', name: 'decks', component: () => import('@/pages/DecksPage.vue'), meta: { title: '卡组与卡片' } },
        { path: 'study', name: 'study', component: () => import('@/pages/StudyPage.vue'), meta: { title: '复习' } },
        { path: 'stats', name: 'stats', component: () => import('@/pages/StatsPage.vue'), meta: { title: '统计' } },
        {
          path: 'scheduler',
          name: 'scheduler',
          component: () => import('@/pages/SchedulerPage.vue'),
          meta: { title: '调度参数' },
        },
        {
          path: 'compare',
          name: 'compare',
          component: () => import('@/pages/ComparePage.vue'),
          meta: { title: '参数对比' },
        },
        {
          path: 'cards/:id',
          name: 'card-detail',
          component: () => import('@/pages/CardDetailPage.vue'),
          meta: { title: '卡片详情' },
        },
      ],
    },
    { path: '/:pathMatch(.*)*', redirect: '/decks' },
  ],
})

router.beforeEach((to) => {
  const auth = useAuthStore()
  if (!to.meta.public && !auth.isAuthenticated) {
    return { path: '/login', query: to.fullPath === '/' ? {} : { redirect: to.fullPath } }
  }
  if (to.meta.public && auth.isAuthenticated && to.path === '/login') {
    return { path: '/decks' }
  }
  return true
})

router.afterEach((to) => {
  const title = (to.meta.title as string | undefined) ?? 'FSRS Engine'
  document.title = `${title} · FSRS Engine`
})

export default router
