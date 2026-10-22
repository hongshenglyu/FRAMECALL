<template>
  <div class="detail-layout">
    <div class="event-visual">
      <div class="panel-title">事件详情</div>
      <img v-if="isImagePath(event.keyframePath)" class="keyframe" :src="fileUrl(event.keyframePath)" />
      <div v-else class="keyframe keyframe-placeholder">无关键帧</div>
      <div class="frame-toolbar">
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
          <span>置信度</span>
          <strong>{{ confidenceText }}</strong>
        </div>
      </div>

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
            <el-option label="成立" value="CONFIRMED" />
            <el-option label="不成立" value="REJECTED" />
            <el-option label="无法判断" value="UNCERTAIN" />
          </el-select>
        </el-form-item>
        <el-form-item label="原因">
          <el-select v-model="review.reasonCode" style="width: 100%">
            <el-option label="身体接触明显" value="CONTACT_VISIBLE" />
            <el-option label="接触不足以判定" value="INSUFFICIENT_CONTACT" />
            <el-option label="手臂区域接触球" value="UPPER_LIMB_CONTACT" />
            <el-option label="位置关系需复核" value="POSITION_REVIEW" />
            <el-option label="画面证据不足" value="INSUFFICIENT_EVIDENCE" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="review.note" type="textarea" :rows="3" />
        </el-form-item>
        <el-button type="primary" @click="submitReview">保存复核结论</el-button>
      </el-form>
    </div>
  </div>
</template>

<script setup>
import { computed, reactive, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { ArrowLeft, ArrowRight, VideoPlay } from '@element-plus/icons-vue'
import { api, fileUrl } from '../api/client'

const props = defineProps({ event: { type: Object, required: true } })
const emit = defineEmits(['reviewed'])

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
  () => props.event.id,
  () => {
    review.result = 'UNCERTAIN'
    review.reasonCode = 'INSUFFICIENT_EVIDENCE'
    review.note = ''
  }
)

async function submitReview() {
  await api.post(`/events/${props.event.id}/review`, review)
  ElMessage.success('复核记录已保存')
  emit('reviewed')
}

function formatTime(ms) {
  return `${(Number(ms || 0) / 1000).toFixed(2)}s`
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
  return metric.unit === 'score' || Number(metric.value) <= 1
}

function metricValue(metric) {
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
    OFFSIDE: '越位候选'
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
