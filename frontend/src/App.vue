<template>
  <LoginPage v-if="!session.user" @logged-in="handleLogin" />
  <div v-else class="app-shell">
    <aside class="sidebar">
      <div class="brand-lockup sidebar-brand">
        <img src="/brand/logo.svg" alt="" />
        <div><strong>刻界</strong><small>FRAMECALL</small></div>
      </div>
      <el-button class="nav-button" :type="section === 'workbench' ? 'primary' : 'default'" @click="section = 'workbench'">
        工作台
      </el-button>
      <el-button class="nav-button" :type="section === 'users' ? 'primary' : 'default'" @click="section = 'users'" v-if="session.user.role === 'ADMIN'">
        用户管理
      </el-button>
      <el-button class="nav-button" :type="section === 'annotations' ? 'primary' : 'default'" @click="section = 'annotations'" v-if="session.user.role === 'ADMIN'">
        标注管理
      </el-button>
      <el-button class="nav-button" :type="section === 'videoAnnotation' ? 'primary' : 'default'" @click="section = 'videoAnnotation'" v-if="session.user.role === 'ADMIN'">
        视频标注
      </el-button>
      <div class="sidebar-meta">
        <strong>{{ session.user.displayName }}</strong>
        <span>{{ session.user.role }}</span>
        <small>吉林外国语大学<br />毕业设计项目</small>
      </div>
    </aside>
    <main class="main">
      <header class="topbar">
        <div>
          <div class="page-title">{{ title }}</div>
          <div class="muted">当前用户：{{ session.user.displayName }} / {{ session.user.role }}</div>
        </div>
        <div class="topbar-actions">
          <span class="muted">在线 · {{ session.user.displayName }}</span>
          <el-button @click="toggleTheme">{{ darkMode ? '浅色主题' : '深色主题' }}</el-button>
          <el-button @click="logout">退出</el-button>
        </div>
      </header>
      <WorkbenchPage v-if="section === 'workbench'" />
      <UserPage v-if="section === 'users'" />
      <AnnotationPage v-if="section === 'annotations'" />
      <VideoAnnotationPage v-if="section === 'videoAnnotation'" />
    </main>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import LoginPage from './pages/LoginPage.vue'
import WorkbenchPage from './pages/WorkbenchPage.vue'
import UserPage from './pages/UserPage.vue'
import AnnotationPage from './pages/AnnotationPage.vue'
import VideoAnnotationPage from './pages/VideoAnnotationPage.vue'

const section = ref('workbench')
const darkMode = ref(localStorage.getItem('theme') !== 'light')
const session = reactive({
  user: JSON.parse(localStorage.getItem('user') || 'null')
})

const title = computed(() => {
  if (section.value === 'users') return '用户管理'
  if (section.value === 'annotations') return '标注任务管理'
  if (section.value === 'videoAnnotation') return '视频手动标注'
  return '裁判快速复核工作台'
})

function handleLogin(payload) {
  localStorage.setItem('token', payload.token)
  localStorage.setItem('user', JSON.stringify(payload.user))
  session.user = payload.user
}

function logout() {
  localStorage.removeItem('token')
  localStorage.removeItem('user')
  session.user = null
}

function applyTheme() {
  document.documentElement.classList.toggle('light-theme', !darkMode.value)
  localStorage.setItem('theme', darkMode.value ? 'dark' : 'light')
}

function toggleTheme() {
  darkMode.value = !darkMode.value
  applyTheme()
}

onMounted(applyTheme)
</script>
