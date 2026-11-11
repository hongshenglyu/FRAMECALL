export function submissionIssue(form) {
  const { sceneType: scene, contactVisibility: visibility, contestStartTimeMs: start, contestEndTimeMs: end, firstContactTimeMs: first } = form
  if (!scene || !visibility) return '请选择画面过程和接触可见性'
  if (!form.humanObserved) return '请实际查看素材后勾选人工确认'
  if (visibility !== 'VISIBLE_CONTACT' && first !== null) return '未见接触或无法判断时，请清空首次接触时间'
  if (scene === 'NO_CONTEST') return visibility === 'NO_VISIBLE_CONTACT' && start === null && end === null ? '' : '未见争抢时请选择未见身体接触，并清空三个时间'
  if (scene === 'UNCLEAR' && visibility !== 'INSUFFICIENT_VIEW') return '画面无法确定时请选择画面不足以判断'
  const clipNegative = scene === 'NORMAL_APPROACH' && visibility === 'NO_VISIBLE_CONTACT' && start === null && end === null
  if ((scene === 'CONTEST' || scene === 'NORMAL_APPROACH') && !clipNegative && (start === null || end === null)) return '请记录争抢／接近的开始和结束；若是整段正常接近且未见接触，可将起止都留空'
  if (start !== null && end !== null && start >= end) return '结束时间必须晚于开始时间'
  if (visibility === 'VISIBLE_CONTACT' && first === null) return '请记录首次可见身体接触的源帧'
  if (first !== null && ((start !== null && first < start) || (end !== null && first > end))) return '首次接触时间必须位于记录的过程范围内'
  return ''
}

export function restoreAnnotation(row, currentAnnotatorId) {
  const result = { annotationKey: row.annotationKey, expectedRevision: row.revisionHead ?? row.revision, recordType: 'HUMAN',
    humanObserved: row.status === 'DRAFT' && row.annotator.id === currentAnnotatorId && row.annotation.humanObserved === true }
  for (const field of ['sceneType', 'contestStartTimeMs', 'firstContactTimeMs', 'contestEndTimeMs', 'contactVisibility', 'note']) result[field] = row.annotation[field]
  return result
}
