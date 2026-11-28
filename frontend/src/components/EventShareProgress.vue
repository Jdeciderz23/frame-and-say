<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { AlbumRequestError, albumError, type AlbumPhoto } from '../api/album'
import { purposes, readShareProgress, type PhotoProgress, type Purpose } from '../api/consent'
import { confirmSelection } from '../api/confirmedSelections'

const props = defineProps<{ eventId: string; photos: AlbumPhoto[]; token: string }>()
const emit = defineEmits<{ refresh: []; selectionsChanged: [] }>()
const purpose = defineModel<Purpose>('purpose', { required: true })
const requestCount = ref(0)
const loading = ref(false)
const forbidden = ref(false)
const error = ref('')
const progress = ref<PhotoProgress[] | null>(null)
const hasLoaded = ref(false)
const selected = ref<string[]>([])
const saving = ref(false)
const message = ref('')
const saveError = ref('')
let submission: AbortController | undefined
onBeforeUnmount(() => submission?.abort())

const currentPhotos = computed(() => {
  const result = progress.value
  if (!result || result.length !== props.photos.length) return null
  const byPhoto = new Map(result.map(item => [item.photoId, item]))
  const rows = []
  for (const [index, photo] of props.photos.entries()) {
    const item = byPhoto.get(photo.photoId)
    if (!item || item.photoVersionId !== photo.photoVersionId) return null
    rows.push({ ...item, subjectRevision: photo.subjectRevision, number: index + 1,
      key: `${photo.photoVersionId}:${photo.subjectRevision}` })
  }
  return rows
})

const chosen = computed(() => currentPhotos.value?.filter(photo => photo.eligible && selected.value.includes(photo.key)) ?? [])

async function confirm() {
  if (saving.value || !chosen.value.length) return
  const controller = new AbortController()
  submission = controller
  saving.value = true
  saveError.value = ''
  message.value = ''
  try {
    const saved = await confirmSelection(props.eventId, props.token, purpose.value, chosen.value, controller.signal)
    if (controller.signal.aborted) return
    message.value = `已确认清单 ${saved.selectionId}，共 ${saved.items.length} 张照片。`
    selected.value = []
    emit('selectionsChanged')
  } catch (cause) {
    if (controller.signal.aborted) return
    if (cause instanceof AlbumRequestError && [404, 409].includes(cause.status)) {
      saveError.value = '照片版本、名单或许可已变化。请刷新相册后重新核对。'
    } else if (!(cause instanceof AlbumRequestError) || cause.status >= 500) {
      saveError.value = '未能确认保存结果。请先核对已确认清单，再决定是否重新提交。'
      emit('selectionsChanged')
    } else {
      saveError.value = albumError(cause)
    }
    selected.value = []
  } finally {
    if (!controller.signal.aborted) saving.value = false
  }
}

watch([purpose, requestCount], async ([currentPurpose], _, cleanup) => {
  const controller = new AbortController()
  cleanup(() => controller.abort())
  loading.value = true
  selected.value = []
  saveError.value = ''
  message.value = ''
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
    <label for="progress-purpose">分享用途</label>
    <select id="progress-purpose" v-model="purpose" :disabled="saving">
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
      <form v-else @submit.prevent="confirm">
        <fieldset :disabled="saving">
          <legend>选择要确认的照片版本</legend>
          <ul>
            <li v-for="photo in currentPhotos" :key="photo.photoVersionId">
              <label v-if="photo.eligible">
                <input v-model="selected" type="checkbox" :value="photo.key" />
                照片 {{ photo.number }} · 版本 {{ photo.photoVersionId }} · 名单修订 {{ photo.subjectRevision }}
              </label>
              <span v-else>照片 {{ photo.number }}</span>
              <strong :class="{ success: photo.eligible }">{{ photo.eligible ? '符合分享条件' : '需处理' }}</strong>
            </li>
          </ul>
        </fieldset>
        <button class="button primary" type="submit" :disabled="saving || !chosen.length">
          {{ saving ? '正在确认…' : `确认最终清单（${chosen.length} 张）` }}
        </button>
        <p v-if="message" role="status">{{ message }}</p>
        <div v-if="saveError">
          <p role="alert" class="error-message">{{ saveError }}</p>
          <button class="button secondary" type="button" @click="emit('refresh')">刷新相册</button>
        </div>
      </form>
    </template>
  </section>
</template>

<style scoped>
.progress { display: grid; gap: 8px; margin: 0 0 18px; padding-bottom: 16px; border-bottom: 1px solid var(--line); }
.progress select, .progress button { justify-self: start; }
.progress ul { display: grid; gap: 6px; list-style: none; padding: 0; margin: 4px 0 0; }
.progress li { display: flex; flex-wrap: wrap; gap: 4px 12px; justify-content: space-between; }
.progress fieldset { min-width: 0; margin: 0 0 10px; padding: 8px; border: 1px solid var(--line); }
.progress li label { cursor: pointer; overflow-wrap: anywhere; }
</style>
