<template>
  <div class="detail-layout">
    <div class="event-visual">
      <div class="panel-title">事件详情</div>
      <template v-if="isContact">
        <video v-if="event.clipPath" ref="player" :key="event.clipPath" class="review-video" controls preload="metadata"
          :src="fileUrl('event', event.id, 'clip')" @error="mediaError = true" />
        <el-alert v-else title="本轮未提取此候选片段，原始候选及证据记录仍保留。" type="info" :closable="false" />
        <el-alert v-if="mediaError" title="视频资源无法读取，请核查本地片段。系统未替换或伪造画面。" type="error" :closable="false" />
        <div class="frame-toolbar">
          <el-button :disabled="!event.clipPath || mediaError" @click="seek(0)">片段起点</el-button>
          <el-button :disabled="!event.clipPath || mediaError" @click="seek(contact.clipPeakTimeMs / 1000)">定位证据峰值</el-button>
          <el-button :disabled="!event.clipPath || mediaError" @click="slowPlay">0.5倍播放</el-button>
        </div>
        <p class="muted">复核优先级不是犯规概率；证据峰值不是首次可见接触时间。</p>
        <p class="muted">{{ event.externalId }} · 源PTS {{ formatTime(contact.candidate?.peakTimeMs) }}</p>
        <p class="muted source-reference">{{ contact.source?.relativePath }}<br />{{ contact.source?.matchId }} / {{ contact.source?.partId }}</p>
        <el-select v-if="relatedEvents.length > 1" :model-value="event.id" @change="selectRelated" aria-label="同片段原始候选">
          <el-option v-for="related in relatedEvents" :key="related.id" :value="related.id" :label="related.externalId" />
        </el-select>
        <details v-if="isImagePath(event.keyframePath)">
          <summary>候选峰值原始帧</summary>
          <img class="keyframe" :src="fileUrl('event', event.id, 'keyframe')" alt="源视频候选峰值帧" />
        </details>
      </template>
      <img v-else-if="isImagePath(event.keyframePath)" class="keyframe" :src="fileUrl('event', event.id, 'keyframe')" />
      <div v-else class="keyframe keyframe-placeholder">无关键帧</div>
      <div v-if="!isContact" class="frame-toolbar">
        <el-button :icon="ArrowLeft">上一帧</el-button>
        <el-button :icon="VideoPlay">慢放</el-button>
        <el-button :icon="ArrowRight">下一帧</el-button>
      </div>
      <div class="evidence-summary">
        <div>
          <span>时间点</span>
          <strong>{{ formatTime(event.timestampMs) }}</strong>
        </div>
        <div>
          <span>涉事轨迹</span>
          <strong>{{ event.involvedTrackIds || '-' }}</strong>
        </div>
        <div>
          <span>{{ isContact ? '复核优先级' : '置信度' }}</span>
          <strong>{{ confidenceText }}</strong>
        </div>
      </div>

      <details v-if="isContact" class="contact-quality">
        <summary>轨迹与证据质量限制</summary>
        <p>图像平面近似；轨迹ID未验证为同一真实人物。以下区间仅表示局部运动样本支持。</p>
        <p v-for="flag in contact.candidate?.qualityFlags || []" :key="flag">{{ qualityLabel(flag) }}</p>
        <p v-for="person in contact.candidate?.trackParticipants || []" :key="person.trackKey">
          {{ person.trackKey }}<br />
          {{ person.localMotionSupportRanges.length ? person.localMotionSupportRanges.map(r => `${formatTime(r.startTimeMs)}—${formatTime(r.endTimeMs)}`).join('；') : '没有可用连续运动区间' }}
        </p>
      </details>

      <div v-if="trackBoxes.length" class="evidence-section">
        <div class="section-head">
          <span>轨迹框</span>
          <el-tag size="small" type="info">{{ trackBoxes.length }} 个目标</el-tag>
        </div>
        <div class="track-box-list">
          <div v-for="box in trackBoxes" :key="box.trackId" class="track-box-row">
            <div>
              <strong>ID {{ box.trackId }}</strong>
              <span>{{ labelForBox(box) }}</span>
            </div>
            <div>{{ boxText(box.box) }}</div>
            <el-progress :percentage="percent(box.confidence)" :show-text="false" />
          </div>
        </div>
      </div>
    </div>

    <div class="event-explain">
      <div class="event-heading">
        <div>
          <div class="panel-title">{{ eventTypeLabel(event.eventType) }}</div>
          <p class="muted">{{ event.eventType }} / {{ event.riskLevel }}</p>
        </div>
        <el-tag :type="riskTag(event.riskLevel)" size="large">{{ riskLabel(event.riskLevel) }}</el-tag>
      </div>
      <p class="muted">{{ event.summary }}</p>

      <div class="metric-groups">
        <div v-for="group in metricGroups" :key="group.key" class="evidence-section">
          <div class="section-head">
            <span>{{ group.title }}</span>
            <el-tag size="small" :type="group.tagType">{{ group.metrics.length }}</el-tag>
          </div>
          <div v-for="metric in group.metrics" :key="metric.name" class="metric-card-row">
            <div class="metric-copy">
              <span>{{ metricLabel(metric.name) }}</span>
              <small>{{ metric.description || metric.name }}</small>
              <small v-if="metric.value === null">{{ unavailableLabel(metric.unavailableReason) }}</small>
            </div>
            <div class="metric-value">
              <strong>{{ metricValue(metric) }}</strong>
              <el-progress
                v-if="isScoreMetric(metric)"
                :percentage="percent(metric.value)"
                :show-text="false"
                :stroke-width="7"
              />
            </div>
          </div>
        </div>
        <el-empty v-if="!metrics.length" description="暂无证据指标" :image-size="72" />
      </div>

      <el-divider />
      <div class="panel-title">裁判复核</div>
      <el-form label-position="top">
        <el-form-item label="结论">
          <el-select v-model="review.result" style="width: 100%">
            <template v-if="isContact">
              <el-option label="可见身体接触" value="VISIBLE_CONTACT" />
              <el-option label="未见身体接触" value="NO_VISIBLE_CONTACT" />
              <el-option label="画面不足以判断" value="INSUFFICIENT_VIEW" />
            </template>
            <template v-else>
              <el-option label="成立" value="CONFIRMED" />
              <el-option label="不成立" value="REJECTED" />
              <el-option label="无法判断" value="UNCERTAIN" />
            </template>
          </el-select>
        </el-form-item>
        <el-form-item label="原因">
          <el-select v-model="review.reasonCode" style="width: 100%">
            <el-option label="身体接触明显" value="CONTACT_VISIBLE" />
            <el-option label="接触不足以判定" value="INSUFFICIENT_CONTACT" />
            <el-option v-if="!isContact" label="手臂区域接触球" value="UPPER_LIMB_CONTACT" />
            <el-option v-if="!isContact" label="位置关系需复核" value="POSITION_REVIEW" />
            <el-option v-if="isContact" label="未见接触／正常接近" value="NO_CONTACT_VISIBLE" />
            <el-option v-if="isContact" label="同人重复框" value="DUPLICATE_TRACKS" />
            <el-option v-if="isContact" label="轨迹身份混淆" value="TRACK_IDENTITY_AMBIGUOUS" />
            <el-option label="画面证据不足" value="INSUFFICIENT_EVIDENCE" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="review.note" type="textarea" :rows="3" />
        </el-form-item>
        <el-button type="primary" :loading="saving" @click="submitReview">保存复核结论</el-button>
      </el-form>
      <div v-if="event.reviews?.length" class="evidence-section">
        <div class="panel-title">已保存的复核记录</div>
        <p v-for="record in event.reviews" :key="record.id">
          第{{ record.revision }}版 · {{ resultLabel(record.result) }} · {{ record.reviewer }}<br />{{ record.note }}
        </p>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { ArrowLeft, ArrowRight, VideoPlay } from '@element-plus/icons-vue'
import { api, fileUrl } from '../api/client'

const props = defineProps({ event: { type: Object, required: true }, relatedEvents: { type: Array, default: () => [] } })
const emit = defineEmits(['reviewed', 'select-event'])
const player = ref(null)
const mediaError = ref(false)
const saving = ref(false)
const isContact = computed(() => props.event.eventType === 'CONTACT')
const contact = computed(() => JSON.parse(props.event.contactMetadataJson || '{}'))

const review = reactive({
  result: 'UNCERTAIN',
  reasonCode: 'INSUFFICIENT_EVIDENCE',
  note: ''
})

const metrics = computed(() => {
  try {
    const parsed = JSON.parse(props.event.evidenceJson || '[]')
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return []
  }
})

const trackBoxes = computed(() => {
  try {
    const parsed = JSON.parse(props.event.trackBoxesJson || '[]')
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return []
  }
})

const confidenceText = computed(() => {
  if (props.event.confidence === undefined || props.event.confidence === null) return '-'
  if (isContact.value) return Number(props.event.confidence).toFixed(3)
  return `${(Number(props.event.confidence) * 100).toFixed(1)}%`
})

const metricGroups = computed(() => {
  const groups = [
    { key: 'rule', title: '轨迹规则证据', tagType: 'primary', names: ['min_distance', 'proximity', 'relative_velocity_delta', 'closing_speed', 'contact_window'], metrics: [] },
    { key: 'pose', title: '局部姿态证据', tagType: 'success', names: ['pose_anomaly', 'box_overlap_ratio', 'limb_extension', 'body_axis_delta'], metrics: [] },
    { key: 'other', title: '其他候选线索', tagType: 'info', names: [], metrics: [] }
  ]

  for (const metric of metrics.value) {
    const target = groups.find((group) => group.names.includes(metric.name)) || groups[2]
    target.metrics.push(metric)
  }
  return groups.filter((group) => group.metrics.length)
})

watch(
  () => props.event,
  () => {
    const latest = props.event.reviews?.[0]
    review.result = latest?.result || (isContact.value ? 'INSUFFICIENT_VIEW' : 'UNCERTAIN')
    review.reasonCode = latest?.reasonCode || 'INSUFFICIENT_EVIDENCE'
    review.note = latest?.note || ''
  }, { immediate: true }
)
watch(() => props.event.clipPath, () => { mediaError.value = false })

async function submitReview() {
  saving.value = true
  try {
    await api.post(`/events/${props.event.id}/review`, review)
    ElMessage.success('复核记录已保存')
    emit('reviewed')
  } catch {
    ElMessage.error('保存失败，复核结论尚未提交')
  } finally { saving.value = false }
}

function seek(seconds) { if (player.value) { player.value.pause(); player.value.currentTime = seconds } }
function slowPlay() { if (player.value) { player.value.playbackRate = 0.5; player.value.play().catch(() => { mediaError.value = true }) } }
function selectRelated(id) { emit('select-event', props.relatedEvents.find(e => e.id === id)) }
function resultLabel(value) { return { VISIBLE_CONTACT: '可见身体接触', NO_VISIBLE_CONTACT: '未见身体接触', INSUFFICIENT_VIEW: '画面不足以判断', CONFIRMED: '成立', REJECTED: '不成立', UNCERTAIN: '无法判断' }[value] || value }
function qualityLabel(value) {
  return { TRACK_IDENTITY_UNVERIFIED: '轨迹身份未经人工验证', IMAGE_PLANE_APPROXIMATION: '图像平面尺度近似',
    LOW_CONFIDENCE_FOR_MOTION: '检测置信度不足，运动特征受限', OBSERVATION_GAP: '轨迹存在缺测',
    POSSIBLE_DUPLICATE_PERSON_TRACKS: '疑似同人重复轨迹', SUSPECTED_ID_OR_POSITION_JUMP: '疑似ID或位置跳变',
    SHARED_ANCHOR_EVENT_MERGE_NOT_IDENTITY_STITCH: '共享轨迹的邻近证据已合并，未拼接人物身份',
    EVIDENCE_GAPS_NOT_INTERPOLATED: '缺失的证据未补插', PERSPECTIVE_SCALE_MISMATCH: '两人图像尺度差异较大',
    FILTER_DETECTION_DISAGREEMENT: '跟踪框与检测框偏差较大', SMALL_BOX: '远处小目标', SCALE_JUMP: '框尺度突变' }[value] || value
}
function unavailableLabel(reason = '') {
  if (reason.startsWith('QUALITY_BLOCKED:')) return reason.slice(16).split(',').map(qualityLabel).join('；')
  return { INSUFFICIENT_CONTIGUOUS_SAMPLES: '连续轨迹样本不足', INSUFFICIENT_VELOCITY_CHANGE_WINDOW: '速度变化前后窗口不足',
    INSUFFICIENT_BEFORE_AFTER_SPAN: '前后窗口时间跨度不足', COINCIDENT_REPRESENTATIVE_POINTS: '代表点重合，接近方向不可计算' }[reason] || reason
}

function formatTime(ms) {
  return `${(Number(ms || 0) / 1000).toFixed(isContact.value ? 3 : 2)}s`
}

function isImagePath(path = '') {
  return /\.(png|jpe?g|webp|gif)$/i.test(path)
}

function percent(value) {
  const numeric = Number(value || 0)
  const normalized = numeric <= 1 ? numeric * 100 : numeric
  return Math.max(0, Math.min(100, Math.round(normalized)))
}

function isScoreMetric(metric) {
  return metric.value !== null && metric.unit === 'score'
}

function metricValue(metric) {
  if (metric.value === null || metric.value === undefined) return '不可计算'
  const value = Number(metric.value)
  const display = Number.isFinite(value) ? Number(value.toFixed(3)).toString() : metric.value
  return `${display}${metric.unit ? ` ${metric.unit}` : ''}`
}

function metricLabel(name) {
  return {
    min_distance: '最小距离',
    proximity: '接近程度',
    relative_velocity_delta: '相对速度突变',
    closing_speed: '闭合速度',
    contact_window: '接触窗口',
    pose_anomaly: '姿态异常',
    box_overlap_ratio: '局部重叠',
    limb_extension: '肢体伸展',
    body_axis_delta: '身体轴线差',
    ball_upper_limb_distance: '球到上肢距离',
    ball_direction_change: '球路变化',
    line_margin: '越位线边距',
    calibration_confidence: '标定可信度'
  }[name] || name
}

function eventTypeLabel(type) {
  return {
    FOUL: '犯规候选',
    HANDBALL: '手球候选',
    OFFSIDE: '越位候选',
    CONTACT: '身体接触候选'
  }[type] || type
}

function riskLabel(risk) {
  return {
    HIGH: '高风险',
    MEDIUM: '中风险',
    LOW: '低风险',
    UNCERTAIN: '不确定'
  }[risk] || risk
}

function riskTag(risk) {
  if (risk === 'HIGH') return 'danger'
  if (risk === 'MEDIUM') return 'warning'
  if (risk === 'LOW') return 'success'
  return 'info'
}

function labelForBox(box) {
  const label = box.label === 'player' ? '球员' : box.label || '目标'
  return box.team ? `${label} / ${box.team}` : label
}

function boxText(box = []) {
  if (!Array.isArray(box) || box.length < 4) return '-'
  return box.map((value) => Math.round(Number(value))).join(', ')
}
</script>

<style scoped>
.review-video { width: 100%; aspect-ratio: 16 / 9; background: #000; }
.contact-quality { overflow-wrap: anywhere; margin-top: 16px; }
.source-reference { overflow-wrap: anywhere; }
</style>
