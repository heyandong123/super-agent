import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  {
    path: '/',
    name: 'Home',
    component: () => import('../views/Home.vue'),
    meta: {
      title: '首页 - 恋爱大师智能体应用平台',
      description: '恋爱大师智能体应用平台提供AI恋爱大师和AI超级智能体服务，满足您的各种AI对话需求'
    }
  },
  {
    path: '/love-master',
    name: 'LoveMaster',
    component: () => import('../views/LoveMaster.vue'),
    meta: {
      title: 'AI恋爱大师 - 恋爱大师智能体应用平台',
      description: 'AI恋爱大师是恋爱大师智能体应用平台的专业情感顾问，帮你解答各种恋爱问题，提供情感建议'
    }
  },
  {
    path: '/super-agent',
    name: 'SuperAgent',
    component: () => import('../views/SuperAgent.vue'),
    meta: {
      title: 'AI超级智能体 - 恋爱大师智能体应用平台',
      description: 'AI超级智能体是恋爱大师智能体应用平台的全能助手，能解答各类专业问题，提供精准建议和解决方案'
    }
  },
  {
    path: '/multi-agent',
    name: 'MultiAgent',
    component: () => import('../views/MultiAgent.vue'),
    meta: {
      title: 'AI多智能体协调器 - 恋爱大师智能体应用平台',
      description: 'AI多智能体协调器会拆解复杂任务，调度搜索、文档和通用智能体协作完成任务'
    }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 全局导航守卫，设置文档标题
router.beforeEach((to, from, next) => {
  // 设置页面标题
  if (to.meta.title) {
    document.title = to.meta.title
  }
  next()
})

export default router 
