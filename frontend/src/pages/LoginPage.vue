<template>
  <div class="login-page" @pointermove="moveCover" @pointerleave="resetCover">
    <section class="login-panel">
      <div class="brand-lockup login-brand">
        <img src="/brand/logo.svg" alt="刻界标志" />
        <div><strong>刻界</strong><small>FRAMECALL</small></div>
      </div>
      <h1>每一帧，<br />都值得看清。</h1>
      <p class="login-intro">足球争议事件辅助复核平台。<br />用影像还原过程，让判断有据可循。</p>
      <el-form :model="form" label-position="top" @submit.prevent="submit">
        <el-form-item label="用户名">
          <el-input v-model="form.username" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" type="password" show-password />
        </el-form-item>
        <el-button type="primary" class="login-submit" :loading="loading" @click="submit">
          <span>进入工作空间</span><small>ENTER</small>
        </el-button>
      </el-form>
      <p class="login-hint">默认账号：admin / admin123，referee / referee123</p>
    </section>
    <section class="login-cover" :style="coverStyle" aria-label="刻界品牌封面">
      <img src="/brand/cover.png" alt="悬浮的金属足球与黄绿色取景框" />
      <div class="cover-top"><strong>FRAMECALL</strong><span>FOOTBALL REVIEW STUDIO</span></div>
      <div class="cover-copy"><strong>EVERY FRAME.<br />A CLEARER CALL.</strong><span>看见关键时刻，厘清判断边界。</span></div>
    </section>
  </div>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'

const emit = defineEmits(['logged-in'])
const loading = ref(false)
const form = reactive({ username: 'admin', password: 'admin123' })
const pointer = reactive({ x: 0, y: 0 })
const coverStyle = computed(() => ({ '--cover-x': `${pointer.x}px`, '--cover-y': `${pointer.y}px` }))

function moveCover(event) {
  if (!window.matchMedia('(pointer: fine) and (prefers-reduced-motion: no-preference)').matches) return
  const rect = event.currentTarget.getBoundingClientRect()
  pointer.x = ((event.clientX - rect.left) / rect.width - 0.5) * -10
  pointer.y = ((event.clientY - rect.top) / rect.height - 0.5) * -10
}

function resetCover() {
  pointer.x = 0
  pointer.y = 0
}

async function submit() {
  loading.value = true
  try {
    const { data } = await api.post('/auth/login', form)
    emit('logged-in', data)
  } catch (error) {
    ElMessage.error(error.response?.data?.message || '登录失败')
  } finally {
    loading.value = false
  }
}
</script>
