<template>
  <transition name="fade-slide">
    <div v-if="backend.isOffline" class="banner">
      <span class="banner__pulse" />
      <span class="banner__text">
        <strong>后端未连接，当前为演示数据</strong>
        <span class="banner__detail">
          /api 代理目标 http://localhost:8080 不可达{{ backend.detail ? ` · ${backend.detail}` : '' }} ·
          页面功能完整可用，提交仅更新内存中的演示数据
        </span>
      </span>
      <button class="banner__retry" :disabled="probing" @click="retry">
        {{ probing ? '检测中…' : '重新检测' }}
      </button>
    </div>
  </transition>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { markOnline, markOffline, schedulerApi } from '@/api'
import { useBackendStore } from '@/stores/backend'

const backend = useBackendStore()
const probing = ref(false)

async function retry(): Promise<void> {
  probing.value = true
  try {
    // A light documented GET: if it answers, the backend is back.
    await schedulerApi.params()
    if (backend.state === 'online') return
  } catch {
    markOffline('重新检测失败')
  } finally {
    probing.value = false
  }
}

// `markOnline` is invoked by the response interceptor; nothing else to do here.
void markOnline
</script>

<style scoped>
.banner {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 9px 18px;
  background: linear-gradient(90deg, rgba(255, 171, 77, 0.16), rgba(255, 93, 108, 0.12));
  border-bottom: 1px solid rgba(255, 171, 77, 0.36);
  color: #ffe0b8;
  font-size: 12.5px;
  position: sticky;
  top: 0;
  z-index: 30;
  backdrop-filter: blur(8px);
}

.banner__pulse {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: var(--fs-hard);
  box-shadow: 0 0 0 0 rgba(255, 171, 77, 0.7);
  animation: pulse 1.8s infinite;
  flex: none;
}

@keyframes pulse {
  0% {
    box-shadow: 0 0 0 0 rgba(255, 171, 77, 0.6);
  }
  70% {
    box-shadow: 0 0 0 9px rgba(255, 171, 77, 0);
  }
  100% {
    box-shadow: 0 0 0 0 rgba(255, 171, 77, 0);
  }
}

.banner__text {
  display: flex;
  flex-direction: column;
  line-height: 1.45;
  min-width: 0;
}

.banner__detail {
  font-size: 11.5px;
  color: rgba(255, 224, 184, 0.72);
}

.banner__retry {
  margin-left: auto;
  flex: none;
  background: rgba(255, 171, 77, 0.14);
  border: 1px solid rgba(255, 171, 77, 0.45);
  color: #ffd7a3;
  border-radius: 8px;
  padding: 4px 12px;
  font-size: 12px;
  cursor: pointer;
  transition: all 0.15s ease;
}

.banner__retry:hover:not(:disabled) {
  background: rgba(255, 171, 77, 0.24);
}

.banner__retry:disabled {
  opacity: 0.6;
  cursor: default;
}
</style>
