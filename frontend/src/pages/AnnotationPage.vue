<template>
  <section class="panel">
    <div class="panel-title">标注任务登记</div>
    <el-form :model="form" inline>
      <el-form-item label="标题"><el-input v-model="form.title" /></el-form-item>
      <el-form-item label="来源"><el-input v-model="form.source" style="width: 320px" /></el-form-item>
      <el-button type="primary" @click="create">登记</el-button>
    </el-form>
    <el-divider />
    <el-table :data="items" border>
      <el-table-column prop="title" label="标题" />
      <el-table-column prop="source" label="来源" />
      <el-table-column prop="sampleCount" label="样本" width="90" />
      <el-table-column prop="eventCount" label="事件" width="90" />
      <el-table-column label="类型分布" min-width="180">
        <template #default="{ row }">
          <span class="muted">{{ row.eventTypeSummary || '未导入' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="数据划分" min-width="180">
        <template #default="{ row }">
          <span class="muted">{{ row.splitSummary || '未导入' }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="140" />
      <el-table-column label="导入标注" width="220">
        <template #default="{ row }">
          <input type="file" accept=".json,application/json" @change="(e) => upload(row.id, e)" />
        </template>
      </el-table-column>
      <el-table-column label="导出" width="180">
        <template #default="{ row }">
          <el-button size="small" :icon="Download" :disabled="!row.labelFilePath" @click="downloadLabels(row, 'json')">JSON</el-button>
          <el-button size="small" :icon="Download" :disabled="!row.labelFilePath" @click="downloadLabels(row, 'csv')">CSV</el-button>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="110" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="danger" :icon="Delete" @click="deleteAnnotation(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
  </section>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, Download } from '@element-plus/icons-vue'
import { api } from '../api/client'

const items = ref([])
const form = reactive({ title: '', source: '' })

async function load() {
  const { data } = await api.get('/annotations')
  items.value = data
}

async function create() {
  await api.post('/annotations', form)
  ElMessage.success('标注任务已登记')
  form.title = ''
  form.source = ''
  await load()
}

async function upload(id, event) {
  const file = event.target.files?.[0]
  if (!file) return
  const body = new FormData()
  body.append('file', file)
  try {
    await api.post(`/annotations/${id}/labels`, body)
    ElMessage.success('标注文件已导入')
    await load()
  } catch (error) {
    ElMessage.error(error.response?.data?.message || '标注文件格式不符合 annotation.v1')
  } finally {
    event.target.value = ''
  }
}

async function downloadLabels(row, format) {
  const isCsv = format === 'csv'
  try {
    const { data } = await api.get(`/annotations/${row.id}/labels${isCsv ? '.csv' : ''}`, {
      responseType: 'blob'
    })
    const blob = new Blob([data], { type: isCsv ? 'text/csv;charset=utf-8' : 'application/json' })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `annotation-labels-${row.id}.${isCsv ? 'csv' : 'json'}`
    document.body.appendChild(link)
    link.click()
    link.remove()
    URL.revokeObjectURL(url)
  } catch (error) {
    ElMessage.error(error.response?.data?.message || '标注文件导出失败')
  }
}

async function deleteAnnotation(row) {
  try {
    await ElMessageBox.confirm(
      `确定删除标注任务「${row.title || row.id}」吗？删除后该任务和已导入的标注文件都会移除。`,
      '删除确认',
      {
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        type: 'warning',
        confirmButtonClass: 'el-button--danger'
      }
    )
  } catch {
    return
  }

  try {
    await api.delete(`/annotations/${row.id}`)
    ElMessage.success('标注任务已删除')
    await load()
  } catch (error) {
    ElMessage.error(error.response?.data?.message || '标注任务删除失败')
  }
}

onMounted(load)
</script>
