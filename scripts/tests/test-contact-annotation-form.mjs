import test from 'node:test'
import assert from 'node:assert/strict'
import { restoreAnnotation, submissionIssue } from '../../frontend/src/utils/contactAnnotationForm.mjs'

const negative = () => ({ sceneType: 'NORMAL_APPROACH', contactVisibility: 'NO_VISIBLE_CONTACT',
  contestStartTimeMs: null, firstContactTimeMs: null, contestEndTimeMs: null, humanObserved: true, note: '' })

test('a saved confirmed no-contact draft restores and can submit without invented event bounds', () => {
  const row = { annotationKey: 'fixture', revision: 2, revisionHead: 3, status: 'DRAFT', annotator: { id: 7 }, annotation: negative() }
  const form = restoreAnnotation(row, 7)
  assert.equal(form.expectedRevision, 3)
  assert.equal(form.humanObserved, true)
  assert.equal(submissionIssue(form), '')
  assert.equal(form.firstContactTimeMs, null)
  assert.equal(restoreAnnotation(row, 8).humanObserved, false)
  assert.equal(restoreAnnotation({ ...row, status: 'SUBMITTED' }, 7).humanObserved, false)
  assert.equal(restoreAnnotation({ ...row, annotation: { ...negative(), humanObserved: false } }, 7).humanObserved, false)
})

test('real contests need boundaries and visible contact needs a first source frame', () => {
  const form = negative()
  form.sceneType = 'CONTEST'
  assert.match(submissionIssue(form), /开始和结束/)
  form.contestStartTimeMs = 1000; form.contestEndTimeMs = 2000
  assert.equal(submissionIssue(form), '')
  form.contactVisibility = 'VISIBLE_CONTACT'
  assert.match(submissionIssue(form), /首次可见/)
  form.firstContactTimeMs = 1500
  assert.equal(submissionIssue(form), '')
  form.firstContactTimeMs = 2100
  assert.match(submissionIssue(form), /范围内/)
})

test('incomplete negatives and missing human confirmation explain why submission is blocked', () => {
  assert.match(submissionIssue({ ...negative(), contestStartTimeMs: 1000 }), /开始和结束/)
  assert.match(submissionIssue({ ...negative(), humanObserved: false }), /人工确认/)
  assert.match(submissionIssue({ ...negative(), firstContactTimeMs: 1000 }), /清空首次/)
  assert.equal(submissionIssue({ ...negative(), sceneType: 'NO_CONTEST' }), '')
})
