<script setup lang="ts">
import { computed, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import { AlbumRequestError, albumError, photoUploadOutcomeUnknown, type AlbumPhoto } from '../api/album'
import { listVersions, saveVersion, type PhotoVersionHistory } from '../api/photoVersions'
import PhotoThumbnail from './PhotoThumbnail.vue'

const props = defineProps<{ eventId: string; photo: AlbumPhoto; token: string; alt: string; selectedVersionId: string }>()
const emit = defineEmits<{ currentVersion: [photo: AlbumPhoto]; selectVersion: [versionId: string] }>()
const history = shallowRef<PhotoVersionHistory>()
const latest = computed(() => history.value?.versions[0])
const selected = computed(() => props.selectedVersionId
  ? history.value?.versions.find(item => item.photo.photoVersionId === props.selectedVersionId)
  : latest.value)
const input = ref<HTMLInputElement>()
const retry = ref(0)
const loading = ref(false)
const historyError = ref('')
const saving = ref(false)
const saveError = ref('')
const saved = ref('')
const refreshNeeded = ref(false)
let pending: AbortController | undefined

watch([() => props.eventId, () => props.photo.photoId, () => props.token, retry],
  async ([eventId, photoId, token], _, cleanup) => {
    const controller = new AbortController()
    cleanup(() => { controller.abort(); pending?.abort() })
    history.value = undefined
    historyError.value = ''
    loading.value = true
    try {
      const result = await listVersions(eventId, photoId, token, controller.signal)
      if (controller.signal.aborted) return
      history.value = result
      const current = result.versions[0]
      if (refreshNeeded.value && current) {
        emit('selectVersion', current.photo.photoVersionId)
        saveError.value = ''
        saved.value = '版本列表已刷新。请核对当前照片。'
      }
      refreshNeeded.value = false
      if (current && current.photo.photoVersionId !== props.photo.photoVersionId) {
        emit('currentVersion', current.photo)
      }
    } catch (error) {
      if (!controller.signal.aborted) historyError.value = albumError(error)
    } finally {
      if (!controller.signal.aborted) loading.value = false
    }
  }, { immediate: true })

onBeforeUnmount(() => pending?.abort())

async function submit() {
  const file = input.value?.files?.[0]
  const snapshot = history.value
  const base = latest.value
  if (!file || !snapshot?.canCreateVersion || !base || saving.value || refreshNeeded.value || selected.value !== base) return
  saved.value = ''
  saveError.value = ''
  if (!file.size || file.size > 8 * 1024 * 1024) {
    saveError.value = '请选择非空的 JPEG 或 PNG，单张最多 8 MiB。'
    return
  }
  const controller = new AbortController()
  pending = controller
  saving.value = true
  try {
    const version = await saveVersion(props.eventId, props.photo.photoId, base.photo.photoVersionId,
      file, props.token, controller.signal)
    if (controller.signal.aborted) return
    history.value = { ...snapshot, versions: [version, ...snapshot.versions] }
    emit('selectVersion', version.photo.photoVersionId)
    if (input.value) input.value.value = ''
    saved.value = `已保存版本 ${version.versionNumber}。出镜名单和分享授权需要重新确认。`
    emit('currentVersion', version.photo)
  } catch (error) {
    if (controller.signal.aborted) return
    if (photoUploadOutcomeUnknown(error) || (error instanceof AlbumRequestError && error.status === 409)) {
      refreshNeeded.value = true
      saveError.value = error instanceof AlbumRequestError && error.status === 409
        ? '当前版本已更新。请刷新版本并确认最新照片，再重新选择文件。'
        : '无法确认保存结果。请先刷新版本列表，再决定是否重试。'
    } else if (error instanceof AlbumRequestError && error.status === 415) {
      saveError.value = '所选文件不是可解码的 JPEG 或 PNG。'
    } else if (error instanceof AlbumRequestError && error.status === 413) {
      saveError.value = '照片超出文件大小或像素限制。'
    } else {
      saveError.value = albumError(error)
    }
  } finally {
    if (pending === controller) {
      pending = undefined
      saving.value = false
    }
  }
}
</script>

<template>
  <div class="versions">
    <p v-if="loading" role="status">正在读取照片版本…</p>
    <div v-else-if="historyError">
      <p class="error-message" role="alert">{{ historyError }}</p>
      <button class="button secondary" type="button" @click="retry++">重试版本列表</button>
    </div>
    <template v-if="latest">
      <label for="photo-version">照片版本</label>
      <select id="photo-version" :value="selected?.photo.photoVersionId ?? ''" :disabled="saving"
        @change="emit('selectVersion', ($event.target as HTMLSelectElement).value)">
        <option v-if="!selected" value="" disabled>链接中的版本不可用</option>
        <option v-for="item in history?.versions" :key="item.photo.photoVersionId" :value="item.photo.photoVersionId">
          版本 {{ item.versionNumber }}{{ item === latest ? '（当前）' : '' }}
        </option>
      </select>
      <p v-if="selected === latest && !latest.photo.rosterConfirmed" class="muted">当前版本的出镜名单尚未确认。</p>
    </template>
    <p v-if="history && selectedVersionId && !selected" role="status">该照片没有链接中指定的版本。</p>
    <PhotoThumbnail v-if="selected || !selectedVersionId" :event-id="eventId" :photo="selected?.photo ?? photo" :token="token"
      :alt="selected ? `版本 ${selected.versionNumber} 缩略图` : alt" />

    <form v-if="history?.canCreateVersion && latest && selected === latest && !refreshNeeded" class="save-version" @submit.prevent="submit">
      <label for="version-file">修改后的照片</label>
      <p id="version-file-hint" class="muted">选择 JPEG 或 PNG，最多 8 MiB。保存后保留原有版本。</p>
      <input id="version-file" ref="input" type="file" required :disabled="saving"
        accept="image/jpeg,image/png,.jpg,.jpeg,.png" aria-describedby="version-file-hint" />
      <button class="button primary" type="submit" :disabled="saving">
        {{ saving ? '正在保存…' : '保存为新版本' }}
      </button>
    </form>
    <p v-if="saved" role="status">{{ saved }}</p>
    <p v-if="saveError" class="error-message" role="alert">{{ saveError }}</p>
    <button v-if="refreshNeeded && !loading && !historyError" class="button secondary" type="button"
      @click="retry++">刷新版本列表</button>
    <slot v-if="selected && selected === latest && !refreshNeeded" :photo="selected.photo" />
  </div>
</template>

<style scoped>
.versions { display: grid; gap: 8px; min-width: 0; }
.versions label { font-weight: 650; }
.versions select { min-height: 44px; max-width: 100%; font: inherit; color: inherit; }
.versions .error-message { margin: 0; }
.save-version {
  display: grid;
  justify-items: start;
  gap: 8px;
  padding: 14px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: #fcfcf9;
}
.save-version input { max-width: 100%; min-width: 0; font: inherit; }
.save-version input::file-selector-button { min-height: 44px; }
.secondary { justify-self: start; color: var(--accent); background: #e8eddc; }
</style>
