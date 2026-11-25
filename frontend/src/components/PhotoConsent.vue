<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { AlbumRequestError, albumError, type AlbumPhoto } from '../api/album'
import { purposes, readConsent, saveConsent, type ConsentStatus, type Decision, type MyConsent, type Purpose } from '../api/consent'

const props = defineProps<{ eventId: string; photo: AlbumPhoto; token: string }>()
const emit = defineEmits<{ refresh: [] }>()
const readAttempt = ref(0)
const purpose = ref<Purpose>('INTERNAL_SHARE')
const consent = ref<MyConsent>()
const loading = ref(false)
const saving = ref(false)
const conflict = ref<'roster' | 'photo'>()
const saved = ref(false)
const error = ref('')
const selected = ref<Decision | null>(null)
const labels: Record<ConsentStatus, string> = { PENDING: '待确认', ALLOW: '已同意', DENY: '已拒绝' }

const saveController = new AbortController()
onUnmounted(() => saveController.abort())

function handleError(cause: unknown, stale: 'roster' | 'photo') {
  if (cause instanceof AlbumRequestError && cause.status === 409) {
    conflict.value = stale
    selected.value = null
    consent.value = undefined
    error.value = '照片版本或出镜名单已更新。请刷新照片和名单状态，再重新选择授权。'
  } else {
    error.value = albumError(cause)
  }
}

function refreshAfterConflict() {
  if (conflict.value === 'photo') emit('refresh')
  else readAttempt.value++
}

watch([purpose, readAttempt], async ([currentPurpose, attempt], [, previousAttempt], cleanup) => {
  const controller = new AbortController()
  cleanup(() => controller.abort())
  loading.value = true
  consent.value = undefined
  conflict.value = undefined
  saved.value = false
  selected.value = null
  error.value = ''
  try {
    const result = await readConsent(props.eventId, props.photo, props.token, currentPurpose, controller.signal)
    if (!controller.signal.aborted) consent.value = result
  } catch (cause) {
    if (controller.signal.aborted) return
    if (attempt > 0 && attempt !== previousAttempt && cause instanceof AlbumRequestError && cause.status === 409) {
      emit('refresh')
    } else {
      handleError(cause, 'photo')
    }
  } finally {
    if (!controller.signal.aborted) loading.value = false
  }
}, { immediate: true })

async function submit() {
  const current = consent.value
  const decision = selected.value
  if (!current?.canDecide || !decision || saving.value) return
  saving.value = true
  saved.value = false
  error.value = ''
  try {
    await saveConsent(props.eventId, props.photo, props.token, current, decision, saveController.signal)
    consent.value = { ...current, decision }
    selected.value = null
    saved.value = true
  } catch (cause) {
    if (!saveController.signal.aborted) handleError(cause, 'roster')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <section class="consent" aria-labelledby="consent-heading">
    <h3 id="consent-heading">我的分享授权</h3>
    <label for="consent-purpose">分享用途</label>
    <select id="consent-purpose" v-model="purpose" :disabled="saving || Boolean(conflict)">
      <option v-for="option in purposes" :key="option.value" :value="option.value">{{ option.label }}</option>
    </select>
    <p v-if="loading" role="status">正在读取授权…</p>
    <p v-if="error" class="error-message" role="alert">{{ error }}</p>
    <button v-if="conflict" class="button primary" type="button" @click="refreshAfterConflict">刷新照片和名单状态</button>
    <button v-else-if="!consent && !loading" class="button primary" type="button" @click="readAttempt++">重试读取授权</button>
    <template v-if="consent">
      <p v-if="!consent.canDecide">仅当前确认名单中的本人可决定分享用途。</p>
      <form v-else @submit.prevent="submit" @change="saved = false">
        <p role="status">{{ saved ? '授权已保存。' : '' }}当前决定 {{ labels[consent.decision] }}。</p>
        <p class="muted">出镜名单修订 {{ consent.subjectRevision }}</p>
        <fieldset :disabled="saving">
          <legend>对此用途的决定</legend>
          <label><input v-model="selected" type="radio" name="consent" value="ALLOW" /> 同意</label>
          <label><input v-model="selected" type="radio" name="consent" value="DENY" /> 拒绝</label>
        </fieldset>
        <button class="button primary" type="submit" :disabled="saving || selected === null">
          {{ saving ? '正在保存…' : error ? '重试保存授权' : '确认授权决定' }}
        </button>
      </form>
    </template>
  </section>
</template>

<style scoped>
.consent { display: grid; gap: 8px; padding-top: 16px; border-top: 1px solid var(--line); }
.consent > button { justify-self: start; }
.consent form { display: grid; gap: 10px; justify-items: start; }
.consent fieldset { display: flex; flex-wrap: wrap; gap: 4px 12px; padding: 0; margin: 0; border: 0; }
.consent fieldset label { display: inline-flex; gap: 5px; align-items: center; min-height: 44px; }
.consent input { accent-color: var(--accent); }
</style>
