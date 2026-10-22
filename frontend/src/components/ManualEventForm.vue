<template>
  <div class="panel" style="margin-top: 16px">
    <div class="panel-title">手动补录争议事件</div>
    <el-form :model="form" inline>
      <el-form-item label="类型">
        <el-select v-model="form.eventType" style="width: 140px">
          <el-option label="犯规" value="FOUL" />
          <el-option label="手球" value="HANDBALL" />
          <el-option label="越位" value="OFFSIDE" />
        </el-select>
      </el-form-item>
      <el-form-item label="时间(ms)">
        <el-input-number v-model="form.timestampMs" :min="0" />
      </el-form-item>
      <el-form-item label="涉事ID">
        <el-input v-model="trackText" placeholder="7,12" style="width: 120px" />
      </el-form-item>
      <el-form-item label="备注">
        <el-input v-model="form.summary" style="width: 260px" />
      </el-form-item>
      <el-button @click="create">添加</el-button>
    </el-form>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'

const props = defineProps({ taskId: { type: Number, required: true } })
const emit = defineEmits(['created'])
const trackText = ref('7,12')
const form = reactive({
  eventType: 'FOUL',
  timestampMs: 0,
  summary: '人工补录争议事件'
})

async function create() {
  const involvedTrackIds = trackText.value
    .split(',')
    .map((v) => Number(v.trim()))
    .filter((v) => !Number.isNaN(v))
  await api.post('/events/manual', { ...form, taskId: props.taskId, involvedTrackIds })
  ElMessage.success('已添加人工事件')
  emit('created')
}
</script>

