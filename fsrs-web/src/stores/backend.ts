import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { type BackendState, getBackendDetail, subscribeBackend } from '@/api'

/**
 * Tracks whether the API calls are hitting a live backend or the built-in demo
 * dataset, and surfaces the reason for the banner at the top of the shell.
 */
export const useBackendStore = defineStore('backend', () => {
  const state = ref<BackendState>('unknown')
  const detail = ref('')
  const lastChecked = ref<number>(Date.now())

  const isOffline = computed(() => state.value === 'offline')
  const isOnline = computed(() => state.value === 'online')
  const label = computed(() => {
    if (state.value === 'online') return '后端已连接'
    if (state.value === 'offline') return '后端未连接，当前为演示数据'
    return '正在检测后端连接…'
  })

  let bound = false
  function bind(): void {
    if (bound) return
    bound = true
    detail.value = getBackendDetail()
    subscribeBackend((next, why) => {
      state.value = next
      detail.value = why
      lastChecked.value = Date.now()
    })
  }

  function setState(next: BackendState, why = ''): void {
    state.value = next
    detail.value = why
    lastChecked.value = Date.now()
  }

  return { state, detail, lastChecked, isOffline, isOnline, label, bind, setState }
})
