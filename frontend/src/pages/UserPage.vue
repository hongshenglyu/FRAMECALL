<template>
  <section class="panel">
    <div class="panel-title">创建用户</div>
    <el-form :model="form" inline>
      <el-form-item label="用户名"><el-input v-model="form.username" /></el-form-item>
      <el-form-item label="姓名"><el-input v-model="form.displayName" /></el-form-item>
      <el-form-item label="密码"><el-input v-model="form.password" type="password" /></el-form-item>
      <el-form-item label="角色">
        <el-select v-model="form.role" style="width: 130px">
          <el-option label="管理员" value="ADMIN" />
          <el-option label="裁判" value="REFEREE" />
        </el-select>
      </el-form-item>
      <el-button type="primary" @click="create">创建</el-button>
    </el-form>
    <el-divider />
    <el-table :data="users" border>
      <el-table-column prop="username" label="用户名" />
      <el-table-column prop="displayName" label="姓名" />
      <el-table-column prop="role" label="角色" width="120" />
      <el-table-column prop="enabled" label="启用" width="90" />
    </el-table>
  </section>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'

const users = ref([])
const form = reactive({ username: '', displayName: '', password: '123456', role: 'REFEREE' })

async function load() {
  const { data } = await api.get('/users')
  users.value = data
}

async function create() {
  await api.post('/users', form)
  ElMessage.success('用户已创建')
  form.username = ''
  form.displayName = ''
  await load()
}

onMounted(load)
</script>

