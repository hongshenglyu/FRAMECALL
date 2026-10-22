<template>
  <section class="annotation-workspace">
    <div class="panel annotation-video-panel">
      <div class="panel-title">视频标注工作台</div>
      <div class="annotation-source-row">
        <el-select v-model="selectedVideoId" filterable placeholder="选择已上传视频" @change="selectVideo">
          <el-option v-for="video in videos" :key="video.id" :label="videoLabel(video)" :value="video.id" />
        </el-select>
        <input type="file" accept="video/*" @change="selectLocalVideo" />
      </div>
      <video
        ref="videoRef"
        class="annotation-video"
        :src="videoSrc"
        controls
        @loadedmetadata="syncVideoMeta"
        @timeupdate="syncCurrentTime"
      />
      <div class="annotation-toolbar">
        <el-button :icon="Aim" @click="markKeyframe" :disabled="!videoSrc">关键帧</el-button>
        <el-button :icon="DArrowLeft" @click="markWindowStart" :disabled="!videoSrc">窗口起点</el-button>
        <el-button :icon="DArrowRight" @click="markWindowEnd" :disabled="!videoSrc">窗口终点</el-button>
        <el-button :icon="RefreshLeft" @click="seekRelative(-1)" :disabled="!videoSrc">-1s</el-button>
        <el-button :icon="RefreshRight" @click="seekRelative(1)" :disabled="!videoSrc">+1s</el-button>
      </div>
      <div class="annotation-time-grid">
        <div>
          <span>当前时间</span>
          <strong>{{ formatSeconds(currentTimeSeconds) }}</strong>
        </div>
        <div>
          <span>关键帧</span>
          <strong>{{ form.timestampMs }} ms</strong>
        </div>
        <div>
          <span>窗口</span>
          <strong>{{ form.windowStartMs }} - {{ form.windowEndMs }} ms</strong>
        </div>
      </div>
    </div>

    <div class="panel annotation-form-panel">
      <div class="panel-title">事件录入</div>
      <el-form :model="form" label-width="88px">
        <el-form-item label="数据划分">
          <el-select v-model="dataset.split">
            <el-option label="train" value="train" />
            <el-option label="val" value="val" />
            <el-option label="test" value="test" />
            <el-option label="demo" value="demo" />
          </el-select>
        </el-form-item>
        <el-form-item label="事件类型">
          <el-segmented v-model="form.eventType" :options="eventTypes" />
        </el-form-item>
        <el-form-item label="裁判结论">
          <el-segmented v-model="form.label" :options="labels" />
        </el-form-item>
        <el-form-item label="风险等级">
          <el-select v-model="form.riskLevel">
            <el-option label="HIGH" value="HIGH" />
            <el-option label="MEDIUM" value="MEDIUM" />
            <el-option label="LOW" value="LOW" />
            <el-option label="UNCERTAIN" value="UNCERTAIN" />
          </el-select>
        </el-form-item>
        <el-form-item label="涉事ID">
          <el-input v-model="form.involvedTrackIds" placeholder="7,12" />
        </el-form-item>
        <el-form-item label="原因码">
          <el-input v-model="form.reasonCode" placeholder="CONTACT_VISIBLE" />
        </el-form-item>
        <el-form-item label="信心分">
          <el-input-number v-model="form.confidence" :min="0" :max="1" :step="0.05" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.note" type="textarea" :rows="3" />
        </el-form-item>
        <div class="annotation-actions">
          <el-button type="primary" :icon="Plus" @click="addEvent" :disabled="!canAddEvent">加入事件</el-button>
          <el-button :icon="Refresh" @click="resetForm">重置表单</el-button>
        </div>
      </el-form>
    </div>

    <div class="panel annotation-events-panel">
      <div class="panel-title">当前样本事件</div>
      <el-table :data="events" border>
        <el-table-column prop="eventType" label="类型" width="110" />
        <el-table-column prop="label" label="结论" width="120" />
        <el-table-column prop="timestampMs" label="关键帧(ms)" width="120" />
        <el-table-column prop="windowStartMs" label="起点" width="100" />
        <el-table-column prop="windowEndMs" label="终点" width="100" />
        <el-table-column prop="reasonCode" label="原因码" min-width="150" />
        <el-table-column label="操作" width="96">
          <template #default="{ $index }">
            <el-button size="small" type="danger" :icon="Delete" @click="removeEvent($index)" />
          </template>
        </el-table-column>
      </el-table>
      <div class="annotation-export-row">
        <el-input v-model="dataset.datasetId" placeholder="datasetId" />
        <el-input v-model="dataset.datasetName" placeholder="datasetName" />
        <el-button type="primary" :icon="Upload" @click="saveToBackend" :disabled="!canSave">保存标注</el-button>
        <el-button :icon="Download" @click="downloadJson" :disabled="!canSave">下载JSON</el-button>
      </div>
    </div>
  </section>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Aim, DArrowLeft, DArrowRight, Delete, Download, Plus, Refresh, RefreshLeft, RefreshRight, Upload } from '@element-plus/icons-vue'
import { api, fileUrl } from '../api/client'

const videos = ref([])
const selectedVideoId = ref(null)
const videoRef = ref(null)
const videoSrc = ref('')
const localObjectUrl = ref('')
const currentTimeSeconds = ref(0)
const durationMs = ref(0)
const selectedSource = reactive({
  sampleId: '',
  title: '',
  uri: '',
  originalName: ''
})
const dataset = reactive({
  datasetId: 'manual-football-labels-v1',
  datasetName: 'Manual Football Labels',
  split: 'demo'
})
const form = reactive(defaultForm())
const events = ref([])
const saving = ref(false)
let savedTaskId = null

const eventTypes = ['FOUL', 'HANDBALL', 'OFFSIDE']
const labels = ['CONFIRMED', 'REJECTED', 'UNCERTAIN']

const canAddEvent = computed(() => Boolean(videoSrc.value && form.timestampMs >= 0 && form.windowEndMs > form.windowStartMs))
const canSave = computed(() => Boolean(!saving.value && selectedSource.sampleId && events.value.length > 0))

onMounted(loadVideos)
onBeforeUnmount(clearLocalObjectUrl)

async function loadVideos() {
  const { data } = await api.get('/videos')
  videos.value = data
}

function videoLabel(video) {
  return `${video.title} / ${video.teams}`
}

function selectVideo(videoId) {
  if (saving.value) return
  savedTaskId = null
  clearLocalObjectUrl()
  const video = videos.value.find((item) => item.id === videoId)
  if (!video) return
  selectedSource.sampleId = `video-${video.id}`
  selectedSource.title = video.title
  selectedSource.uri = video.originalPath
  selectedSource.originalName = video.title
  videoSrc.value = fileUrl(video.originalPath)
  events.value = []
  resetForm()
}

function selectLocalVideo(event) {
  if (saving.value) return
  savedTaskId = null
  const file = event.target.files?.[0]
  if (!file) return
  selectedVideoId.value = null
  clearLocalObjectUrl()
  localObjectUrl.value = URL.createObjectURL(file)
  videoSrc.value = localObjectUrl.value
  selectedSource.sampleId = sampleIdFromName(file.name)
  selectedSource.title = file.name
  selectedSource.uri = file.name
  selectedSource.originalName = file.name
  events.value = []
  resetForm()
  event.target.value = ''
}

function syncVideoMeta() {
  durationMs.value = Math.round((videoRef.value?.duration || 0) * 1000)
  if (!form.windowEndMs && durationMs.value) {
    form.windowEndMs = Math.min(durationMs.value, form.windowStartMs + 6000)
  }
}

function syncCurrentTime() {
  currentTimeSeconds.value = videoRef.value?.currentTime || 0
}

function markKeyframe() {
  const currentMs = currentMsFromVideo()
  form.timestampMs = currentMs
  form.keyframeFrameIndex = Math.round(currentMs / 40)
  if (!form.windowStartMs || form.windowStartMs > currentMs) {
    form.windowStartMs = Math.max(0, currentMs - 3000)
  }
  if (!form.windowEndMs || form.windowEndMs < currentMs) {
    form.windowEndMs = currentMs + 3000
  }
}

function markWindowStart() {
  form.windowStartMs = currentMsFromVideo()
  if (form.windowEndMs <= form.windowStartMs) {
    form.windowEndMs = form.windowStartMs + 3000
  }
}

function markWindowEnd() {
  form.windowEndMs = currentMsFromVideo()
  if (form.windowEndMs <= form.windowStartMs) {
    form.windowStartMs = Math.max(0, form.windowEndMs - 3000)
  }
}

function seekRelative(seconds) {
  if (!videoRef.value) return
  videoRef.value.currentTime = Math.max(0, videoRef.value.currentTime + seconds)
}

function addEvent() {
  if (!canAddEvent.value) return
  const eventIndex = events.value.length + 1
  events.value.push({
    eventId: `${selectedSource.sampleId}-${crypto.randomUUID()}`,
    eventType: form.eventType,
    label: form.label,
    riskLevel: form.riskLevel,
    timestampMs: form.timestampMs,
    keyframe: {
      frameIndex: form.keyframeFrameIndex,
      timestampMs: form.timestampMs,
      toleranceFrames: 3,
      definition: form.eventType === 'FOUL' ? '首次明显身体接触帧' : '事件复核关键帧'
    },
    windowStartMs: form.windowStartMs,
    windowEndMs: form.windowEndMs,
    involvedTrackIds: parseTrackIds(form.involvedTrackIds),
    decision: {
      expertDecision: form.eventType,
      reviewResult: form.label,
      confidence: form.confidence,
      reasonCode: form.reasonCode,
      note: form.note
    },
    boxes: [],
    tracks: [],
    tags: [],
    createdBy: 'manual-annotator',
    createdAt: new Date().toISOString()
  })
  resetForm()
}

function removeEvent(index) {
  events.value.splice(index, 1)
}

async function saveToBackend() {
  if (!canSave.value) return
  saving.value = true
  const payload = buildAnnotationPayload()
  try {
    if (!savedTaskId) {
    const { data: task } = await api.post('/annotations', {
      title: `${selectedSource.title || selectedSource.sampleId} 手动标注`,
      source: selectedSource.uri || selectedSource.originalName || selectedSource.sampleId
    })
    savedTaskId = task.id
    }
    const file = new File([JSON.stringify(payload, null, 2)], `${selectedSource.sampleId}.annotation.v1.json`, {
      type: 'application/json'
    })
    const body = new FormData()
    body.append('file', file)
    await api.post(`/annotations/${savedTaskId}/labels`, body)
    ElMessage.success('标注已保存')
  } catch (error) {
    ElMessage.error(error.response?.data?.message || '标注保存失败')
  } finally {
    saving.value = false
  }
}

function downloadJson() {
  const blob = new Blob([JSON.stringify(buildAnnotationPayload(), null, 2)], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `${selectedSource.sampleId}.annotation.v1.json`
  document.body.appendChild(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(url)
}

function buildAnnotationPayload() {
  const now = new Date().toISOString()
  return {
    schemaVersion: 'annotation.v1',
    datasetId: dataset.datasetId,
    datasetName: dataset.datasetName,
    createdAt: now,
    updatedAt: now,
    annotators: [
      {
        annotatorId: 'manual-annotator',
        displayName: 'Manual Annotator',
        role: 'REFEREE'
      }
    ],
    samples: [
      {
        sampleId: selectedSource.sampleId,
        source: {
          type: 'local_file',
          uri: selectedSource.uri,
          originalName: selectedSource.originalName
        },
        split: dataset.split,
        durationMs: durationMs.value,
        fps: 25,
        events: events.value
      }
    ]
  }
}

function resetForm() {
  Object.assign(form, defaultForm())
  const currentMs = currentMsFromVideo()
  if (currentMs > 0) {
    form.timestampMs = currentMs
    form.keyframeFrameIndex = Math.round(currentMs / 40)
    form.windowStartMs = Math.max(0, currentMs - 3000)
    form.windowEndMs = currentMs + 3000
  }
}

function defaultForm() {
  return {
    eventType: 'FOUL',
    label: 'CONFIRMED',
    riskLevel: 'MEDIUM',
    timestampMs: 0,
    keyframeFrameIndex: 0,
    windowStartMs: 0,
    windowEndMs: 6000,
    involvedTrackIds: '',
    reasonCode: 'CONTACT_VISIBLE',
    confidence: 0.8,
    note: ''
  }
}

function currentMsFromVideo() {
  return Math.round((videoRef.value?.currentTime || 0) * 1000)
}

function parseTrackIds(value) {
  return value
    .split(',')
    .filter((item) => item.trim() !== '')
    .map((item) => Number(item.trim()))
    .filter((item) => Number.isFinite(item))
}

function sampleIdFromName(name) {
  return name.replace(/\.[^.]+$/, '').replace(/[^a-zA-Z0-9_-]+/g, '-').replace(/^-+|-+$/g, '') || 'manual-sample'
}

function formatSeconds(value) {
  return `${value.toFixed(2)} s`
}

function clearLocalObjectUrl() {
  if (localObjectUrl.value) {
    URL.revokeObjectURL(localObjectUrl.value)
    localObjectUrl.value = ''
  }
}
</script>
