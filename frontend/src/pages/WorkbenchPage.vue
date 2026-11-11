<template>
  <div class="workspace-grid">
    <section class="panel">
      <div class="panel-title">上传争议视频</div>
      <el-form label-position="top">
        <el-form-item label="标题">
          <el-input v-model="upload.title" />
        </el-form-item>
        <el-form-item label="赛事">
          <el-input v-model="upload.competition" />
        </el-form-item>
        <el-form-item label="队伍">
          <el-input v-model="upload.teams" placeholder="A队 vs B队" />
        </el-form-item>
        <el-form-item label="灵敏度">
          <el-select v-model="upload.sensitivity" style="width: 100%">
            <el-option label="高召回" value="HIGH_RECALL" />
            <el-option label="均衡" value="BALANCED" />
            <el-option label="低误报" value="LOW_FALSE_POSITIVE" />
          </el-select>
        </el-form-item>
        <el-form-item label="视频文件">
          <input type="file" accept="video/*" @change="onFile" />
        </el-form-item>
        <el-button type="primary" :loading="uploading" @click="submitUpload">上传并分析</el-button>
      </el-form>

      <el-divider />
      <div class="panel-title">导入真实候选／试标素材</div>
      <p class="muted">选择包含bundle.json与MP4的提取结果目录，复用已有真实分析成果。</p>
      <input type="file" webkitdirectory multiple @change="onBundleFiles" aria-label="真实候选包目录" />
      <el-button :loading="importing" :disabled="!bundleFiles.length" @click="importBundle">导入并复核</el-button>

      <el-divider />
      <div class="panel-title">分析任务</div>
      <el-checkbox v-model="includeArchived" @change="loadTasks">显示归档任务</el-checkbox>
      <div class="task-list">
        <div
          v-for="task in tasks"
          :key="task.id"
          class="task-row"
          :class="{ active: selectedTask?.id === task.id }"
          @click="selectTask(task.id)"
        >
          <div class="task-row-title">{{ task.videoTitle }}</div>
          <el-progress :percentage="task.progressPercent" :status="progressStatus(task.status)" />
          <div class="muted">{{ task.archivedAt ? '已归档' : task.status }} / {{ task.stage }}</div>
          <div v-if="task.errorMessage" class="task-error">{{ task.errorMessage }}</div>
        </div>
      </div>
    </section>

    <section class="panel">
      <template v-if="selectedTask">
        <div style="display: flex; justify-content: space-between; gap: 10px; margin-bottom: 14px">
          <div>
            <div class="panel-title">{{ selectedTask.videoTitle }}</div>
            <div class="muted">任务 {{ selectedTask.id }} / {{ selectedTask.status }} / {{ selectedTask.progressPercent }}%</div>
          </div>
          <div>
            <el-button @click="loadTasks">刷新</el-button>
            <el-button @click="cancelTask" :disabled="!canCancel">取消</el-button>
            <el-button v-if="!selectedTask.archivedAt && selectedTask.status === 'FAILED'" type="warning" @click="retryTask">重试</el-button>
            <el-button v-if="selectedTask.archivedAt" @click="restoreTask">恢复归档</el-button>
            <el-button v-else @click="archiveTask" :disabled="!canArchive">归档</el-button>
            <el-button type="primary" @click="downloadReport" :disabled="Boolean(selectedTask.archivedAt) || (!selectedTask.events?.length && selectedTask.status !== 'SUCCEEDED')">导出PDF</el-button>
          </div>
        </div>

        <el-alert
          v-if="selectedTask.errorMessage"
          :title="selectedTask.errorMessage"
          type="error"
          :closable="false"
          show-icon
          style="margin-bottom: 16px"
        />

        <el-select v-model="eventSelection" :disabled="Boolean(selectedTask.archivedAt)" @change="refreshSelectedTask" aria-label="结果版本">
          <el-option label="当前结果" value="current" />
          <el-option label="全部历史" value="all" />
          <el-option label="旧版未归属记录（LEGACY）" value="legacy" />
          <el-option v-for="attempt in selectedTask.attempts || []" :key="attempt.id" :label="`${attempt.status} / ${attempt.id}`" :value="`attempt:${attempt.id}`" />
        </el-select>
        <div class="muted">{{ selectedTask.archivedAt ? '归档保留历史与媒体；恢复后可继续查看。' : selectedTask.eventSelection }}</div>
        <template v-if="isPilotTask">
          <p class="muted">试标素材{{ pilotSummary.samples || selectedTask.events.length }}段 · 人工已提交{{ pilotSummary.humanAnnotatedSamples || 0 }}段 · 待标注{{ pilotSummary.unlabeledSamples ?? selectedTask.events.length }}段；不将选材线索或联调记录作为真值。</p>
          <el-button @click="exportPilot">导出试标记录JSON</el-button>
        </template>
        <template v-else-if="isContactTask">
          <p class="muted">保留{{ selectedTask.events.length }}个原始候选；默认按复核片段合并展示。评分不是犯规概率。</p>
          <el-checkbox v-model="showAllCandidates">显示全部原始候选（含未提取片段）</el-checkbox>
        </template>
        <div class="event-grid">
          <div v-for="event in displayedEvents" :key="event.id" class="event-card" :class="{ active: selectedEvent?.id === event.id }" @click="selectEvent(event)">
            <img v-if="isImagePath(event.keyframePath)" :src="fileUrl('event', event.id, 'keyframe')" />
            <div v-else class="event-card-placeholder">无关键帧</div>
            <div class="event-card-body">
              <el-tag :type="riskTag(event.riskLevel)">{{ isPilotTask ? '试标素材 · 人工状态见标注记录' : event.eventType === 'CONTACT' ? '身体接触候选 · 待人工复核' : `${event.eventType} / ${event.riskLevel}` }}</el-tag>
              <div style="margin-top: 8px; font-weight: 700">{{ formatTime(event.timestampMs) }}</div>
              <div class="muted">{{ event.summary }}</div>
              <div v-if="isPilotTask" class="muted">{{ event.externalId }}</div>
              <div class="muted">ID: {{ event.involvedTrackIds }}</div>
              <div v-if="eventSelection !== 'current'" class="muted">{{ event.attemptAssociation }} / {{ event.attemptId || 'LEGACY' }}</div>
            </div>
          </div>
        </div>

        <el-divider />
        <EventDetail v-if="selectedEvent" :event="selectedEvent" :related-events="relatedEvents" @select-event="selectEvent" @reviewed="refreshSelectedTask" />
        <ManualEventForm v-if="!selectedTask.archivedAt && !isContactTask" :task-id="selectedTask.id" @created="refreshSelectedTask" />
      </template>
      <el-empty v-else description="请选择或上传一个分析任务" />
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, fileUrl } from '../api/client'
import EventDetail from '../components/EventDetail.vue'
import ManualEventForm from '../components/ManualEventForm.vue'

const tasks = ref([])
const selectedTask = ref(null)
const selectedEvent = ref(null)
const uploading = ref(false)
const file = ref(null)
const includeArchived = ref(false)
const eventSelection = ref('current')
const bundleFiles = ref([])
const importing = ref(false)
const showAllCandidates = ref(false)
const isPilotTask = computed(() => selectedTask.value?.stage === 'CONTACT_PILOT_IMPORTED')
const pilotSummary = ref({})
const isContactTask = computed(() => isPilotTask.value || selectedTask.value?.stage === 'REAL_CONTACT_IMPORTED')
const displayedEvents = computed(() => {
  const events = selectedTask.value?.events || []
  if (!isContactTask.value || showAllCandidates.value) return events
  const clips = new Map()
  for (const event of events) {
    if (!event.clipPath) continue
    const anchors = JSON.parse(event.contactMetadataJson).clip.anchorEventIds
    if (!clips.has(event.clipPath) || anchors.includes(event.externalId)) clips.set(event.clipPath, event)
  }
  return [...clips.values()].sort((a,b) => a.timestampMs-b.timestampMs)
})
const relatedEvents = computed(() => selectedEvent.value?.clipPath ? (selectedTask.value?.events || []).filter(e => e.clipPath === selectedEvent.value.clipPath) : [])
let timer = null

const upload = reactive({
  title: '争议片段测试',
  competition: '校园足球赛',
  teams: '蓝队 vs 红队',
  sensitivity: 'BALANCED'
})

const canCancel = computed(() => !selectedTask.value?.archivedAt && ['PENDING', 'RUNNING'].includes(selectedTask.value?.status))
const canArchive = computed(() => !['RUNNING', 'CANCEL_REQUESTED'].includes(selectedTask.value?.status) && !(selectedTask.value?.attempts || []).some(a => a.active))

function onFile(event) {
  file.value = event.target.files?.[0] || null
}

function onBundleFiles(event) { bundleFiles.value = [...event.target.files] }
async function importBundle() {
  importing.value = true
  try {
    const manifest = bundleFiles.value.find(f => f.name === 'bundle.json')
    if (!manifest) throw new Error('请选择含bundle.json的目录')
    const bundle = JSON.parse(await manifest.text())
    const body = new FormData()
    body.append('manifest', manifest)
    for (const media of bundle.media) {
      const file = bundleFiles.value.find(f => f.name === media.file)
      if (!file) throw new Error(`缺少视频或关键帧：${media.file}`)
      body.append('media', file, file.name)
    }
    const { data } = await api.post('/videos/contact-import', body)
    ElMessage.success('真实候选和媒体已导入，待人工复核')
    await loadTasks(); await selectTask(data.task.id)
  } catch (error) { ElMessage.error(error.response?.data?.message || error.message || '导入失败') }
  finally { importing.value = false }
}

async function submitUpload() {
  if (!file.value) {
    ElMessage.warning('请先选择视频文件')
    return
  }
  uploading.value = true
  try {
    const body = new FormData()
    body.append('file', file.value)
    body.append('title', upload.title)
    body.append('competition', upload.competition)
    body.append('teams', upload.teams)
    body.append('sensitivity', upload.sensitivity)
    const { data } = await api.post('/videos', body)
    ElMessage.success('已创建分析任务')
    await loadTasks()
    await selectTask(data.task.id)
  } finally {
    uploading.value = false
  }
}

async function loadTasks() {
  const { data } = await api.get('/tasks', { params: { includeArchived: includeArchived.value } })
  tasks.value = data
}

async function selectTask(id) {
  if (selectedTask.value?.id !== id) eventSelection.value = 'current'
  const archived = tasks.value.find(task => task.id === id && task.archivedAt)
  if (archived) { selectedTask.value = { ...archived, events: [] }; selectedEvent.value = null; return }
  const selectedEventId = selectedEvent.value?.id
  const { data } = await api.get(`/tasks/${id}`, { params: historyParams() })
  selectedTask.value = data
  if (isPilotTask.value) pilotSummary.value = (await api.get(`/tasks/${id}/contact-annotations`)).data.summary
  const next = data.events?.find((event) => event.id === selectedEventId) || displayedEvents.value[0] || null
  if (next) await selectEvent(next)
  else selectedEvent.value = null
}

async function refreshSelectedTask() {
  if (selectedTask.value) {
    await selectTask(selectedTask.value.id)
  }
}

async function selectEvent(event) {
  selectedEvent.value = event
  const { data } = await api.get(`/events/${event.id}`)
  if (selectedEvent.value?.id === event.id) selectedEvent.value = data
}

async function cancelTask() {
  await api.post(`/tasks/${selectedTask.value.id}/cancel`)
  await refreshSelectedTask()
}

async function retryTask() {
  await api.post(`/tasks/${selectedTask.value.id}/retry`)
  ElMessage.success('任务已重新提交')
  await refreshSelectedTask()
}

function historyParams() {
  return eventSelection.value.startsWith('attempt:')
    ? { attemptId: eventSelection.value.slice(8) }
    : { scope: eventSelection.value }
}

async function archiveTask() {
  await api.delete(`/tasks/${selectedTask.value.id}`)
  selectedTask.value = null; selectedEvent.value = null
  ElMessage.success('已归档，历史复核与媒体已保留')
  await loadTasks()
}

async function restoreTask() {
  const id = selectedTask.value.id
  await api.post(`/tasks/${id}/restore`)
  await loadTasks(); await selectTask(id)
}

async function downloadReport() {
  try {
    const { data } = await api.get(`/tasks/${selectedTask.value.id}/report`, {
      params: historyParams(),
      responseType: 'blob'
    })
    const url = URL.createObjectURL(new Blob([data], { type: 'application/pdf' }))
    const link = document.createElement('a')
    link.href = url
    link.download = `referee-review-report-task-${selectedTask.value.id}.pdf`
    document.body.appendChild(link)
    link.click()
    link.remove()
    URL.revokeObjectURL(url)
  } catch (error) {
    ElMessage.error(error.response?.data?.message || '报告导出失败')
  }
}

async function exportPilot() {
  const { data } = await api.get(`/tasks/${selectedTask.value.id}/contact-annotations`)
  const url = URL.createObjectURL(new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' }))
  const link = document.createElement('a'); link.href = url; link.download = 'contact-pilot-annotations.json'; link.click(); URL.revokeObjectURL(url)
}

function riskTag(risk) {
  if (risk === 'HIGH') return 'danger'
  if (risk === 'MEDIUM') return 'warning'
  if (risk === 'LOW') return 'success'
  return 'info'
}

function progressStatus(status) {
  if (status === 'FAILED') return 'exception'
  if (status === 'SUCCEEDED') return 'success'
  return ''
}

function formatTime(ms) {
  return `${(ms / 1000).toFixed(2)}s`
}

function isImagePath(path = '') {
  return /\.(png|jpe?g|webp|gif)$/i.test(path)
}

onMounted(() => {
  loadTasks()
  timer = setInterval(async () => {
    await loadTasks()
    if (selectedTask.value && ['PENDING', 'RUNNING', 'CANCEL_REQUESTED'].includes(selectedTask.value.status)) await refreshSelectedTask()
  }, 2500)
})

onUnmounted(() => clearInterval(timer))
</script>
