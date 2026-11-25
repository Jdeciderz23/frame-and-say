<script setup lang="ts">
import { onBeforeUnmount, shallowRef, ref, watch } from 'vue'
import { AlbumRequestError, photoUploadError, uploadPhoto } from '../api/album'

const props = defineProps<{ eventId: string; token: string }>()
const emit = defineEmits<{ uploaded: []; refresh: [] }>()
const maxBytes = 8 * 1024 * 1024
const input = ref<HTMLInputElement>()
const selected = shallowRef<File>()
const busy = ref(false)
const error = ref('')
const uploaded = ref(false)
const resultUnknown = ref(false)
let request: AbortController | undefined

function chooseFile(event: Event) {
  selected.value = (event.target as HTMLInputElement).files?.[0]
  error.value = ''
  uploaded.value = false
  resultUnknown.value = false
}

function clearSelection() {
  selected.value = undefined
  if (input.value) input.value.value = ''
}

function reset() {
  request?.abort()
  busy.value = false
  error.value = ''
  uploaded.value = false
  resultUnknown.value = false
  clearSelection()
}

watch(() => [props.eventId, props.token], reset, { flush: 'sync' })
onBeforeUnmount(() => request?.abort())

async function submit() {
  const file = selected.value
  if (!file || busy.value) return
  error.value = ''
  resultUnknown.value = false
  uploaded.value = false
  if (file.size === 0) {
    error.value = '所选文件为空，请重新选择。'
    return
  }
  if (file.size > maxBytes) {
    error.value = '单张照片最多 8 MiB，请缩小后再上传。'
    return
  }

  const controller = new AbortController()
  request = controller
  busy.value = true
  try {
    await uploadPhoto(props.eventId, file, props.token, controller.signal)
    if (controller.signal.aborted) return
    clearSelection()
    uploaded.value = true
    emit('uploaded')
  } catch (cause) {
    if (controller.signal.aborted) return
    error.value = photoUploadError(cause)
    resultUnknown.value = !(cause instanceof AlbumRequestError)
  } finally {
    if (request === controller) {
      request = undefined
      busy.value = false
    }
  }
}
</script>

<template>
  <form class="photo-upload" @submit.prevent="submit">
    <label for="album-upload">选择照片</label>
    <p id="album-upload-hint" class="muted">JPEG 或 PNG，单张最多 8 MiB。</p>
    <div class="upload-actions">
      <input id="album-upload" ref="input" type="file" accept="image/jpeg,image/png,.jpg,.jpeg,.png"
        aria-describedby="album-upload-hint" :disabled="busy" @change="chooseFile" />
      <button class="button primary" type="submit" :disabled="busy || !selected">
        {{ busy ? '正在上传…' : '上传照片' }}
      </button>
    </div>
    <p v-if="busy" role="status">正在上传所选照片。</p>
    <p v-else-if="uploaded" role="status">照片已上传。</p>
    <p v-else-if="error" class="error-message" role="alert">{{ error }}</p>
    <button v-if="resultUnknown" class="button upload-refresh" type="button" @click="emit('refresh')">
      刷新照片列表
    </button>
  </form>
</template>

<style scoped>
.photo-upload {
  display: grid;
  gap: 8px;
  margin-bottom: 16px;
  padding: 16px;
  border: 1px solid var(--line);
  border-radius: 9px;
  background: #fcfcf9;
}

.photo-upload label { font-weight: 650; }
.photo-upload p { font-size: .875rem; }
.photo-upload .error-message { margin: 0; }
.upload-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
.upload-actions input { flex: 1 1 16rem; min-width: 0; max-width: 100%; font: inherit; }
.upload-actions input::file-selector-button { min-height: 44px; cursor: pointer; }
.upload-actions button:disabled { cursor: default; }
.upload-refresh { justify-self: start; color: var(--accent); background: #e8eddc; }
</style>
