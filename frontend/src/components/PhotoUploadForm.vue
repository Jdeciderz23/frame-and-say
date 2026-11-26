<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { uploadPhoto } from '../api/album'
import { usePhotoUpload } from './usePhotoUpload'

const props = defineProps<{ eventId: string; token: string; refreshPhotos: () => Promise<boolean> }>()
const emit = defineEmits<{ uploaded: [] }>()
const input = ref<HTMLInputElement>()
const { state, error, canSelect, canSubmit, chooseFile, submit, checkPhotos, finishCheck, reset } = usePhotoUpload(
  (file, signal) => uploadPhoto(props.eventId, file, props.token, signal),
  () => props.refreshPhotos(),
)

function clearInput() {
  if (input.value) input.value.value = ''
}

watch(() => [props.eventId, props.token], () => {
  reset()
  clearInput()
}, { flush: 'sync' })
onBeforeUnmount(reset)

async function submitForm() {
  if (!await submit()) return
  clearInput()
  emit('uploaded')
}

async function resolveCheck(chooseAgain: boolean) {
  if (!finishCheck()) return
  clearInput()
  if (chooseAgain) {
    await nextTick()
    input.value?.focus()
  }
}
</script>

<template>
  <form class="photo-upload" @submit.prevent="submitForm">
    <label for="album-upload">选择照片</label>
    <p id="album-upload-hint" class="muted">JPEG 或 PNG，单张最多 8 MiB。</p>
    <div class="upload-actions">
      <input id="album-upload" ref="input" type="file" accept="image/jpeg,image/png,.jpg,.jpeg,.png"
        aria-describedby="album-upload-hint" :disabled="!canSelect"
        @change="chooseFile(($event.target as HTMLInputElement).files?.[0])" />
      <button class="button primary" type="submit" :disabled="!canSubmit">
        {{ state === 'uploading' ? '正在上传…' : '上传照片' }}
      </button>
    </div>
    <p v-if="state === 'uploading'" role="status">正在上传所选照片。</p>
    <p v-else-if="state === 'uploaded'" role="status">照片已上传。</p>
    <p v-else-if="state === 'checking'" role="status">正在刷新照片列表…</p>
    <p v-else-if="state === 'checked'" role="status">列表已刷新。请核对这张照片是否已经入册。</p>
    <p v-else-if="error" class="error-message" role="alert">{{ error }}</p>
    <button v-if="state === 'needs-check'" class="button upload-refresh" type="button" @click="checkPhotos">
      刷新照片列表
    </button>
    <div v-if="state === 'checked'" class="upload-actions">
      <button class="button upload-refresh" type="button" @click="resolveCheck(false)">已找到照片，结束核对</button>
      <button class="button upload-refresh" type="button" @click="resolveCheck(true)">未找到照片，重新选择</button>
      <button class="button upload-refresh" type="button" @click="checkPhotos">再次刷新</button>
    </div>
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
