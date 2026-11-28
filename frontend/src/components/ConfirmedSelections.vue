<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { albumError, type AlbumPhoto } from '../api/album'
import { listSelections, type ConfirmedSelection } from '../api/confirmedSelections'
import { purposes } from '../api/consent'

const props = defineProps<{ eventId: string; token: string; photos: AlbumPhoto[]; revision: number; organizer: boolean }>()
const refresh = ref(0)
const selections = ref<ConfirmedSelection[]>([])
const loading = ref(false)
const error = ref('')
const album = computed(() => new Map(props.photos.map((photo, index) => [photo.photoId, { photo, number: index + 1 }])))

watch([() => props.eventId, () => props.token, () => props.revision, refresh], async (_, __, cleanup) => {
  const controller = new AbortController()
  cleanup(() => controller.abort())
  selections.value = []
  loading.value = true
  error.value = ''
  try {
    const result = await listSelections(props.eventId, props.token, controller.signal)
    if (!controller.signal.aborted) selections.value = result
  } catch (cause) {
    if (!controller.signal.aborted) error.value = albumError(cause)
  } finally {
    if (!controller.signal.aborted) loading.value = false
  }
}, { immediate: true })

</script>

<template>
  <section aria-labelledby="confirmed-heading" class="confirmed">
    <h3 id="confirmed-heading">已确认清单</h3>
    <button class="button secondary" type="button" :disabled="loading" @click="refresh++">刷新清单</button>
    <p v-if="loading" role="status">正在读取清单…</p>
    <p v-else-if="error" class="error-message" role="alert">{{ error }}</p>
    <p v-else-if="!selections.length">当前没有可核对的清单。</p>
    <template v-else>
      <p v-if="!organizer" class="muted">仅显示当前用途下仍获准核对的版本。</p>
      <details v-for="selection in selections" :key="selection.selectionId">
        <summary>
          {{ purposes.find(item => item.value === selection.purpose)?.label }} · {{ selection.items.length }} 张
          · 清单 {{ selection.selectionId }}
        </summary>
        <ol>
          <li v-for="item in selection.items" :key="item.photoVersionId">
            <RouterLink v-if="album.has(item.photoId)"
              :to="{ path: '/album', query: { event: eventId, photo: item.photoId, version: item.photoVersionId } }">
              照片 {{ album.get(item.photoId)?.number }} · 版本 {{ item.photoVersionId }}
            </RouterLink>
            <span v-else>版本 {{ item.photoVersionId }}（当前相册中不可用）</span>
            · 确认时的名单修订 {{ item.subjectRevision }}
            <span v-if="organizer && album.has(item.photoId) && album.get(item.photoId)?.photo.photoVersionId !== item.photoVersionId">
              · 已有新版，交付前需重新确认
            </span>
          </li>
        </ol>
      </details>
    </template>
  </section>
</template>

<style scoped>
.confirmed { display: grid; gap: 8px; margin-bottom: 18px; }
.confirmed button { justify-self: start; }
.confirmed details { border-top: 1px solid var(--line); }
.confirmed summary { padding-block: 12px; cursor: pointer; }
.confirmed li { margin-bottom: 8px; }
</style>
