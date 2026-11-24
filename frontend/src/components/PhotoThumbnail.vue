<script setup lang="ts">
import { ref, watch } from 'vue'
import { readThumbnail, type AlbumPhoto } from '../api/album'

const props = defineProps<{ eventId: string; photo: AlbumPhoto; token: string; alt: string }>()
const url = ref('')
const failed = ref(false)
const retry = ref(0)

watch(() => [props.eventId, props.photo, props.token, retry.value] as const, async ([eventId, photo, token], _, cleanup) => {
  const controller = new AbortController()
  let objectUrl = ''
  cleanup(() => {
    controller.abort()
    if (objectUrl) URL.revokeObjectURL(objectUrl)
  })
  url.value = ''
  failed.value = false
  if (!photo.thumbnailAvailable) return

  try {
    const blob = await readThumbnail(eventId, photo, token, controller.signal)
    if (!controller.signal.aborted) {
      objectUrl = URL.createObjectURL(blob)
      url.value = objectUrl
    }
  } catch {
    if (!controller.signal.aborted) failed.value = true
  }
}, { immediate: true })
</script>

<template>
  <div class="thumbnail">
    <img v-if="url && !failed" :src="url" :alt="alt" @error="failed = true" />
    <button v-else-if="failed" type="button" class="button retry" @click="retry++">重试缩略图</button>
    <p v-else>{{ photo.thumbnailAvailable ? '正在加载缩略图…' : '暂无缩略图' }}</p>
  </div>
</template>

<style scoped>
.thumbnail {
  display: grid;
  place-items: center;
  min-height: 110px;
  aspect-ratio: 4 / 3;
  overflow: hidden;
  border-radius: 7px;
  background: #e8eddc;
  color: var(--muted);
  font-size: .875rem;
}

.thumbnail img {
  width: 100%;
  height: 100%;
  min-height: 0;
  object-fit: contain;
}

.retry { background: #fcfcf9; color: var(--accent); }
</style>
