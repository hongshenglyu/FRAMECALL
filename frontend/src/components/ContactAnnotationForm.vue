<template>
  <section class="evidence-section contact-annotation">
    <div class="panel-title">原始接触标注</div>
    <p class="muted">由{{ data.currentAnnotator?.displayName || '当前登录账户' }}记录。所有时间初始为空；候选峰值不能代替首次接触。每次保存新增修订。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <div class="frame-toolbar">
      <el-button :disabled="!ready" @click="step(-1)">-1帧</el-button>
      <el-button :disabled="!ready" @click="step(1)">+1帧</el-button>
      <el-button @click="newAnnotation">新增另一段争抢</el-button>
    </div>
    <el-select v-model="selectedKey" placeholder="选择已有争抢以修订" clearable @change="choose" aria-label="原始标注记录">
      <el-option v-for="row in latest" :key="row.annotationKey" :value="row.annotationKey" :label="`第${row.revision}版 / ${row.status} / ${row.annotationKey.slice(0,8)}`" />
    </el-select>
    <el-form label-position="top">
      <el-form-item label="画面过程（人工选择）">
        <el-select v-model="form.sceneType" placeholder="待人工选择" clearable aria-label="画面过程">
          <el-option label="争抢" value="CONTEST" /><el-option label="正常接近" value="NORMAL_APPROACH" />
          <el-option label="未见争抢" value="NO_CONTEST" /><el-option label="画面无法确定" value="UNCLEAR" />
        </el-select>
      </el-form-item>
      <el-form-item label="接触可见性（人工选择）">
        <el-select v-model="form.contactVisibility" placeholder="待人工选择" clearable aria-label="原始接触可见性" @change="visibilityChanged">
          <el-option label="可见身体接触" value="VISIBLE_CONTACT" /><el-option label="未见身体接触" value="NO_VISIBLE_CONTACT" />
          <el-option label="画面不足以判断" value="INSUFFICIENT_VIEW" />
        </el-select>
      </el-form-item>
      <div v-for="field in timeFields" :key="field.key" class="annotation-time-row">
        <span>{{ field.label }}：{{ format(form[field.key]) }}</span>
        <el-button size="small" :disabled="!ready || (field.key === 'firstContactTimeMs' && form.contactVisibility !== 'VISIBLE_CONTACT')" @click="mark(field.key)">记录当前源帧</el-button>
        <el-button size="small" @click="form[field.key] = null">清空</el-button>
      </div>
      <p class="muted">使用实际PTS逐帧定位，未见接触时首次接触必须为空。未见争抢时无需编造争抢起止。</p>
      <el-form-item label="原始标注备注"><el-input v-model="form.note" type="textarea" :rows="2" maxlength="1000" /></el-form-item>
      <el-checkbox v-model="form.humanObserved">我已实际查看素材并核对边界与接触可见性</el-checkbox>
      <div class="frame-toolbar">
        <el-button :loading="saving" :disabled="!ready" @click="save('DRAFT')">保存未完成草稿</el-button>
        <el-button type="primary" :loading="saving" :disabled="!canSubmit" @click="save('SUBMITTED')">提交原始标注待复核</el-button>
      </div>
    </el-form>
    <details v-if="data.revisions?.length">
      <summary>全部原始修订记录（{{ data.revisions.length }}）</summary>
      <p v-for="row in data.revisions" :key="row.id">{{ row.annotationKey.slice(0,8) }} · 第{{ row.revision }}版 · {{ row.status }} · {{ row.annotator.displayName }} · {{ row.reviewStatus }}</p>
    </details>
  </section>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../api/client'

const props = defineProps({ eventId: Number, readTime: Function, reviewCount: Number })
const emit = defineEmits(['seek', 'revisions', 'saved'])
const data = ref({ frames: [], revisions: [] })
const saving = ref(false), error = ref(''), selectedKey = ref(null)
const blank = () => ({ annotationKey: null, expectedRevision: 0, recordType: 'HUMAN', sceneType: null,
  contestStartTimeMs: null, firstContactTimeMs: null, contestEndTimeMs: null, contactVisibility: null, humanObserved: false, note: '' })
const form = reactive(blank())
const timeFields = [{ key: 'contestStartTimeMs', label: '争抢／接近开始' }, { key: 'firstContactTimeMs', label: '首次可见接触' }, { key: 'contestEndTimeMs', label: '连续过程结束' }]
const ready = computed(() => data.value.frames.length > 0 && !error.value)
const latest = computed(() => [...new Map(data.value.revisions.slice().reverse().map(r => [r.annotationKey, r])).values()])
const canSubmit = computed(() => {
  if (!ready.value || !form.humanObserved || !form.sceneType || !form.contactVisibility) return false
  if (form.sceneType === 'NO_CONTEST') return form.contactVisibility === 'NO_VISIBLE_CONTACT' && timeFields.every(f => form[f.key] === null)
  if (form.sceneType === 'UNCLEAR') return form.contactVisibility === 'INSUFFICIENT_VIEW' && form.firstContactTimeMs === null
  if (form.contestStartTimeMs === null || form.contestEndTimeMs === null || form.contestStartTimeMs >= form.contestEndTimeMs) return false
  return form.contactVisibility === 'VISIBLE_CONTACT'
    ? form.firstContactTimeMs !== null && form.contestStartTimeMs <= form.firstContactTimeMs && form.firstContactTimeMs <= form.contestEndTimeMs
    : form.firstContactTimeMs === null
})
async function load() {
  error.value = ''
  try { data.value = (await api.get(`/events/${props.eventId}/annotations`)).data; emit('revisions', data.value.revisions) }
  catch { data.value = { frames: [], revisions: [] }; error.value = '源PTS映射或原始标注无法读取，不能记录虚构时间。' }
}
watch(() => props.eventId, async () => { newAnnotation(); await load() }, { immediate: true })
watch(() => props.reviewCount, load)
function newAnnotation() { selectedKey.value = null; Object.assign(form, blank()) }
function choose(key) {
  const row = latest.value.find(r => r.annotationKey === key)
  if (!row) { newAnnotation(); return }
  Object.assign(form, blank())
  for (const name of ['sceneType','contestStartTimeMs','firstContactTimeMs','contestEndTimeMs','contactVisibility','note']) form[name] = row.annotation[name]
  Object.assign(form, { annotationKey: key, expectedRevision: row.revision, recordType: 'HUMAN', humanObserved: false })
}
function currentIndex() {
  const current = props.readTime()
  if (current === null) return null
  const frames = data.value.frames, time = frames[0].timeMs + current
  let index = 0
  for (let i = 0; i < frames.length && frames[i].timeMs <= time + 0.1; i++) index = i
  return index
}
function mark(field) {
  const index = currentIndex()
  if (index === null) { ElMessage.warning('请等待视频帧解码后再记录'); return }
  form[field] = data.value.frames[index].timeMs
}
function step(delta) {
  const current = currentIndex()
  if (current === null) return
  const frames = data.value.frames, index = Math.max(0, Math.min(frames.length - 1, current + delta))
  emit('seek', frames[index].timeMs - frames[0].timeMs)
}
function visibilityChanged() { if (form.contactVisibility !== 'VISIBLE_CONTACT') form.firstContactTimeMs = null }
function format(value) { return value === null ? '未记录' : `${(value / 1000).toFixed(3)}s（源PTS）` }
async function save(status) {
  saving.value = true
  try {
    const { data: row } = await api.post(`/events/${props.eventId}/annotations`, { ...form, status })
    await load(); selectedKey.value = row.annotationKey; choose(row.annotationKey)
    ElMessage.success(status === 'DRAFT' ? '草稿已保存，尚未完成标注' : '原始标注已提交，待独立复核')
    emit('saved')
  } catch (failure) { ElMessage.error(failure.response?.status === 409 ? '已有新修订，请重新载入' : '保存失败，请检查边界、首次接触时间和人工确认') }
  finally { saving.value = false }
}
</script>

<style scoped>
.annotation-time-row { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; margin: 12px 0; }
.annotation-time-row span { flex-basis: 100%; }
</style>
