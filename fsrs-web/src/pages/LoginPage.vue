<template>
  <div class="login">
    <div class="login__aurora" />
    <div class="login__inner">
      <section class="hero">
        <div class="hero__brand">
          <div class="hero__mark">FS</div>
          <div>
            <div class="hero__name">FSRS Engine</div>
            <div class="hero__sub">FSRS-6 记忆调度引擎 · 可视化控制台</div>
          </div>
        </div>

        <h1 class="hero__title">
          让每一次复习
          <span class="hero__accent">都发生在忘记之前</span>
        </h1>
        <p class="hero__desc">
          后端实现 D-S-R 三变量记忆模型与 21 参数调度，前端把「可提取性曲线、间隔预测、评分分布」全部可视化。
        </p>

        <ul class="hero__list">
          <li v-for="item in highlights" :key="item.title">
            <span class="hero__dot" />
            <div>
              <div class="hero__li-title">{{ item.title }}</div>
              <div class="hero__li-desc">{{ item.desc }}</div>
            </div>
          </li>
        </ul>

        <div class="hero__formula fs-mono">
          R(t, S) = (1 + factor · t / S)<sup>-w20</sup>
          <span class="hero__formula-note">factor = 0.9<sup>-1/w20</sup> - 1  → R(S, S) = 90%</span>
        </div>
      </section>

      <section class="panel fs-card">
        <div class="panel__head">
          <h2>登录</h2>
          <span class="fs-chip fs-chip--new">demo 环境</span>
        </div>

        <el-form class="panel__form" label-position="top" @submit.prevent="onSubmit">
          <el-form-item label="用户名">
            <el-input v-model="form.username" size="large" placeholder="demo" autocomplete="username" />
          </el-form-item>
          <el-form-item label="密码">
            <el-input
              v-model="form.password"
              size="large"
              type="password"
              show-password
              placeholder="demo1234"
              autocomplete="current-password"
              @keyup.enter="onSubmit"
            />
          </el-form-item>

          <transition name="fade-slide">
            <div v-if="auth.error" class="panel__error">{{ auth.error }}</div>
          </transition>

          <el-button
            class="panel__submit"
            type="primary"
            size="large"
            :loading="auth.loading"
            native-type="submit"
            @click="onSubmit"
          >
            {{ auth.loading ? '登录中…' : '进入控制台' }}
          </el-button>
        </el-form>

        <div class="panel__demo">
          <div class="panel__demo-title">演示账号</div>
          <div class="panel__demo-row">
            <span class="fs-kbd">demo</span>
            <span class="panel__demo-sep">/</span>
            <span class="fs-kbd">demo1234</span>
            <button class="panel__fill" @click="fillDemo">一键填入</button>
          </div>
          <p class="panel__note">
            首次登录时若 8080 上的后端尚未启动，将自动进入离线演示模式，所有页面依然可以完整交互。
          </p>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

const form = reactive({ username: 'demo', password: 'demo1234' })

const highlights = [
  { title: '间隔预测', desc: '评分按钮上直接显示 4 种评分的下次间隔' },
  { title: '遗忘曲线', desc: '滑块调节 S 与目标保持率，曲线实时重绘' },
  { title: '参数实验室', desc: '21 个权重可编辑，改动立即反馈到曲线与间隔表' },
]

function fillDemo(): void {
  form.username = 'demo'
  form.password = 'demo1234'
}

async function onSubmit(): Promise<void> {
  if (!form.username || !form.password) {
    ElMessage.warning('请输入用户名与密码')
    return
  }
  const ok = await auth.login(form.username, form.password)
  if (!ok) return
  if (auth.usedOfflineLogin) {
    ElMessage.warning('后端未连接，已进入离线演示模式')
  } else {
    ElMessage.success(`欢迎回来，${auth.username}`)
  }
  const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/decks'
  await router.replace(redirect)
}
</script>

<style scoped>
.login {
  position: relative;
  min-height: 100vh;
  display: grid;
  place-items: center;
  padding: 32px 24px;
  overflow: hidden;
}

.login__aurora {
  position: absolute;
  inset: -20% -10% auto -10%;
  height: 70%;
  background:
    radial-gradient(520px 320px at 18% 20%, rgba(76, 201, 240, 0.22), transparent 65%),
    radial-gradient(560px 340px at 78% 12%, rgba(123, 140, 255, 0.22), transparent 65%),
    radial-gradient(480px 280px at 50% 90%, rgba(61, 220, 151, 0.12), transparent 70%);
  filter: blur(8px);
  pointer-events: none;
}

.login__inner {
  position: relative;
  width: 100%;
  max-width: 1060px;
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(340px, 420px);
  gap: 40px;
  align-items: center;
}

.hero__brand {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 26px;
}

.hero__mark {
  width: 42px;
  height: 42px;
  border-radius: 12px;
  display: grid;
  place-items: center;
  font-weight: 700;
  color: #04121b;
  background: linear-gradient(135deg, #4cc9f0, #7b8cff 70%);
  box-shadow: 0 10px 26px rgba(76, 201, 240, 0.3);
}

.hero__name {
  font-size: 16px;
  font-weight: 650;
  letter-spacing: 0.4px;
}

.hero__sub {
  font-size: 12px;
  color: var(--fs-text-2);
}

.hero__title {
  margin: 0 0 14px;
  font-size: 40px;
  line-height: 1.22;
  font-weight: 700;
  letter-spacing: 0.5px;
}

.hero__accent {
  display: block;
  background: linear-gradient(100deg, #4cc9f0, #7b8cff 60%, #3ddc97);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}

.hero__desc {
  margin: 0 0 24px;
  max-width: 520px;
  color: var(--fs-text-1);
  font-size: 14px;
  line-height: 1.75;
}

.hero__list {
  list-style: none;
  margin: 0 0 26px;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 13px;
  max-width: 520px;
}

.hero__list li {
  display: flex;
  gap: 11px;
  align-items: flex-start;
}

.hero__dot {
  width: 7px;
  height: 7px;
  margin-top: 7px;
  border-radius: 50%;
  flex: none;
  background: var(--fs-accent);
  box-shadow: 0 0 10px rgba(76, 201, 240, 0.8);
}

.hero__li-title {
  font-size: 13.5px;
  font-weight: 600;
}

.hero__li-desc {
  font-size: 12.5px;
  color: var(--fs-text-2);
}

.hero__formula {
  display: inline-flex;
  flex-direction: column;
  gap: 4px;
  padding: 12px 16px;
  border-radius: var(--fs-radius-sm);
  border: 1px solid var(--fs-line);
  background: rgba(16, 26, 44, 0.66);
  font-size: 13px;
  color: #cfe9f7;
}

.hero__formula-note {
  font-size: 11.5px;
  color: var(--fs-text-2);
}

.panel {
  padding: 26px 24px 22px;
}

.panel__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
}

.panel__head h2 {
  margin: 0;
  font-size: 19px;
  font-weight: 650;
}

.panel__form {
  margin-top: 14px;
}

.panel__error {
  margin-bottom: 12px;
  padding: 9px 12px;
  border-radius: 9px;
  font-size: 12.5px;
  color: #ffc3c9;
  background: rgba(255, 93, 108, 0.12);
  border: 1px solid rgba(255, 93, 108, 0.35);
}

.panel__submit {
  width: 100%;
  margin-top: 4px;
  letter-spacing: 1px;
}

.panel__demo {
  margin-top: 20px;
  padding-top: 16px;
  border-top: 1px dashed var(--fs-line);
}

.panel__demo-title {
  font-size: 11.5px;
  color: var(--fs-text-2);
  margin-bottom: 8px;
}

.panel__demo-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.panel__demo-sep {
  color: var(--fs-text-2);
}

.panel__fill {
  margin-left: auto;
  background: rgba(76, 201, 240, 0.12);
  border: 1px solid rgba(76, 201, 240, 0.36);
  color: #a8e2f7;
  border-radius: 8px;
  font-size: 12px;
  padding: 4px 10px;
  cursor: pointer;
}

.panel__fill:hover {
  background: rgba(76, 201, 240, 0.2);
}

.panel__note {
  margin: 12px 0 0;
  font-size: 11.5px;
  color: var(--fs-text-2);
  line-height: 1.65;
}

@media (max-width: 940px) {
  .login__inner {
    grid-template-columns: minmax(0, 1fr);
    gap: 26px;
  }
  .hero__title {
    font-size: 30px;
  }
}
</style>
