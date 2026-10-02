import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { TOKEN_KEY, USER_KEY, authApi } from '@/api'

interface StoredUser {
  username: string
  userId: number
  offline: boolean
}

function readUser(): StoredUser | null {
  const raw = localStorage.getItem(USER_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw) as StoredUser
  } catch {
    return null
  }
}

export const useAuthStore = defineStore('auth', () => {
  const token = ref<string>(localStorage.getItem(TOKEN_KEY) ?? '')
  const user = ref<StoredUser | null>(readUser())
  const loading = ref(false)
  const error = ref('')

  const isAuthenticated = computed(() => Boolean(token.value))
  const username = computed(() => user.value?.username ?? '未登录')
  const usedOfflineLogin = computed(() => user.value?.offline ?? false)

  async function login(name: string, password: string): Promise<boolean> {
    loading.value = true
    error.value = ''
    try {
      const result = await authApi.login(name, password)
      token.value = result.token
      const offline = result.token.startsWith('demo-')
      user.value = { username: result.username, userId: result.userId, offline }
      localStorage.setItem(TOKEN_KEY, result.token)
      localStorage.setItem(USER_KEY, JSON.stringify(user.value))
      return true
    } catch (e) {
      error.value = (e as Error).message || '登录失败'
      return false
    } finally {
      loading.value = false
    }
  }

  function logout(): void {
    token.value = ''
    user.value = null
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(USER_KEY)
  }

  return { token, user, loading, error, isAuthenticated, username, usedOfflineLogin, login, logout }
})
