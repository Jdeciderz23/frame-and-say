<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { AlbumRequestError, albumError, type AlbumPhoto } from '../api/album'
import { purposes, readShareProgress, type PhotoProgress, type Purpose } from '../api/consent'

const props = defineProps<{ eventId: string; photos: AlbumPhoto[]; token: string }>()
const emit = defineEmits<{ refresh: [] }>()
const purpose = defineModel<Purpose>('purpose', { required: true })
const requestCount = ref(0)
const loading = ref(false)
const forbidden = ref(false)
const error = ref('')
const progress = ref<PhotoProgress[] | null>(null)
const hasLoaded = ref(false)

const currentPhotos = computed(() => {
  const result = progress.value
  if (!result || result.length !== props.photos.length) return null
  const byPhoto = new Map(result.map(item => [item.photoId, item]))
  const rows = []
  for (const [index, photo] of props.photos.entries()) {
    const item = byPhoto.get(photo.photoId)
    if (!item || item.photoVersionId !== photo.photoVersionId) return null
    rows.push({ ...item, number: index + 1 })
  }
  return rows
})

watch([purpose, requestCount], async ([currentPurpose], _, cleanup) => {
  const controller = new AbortController()
  cleanup(() => controller.abort())
  loading.value = true
  error.value = ''
  progress.value = null
  try {
    const result = await readShareProgress(props.eventId, props.token, currentPurpose, controller.signal)
    if (!controller.signal.aborted) {
      progress.value = result
      hasLoaded.value = true
    }
  } catch (cause) {
    if (controller.signal.aborted) return
    if (cause instanceof AlbumRequestError && cause.status === 403) forbidden.value = true
    else error.value = albumError(cause)
  } finally {
    if (!controller.signal.aborted) loading.value = false
  }
}, { immediate: true })
</script>

<template>
  <section v-if="!forbidden && (hasLoaded || error)" class="progress" aria-labelledby="progress-heading">
    <h3 id="progress-heading">分享确认进度</h3>
    <label for="progress-purpose">查看用途</label>
    <select id="progress-purpose" v-model="purpose">
      <option v-for="item in purposes" :key="item.value" :value="item.value">{{ item.label }}</option>
    </select>
    <p v-if="loading" role="status">正在读取分享进度…</p>
    <template v-else-if="error">
      <p class="error-message" role="alert">{{ error }}</p>
      <button class="button secondary" type="button" @click="requestCount++">重试分享进度</button>
    </template>
    <template v-else-if="progress">
      <template v-if="currentPhotos === null">
        <p role="status">照片列表与分享进度已不同步。请刷新后查看当前照片的状态。</p>
        <button class="button secondary" type="button" @click="emit('refresh')">刷新相册和分享进度</button>
      </template>
      <p v-else-if="!currentPhotos.length">当前没有可显示的照片进度。</p>
      <ul v-else>
        <li v-for="photo in currentPhotos" :key="photo.photoVersionId">
          <span>照片 {{ photo.number }}</span>
          <strong :class="{ success: photo.eligible }">{{ photo.eligible ? '符合分享条件' : '需处理' }}</strong>
        </li>
      </ul>
    </template>
  </section>
</template>

<style scoped>
.progress { display: grid; gap: 8px; margin: 0 0 18px; padding-bottom: 16px; border-bottom: 1px solid var(--line); }
.progress select, .progress button { justify-self: start; }
.progress ul { display: grid; gap: 6px; list-style: none; padding: 0; margin: 4px 0 0; }
.progress li { display: flex; flex-wrap: wrap; gap: 4px 12px; justify-content: space-between; }
</style>
