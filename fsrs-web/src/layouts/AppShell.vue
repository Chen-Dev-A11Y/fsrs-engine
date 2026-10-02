<template>
  <div class="shell">
    <OfflineBanner />
    <div class="shell__body">
      <aside class="sidebar">
        <div class="brand">
          <div class="brand__mark">FS</div>
          <div class="brand__text">
            <div class="brand__name">FSRS Engine</div>
            <div class="brand__sub">记忆调度可视化台</div>
          </div>
        </div>

        <nav class="nav">
          <router-link v-for="item in nav" :key="item.path" :to="item.path" class="nav__item">
            <span class="nav__icon" v-html="item.icon" />
            <span class="nav__label">{{ item.label }}</span>
            <span v-if="item.badge" class="nav__badge">{{ item.badge }}</span>
          </router-link>
        </nav>

        <div class="side-card">
          <div class="side-card__title">
            <span class="dot" :class="backend.isOffline ? 'dot--warn' : backend.isOnline ? 'dot--ok' : 'dot--idle'" />
            {{ backend.isOnline ? '实时数据' : backend.isOffline ? '演示数据' : '检测中' }}
          </div>
          <p class="side-card__text">
            {{ backend.isOnline ? '所有数据来自 Spring Boot 后端。' : '后端 /api 不可达，页面使用内置数据集。' }}
          </p>
        </div>

        <div class="user">
          <div class="user__avatar">{{ initials }}</div>
          <div class="user__meta">
            <div class="user__name">{{ auth.username }}</div>
            <div class="user__sub">uid {{ auth.user?.userId ?? '—' }}</div>
          </div>
          <button class="user__out" title="退出登录" @click="onLogout">退出</button>
        </div>
      </aside>

      <main class="content">
        <router-view v-slot="{ Component }">
          <transition name="fade-slide" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </main>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import OfflineBanner from '@/components/OfflineBanner.vue'
import { useAuthStore } from '@/stores/auth'
import { useBackendStore } from '@/stores/backend'

const auth = useAuthStore()
const backend = useBackendStore()
const router = useRouter()

const icon = {
  decks:
    '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><rect x="3" y="4" width="18" height="5" rx="1.6"/><rect x="3" y="11" width="18" height="5" rx="1.6"/><path d="M7 20h10"/></svg>',
  study:
    '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M4 5.5A2.5 2.5 0 0 1 6.5 3H20v15H6.5A2.5 2.5 0 0 0 4 20.5z"/><path d="M8 7.5h8M8 11h5"/></svg>',
  stats:
    '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M4 20V9M10 20V4M16 20v-7M22 20H2"/></svg>',
  params:
    '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><circle cx="12" cy="12" r="3.2"/><path d="M12 2.5v3M12 18.5v3M2.5 12h3M18.5 12h3M5.3 5.3l2.1 2.1M16.6 16.6l2.1 2.1M18.7 5.3l-2.1 2.1M7.4 16.6l-2.1 2.1"/></svg>',
  compare:
    '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M4 17c3.5 0 4-9 8-9s3.5 5 8 5"/><path d="M4 20h16M4 4h16" opacity="0.45"/></svg>',
}

const nav = computed(() => [
  { path: '/decks', label: '卡组与卡片', icon: icon.decks, badge: '' },
  { path: '/study', label: '复习', icon: icon.study, badge: '核心' },
  { path: '/stats', label: '统计可视化', icon: icon.stats, badge: '' },
  { path: '/scheduler', label: '调度参数', icon: icon.params, badge: '' },
  { path: '/compare', label: '参数对比', icon: icon.compare, badge: '' },
])

const initials = computed(() => (auth.username || 'D').slice(0, 1).toUpperCase())

function onLogout(): void {
  auth.logout()
  router.push('/login')
}
</script>

<style scoped>
.shell {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
}

.shell__body {
  display: flex;
  flex: 1;
  min-height: 0;
}

.sidebar {
  width: 236px;
  flex: 0 0 236px;
  display: flex;
  flex-direction: column;
  gap: 18px;
  padding: 20px 14px;
  border-right: 1px solid var(--fs-line-soft);
  background: linear-gradient(180deg, rgba(13, 21, 36, 0.86), rgba(8, 13, 24, 0.7));
  position: sticky;
  top: 0;
  height: 100vh;
}

.brand {
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 2px 6px 0;
}

.brand__mark {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  display: grid;
  place-items: center;
  font-weight: 700;
  font-size: 13px;
  letter-spacing: 0.5px;
  color: #04121b;
  background: linear-gradient(135deg, #4cc9f0, #7b8cff 70%);
  box-shadow: 0 8px 20px rgba(76, 201, 240, 0.28);
}

.brand__name {
  font-size: 14.5px;
  font-weight: 650;
  letter-spacing: 0.3px;
}

.brand__sub {
  font-size: 11px;
  color: var(--fs-text-2);
}

.nav {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.nav__item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 11px;
  border-radius: var(--fs-radius-sm);
  color: var(--fs-text-1);
  text-decoration: none;
  font-size: 13.5px;
  border: 1px solid transparent;
  transition: background 0.16s ease, color 0.16s ease, border-color 0.16s ease;
}

.nav__item:hover {
  background: rgba(76, 201, 240, 0.08);
  color: var(--fs-text-0);
}

.nav__item.router-link-active {
  background: linear-gradient(90deg, rgba(76, 201, 240, 0.18), rgba(123, 140, 255, 0.08));
  border-color: rgba(76, 201, 240, 0.32);
  color: #eaf6ff;
  font-weight: 600;
}

.nav__icon {
  width: 18px;
  height: 18px;
  display: inline-flex;
  opacity: 0.92;
}

.nav__icon :deep(svg) {
  width: 18px;
  height: 18px;
}

.nav__label {
  flex: 1;
}

.nav__badge {
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 999px;
  background: rgba(76, 201, 240, 0.16);
  border: 1px solid rgba(76, 201, 240, 0.34);
  color: #9fd8ef;
}

.side-card {
  margin-top: auto;
  padding: 12px;
  border-radius: var(--fs-radius-sm);
  border: 1px solid var(--fs-line-soft);
  background: rgba(16, 26, 44, 0.6);
}

.side-card__title {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 12px;
  font-weight: 600;
}

.side-card__text {
  margin: 6px 0 0;
  font-size: 11.5px;
  color: var(--fs-text-2);
  line-height: 1.55;
}

.dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  display: inline-block;
}
.dot--ok {
  background: var(--fs-good);
  box-shadow: 0 0 8px rgba(61, 220, 151, 0.8);
}
.dot--warn {
  background: var(--fs-hard);
  box-shadow: 0 0 8px rgba(255, 171, 77, 0.8);
}
.dot--idle {
  background: var(--fs-text-2);
}

.user {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px;
  border-radius: var(--fs-radius-sm);
  border: 1px solid var(--fs-line-soft);
  background: rgba(16, 26, 44, 0.5);
}

.user__avatar {
  width: 30px;
  height: 30px;
  border-radius: 8px;
  display: grid;
  place-items: center;
  background: rgba(123, 140, 255, 0.2);
  border: 1px solid rgba(123, 140, 255, 0.4);
  font-weight: 600;
  font-size: 13px;
}

.user__meta {
  flex: 1;
  min-width: 0;
}

.user__name {
  font-size: 12.5px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.user__sub {
  font-size: 10.5px;
  color: var(--fs-text-2);
}

.user__out {
  background: transparent;
  border: 1px solid var(--fs-line);
  color: var(--fs-text-2);
  border-radius: 7px;
  font-size: 11px;
  padding: 3px 8px;
  cursor: pointer;
  transition: all 0.15s ease;
}

.user__out:hover {
  color: #ff9aa4;
  border-color: rgba(255, 93, 108, 0.45);
  background: rgba(255, 93, 108, 0.08);
}

.content {
  flex: 1;
  min-width: 0;
  overflow-x: hidden;
}

@media (max-width: 900px) {
  .shell__body {
    flex-direction: column;
  }
  .sidebar {
    width: 100%;
    flex: none;
    height: auto;
    position: static;
    border-right: none;
    border-bottom: 1px solid var(--fs-line-soft);
  }
  .nav {
    flex-direction: row;
    flex-wrap: wrap;
  }
  .side-card {
    margin-top: 0;
  }
}
</style>
