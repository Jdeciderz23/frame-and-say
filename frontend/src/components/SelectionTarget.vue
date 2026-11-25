<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import { AlbumRequestError, albumError, setSelectionTarget, type AlbumEvent } from '../api/album'

const props = defineProps<{ event: AlbumEvent; token: string }>()
const emit = defineEmits<{ saved: [count: number] }>()
const draft = ref<number | ''>(props.event.desiredPhotoCount ?? '')
const session = new AbortController()
const saving = ref(false)
const error = ref('')
const message = ref('')

async function save() {
  if (saving.value || props.event.memberRole !== 'ORGANIZER') return
  message.value = ''
  error.value = ''
  const count = draft.value
  if (typeof count !== 'number' || !Number.isInteger(count) || count < 1 || count > 100) {
    error.value = '请输入 1 至 100 之间的整数。'
    return
  }
  saving.value = true
  try {
    await setSelectionTarget(props.event.id, count, props.token, session.signal)
    if (session.signal.aborted) return
    emit('saved', count)
    message.value = '选片目标已保存。'
  } catch (cause) {
    if (session.signal.aborted) return
    error.value = cause instanceof AlbumRequestError && cause.status === 400
      ? '请输入 1 至 100 之间的整数。' : albumError(cause)
  } finally {
    saving.value = false
  }
}

onBeforeUnmount(() => session.abort())
</script>

<template>
  <section class="selection-target" aria-label="活动选片目标">
    <p>当前选片目标 · <strong>{{ event.desiredPhotoCount == null ? '未设置' : event.desiredPhotoCount + ' 张' }}</strong></p>
    <form v-if="event.memberRole === 'ORGANIZER'" novalidate @submit.prevent="save">
      <label for="selection-target">调整目标（1–100 张）</label>
      <div class="target-actions">
        <input id="selection-target" v-model="draft" type="number" min="1" max="100" step="1" required
          :disabled="saving" @input="error = ''; message = ''" />
        <button type="submit" class="button primary" :disabled="saving">{{ saving ? '正在保存…' : '保存目标' }}</button>
      </div>
      <p v-if="error" role="alert" class="error-message">{{ error }}</p>
      <p v-if="message" role="status" class="success">{{ message }}</p>
    </form>
  </section>
</template>

<style scoped>
.selection-target { display: grid; gap: 12px; margin-bottom: 20px; padding: 16px; border: 1px solid var(--line); border-radius: 8px; }
.selection-target form { display: grid; gap: 8px; }
.target-actions { display: flex; flex-wrap: wrap; gap: 8px; }
.target-actions input { width: 7em; min-height: 44px; padding: 8px 12px; border: 1px solid var(--line); border-radius: 7px; font: inherit; color: inherit; }
</style>
