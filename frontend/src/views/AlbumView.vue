<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { albumError, listEvents, listPhotos, type AlbumEvent, type AlbumPhoto } from '../api/album'
import PhotoThumbnail from '../components/PhotoThumbnail.vue'
import { selectionTargetEditor } from '../components/selectionTargetEditor'
import SelectionTarget from '../components/SelectionTarget.vue'
import PhotoPreference from '../components/PhotoPreference.vue'
import PhotoConsent from '../components/PhotoConsent.vue'
import PhotoUploadForm from '../components/PhotoUploadForm.vue'

const route = useRoute()
const router = useRouter()
const storageKey = 'frame-and-say:local-token'
const token = ref(sessionStorage.getItem(storageKey) ?? '')
const enteredToken = ref('')
const events = ref<AlbumEvent[]>([])
const photos = ref<AlbumPhoto[]>([])
const eventsLoading = ref(false)
const photosLoading = ref(false)
const eventsError = ref('')
const photosError = ref('')
const eventsRetry = ref(0)
const targetEditors = new Map<string, ReturnType<typeof selectionTargetEditor>>()
let targetSession = new AbortController()
function editorFor(item: AlbumEvent) {
  let editor = targetEditors.get(item.id)
  if (!editor) {
    editor = selectionTargetEditor(item, token.value, targetSession.signal)
    targetEditors.set(item.id, editor)
  }
  return editor
}

let photosRequest: AbortController | undefined
const eventId = computed(() => typeof route.query.event === 'string' ? route.query.event : '')
const event = computed(() => events.value.find(item => item.id === eventId.value))
const activePhoto = computed(() => photos.value.find(item => item.photoVersionId === route.query.photo))
const activePhotoNumber = computed(() => activePhoto.value ? photos.value.indexOf(activePhoto.value) + 1 : 0)

function connect() {
  const nextToken = enteredToken.value.trim()
  if (!nextToken) return
  sessionStorage.setItem(storageKey, nextToken)
  token.value = nextToken
  enteredToken.value = ''
  eventsRetry.value++
}

function disconnect() {
  sessionStorage.removeItem(storageKey)
  token.value = ''
  enteredToken.value = ''
}

function chooseEvent(eventId: string) {
  void router.push({ query: eventId ? { event: eventId } : {} })
}

function openPhoto(photo?: AlbumPhoto) {
  void router.push({ query: { event: eventId.value, photo: photo?.photoVersionId } })
}

watch([token, eventsRetry], async ([currentToken], _, cleanup) => {
  const controller = new AbortController()
  targetSession = controller
  targetEditors.clear()
  cleanup(() => controller.abort())
  events.value = []
  eventsError.value = ''
  eventsLoading.value = Boolean(currentToken)
  if (!currentToken) return

  try {
    const result = await listEvents(currentToken, controller.signal)
    if (!controller.signal.aborted) events.value = result
  } catch (error) {
    if (!controller.signal.aborted) eventsError.value = albumError(error)
  } finally {
    if (!controller.signal.aborted) eventsLoading.value = false
  }
}, { immediate: true })

async function refreshPhotos() {
  photosRequest?.abort()
  const currentToken = token.value
  const currentEvent = event.value
  photosError.value = ''
  photosLoading.value = Boolean(currentToken && currentEvent)
  if (!currentToken || !currentEvent) return false
  const controller = new AbortController()
  photosRequest = controller

  try {
    const result = await listPhotos(currentEvent.id, currentToken, controller.signal)
    if (controller.signal.aborted) return false
    photos.value = result
    return true
  } catch (error) {
    if (!controller.signal.aborted) photosError.value = albumError(error)
    return false
  } finally {
    if (photosRequest === controller) {
      photosRequest = undefined
      photosLoading.value = false
    }
  }
}

watch([token, event], (_, __, cleanup) => {
  cleanup(() => photosRequest?.abort())
  photos.value = []
  void refreshPhotos()
}, { immediate: true })
</script>

<template>
  <section class="album-page">
    <h1>活动相册</h1>
    <div class="album-connection">
      <form v-if="!token || eventsError" @submit.prevent="connect">
        <label for="album-token">本机联调令牌</label>
        <p class="muted">仅用于合成数据联调。关闭标签页后需重新连接。</p>
        <div class="album-actions">
          <input id="album-token" v-model="enteredToken" type="password" autocomplete="off" required />
          <button class="button primary" type="submit">连接相册</button>
        </div>
      </form>
      <div v-if="token" class="album-actions">
        <button type="button" class="button secondary" @click="disconnect">断开联调身份</button>
      </div>
    </div>

    <p v-if="eventsLoading" role="status">正在加载活动…</p>
    <div v-else-if="eventsError">
      <p class="error-message" role="alert">{{ eventsError }}</p>
      <button class="button secondary" type="button" @click="eventsRetry++">重试活动列表</button>
    </div>
    <p v-else-if="token && !events.length">当前身份没有可访问的活动。</p>
    <template v-else-if="events.length">
      <label for="album-event">活动</label>
      <select id="album-event" :value="event?.id ?? ''" @change="chooseEvent(($event.target as HTMLSelectElement).value)">
        <option value="">请选择活动</option>
        <option v-for="item in events" :key="item.id" :value="item.id">{{ item.name }}</option>
      </select>
      <p v-if="eventId && !event" role="status">当前身份无法访问链接中的活动。</p>
    </template>

    <section v-if="event" aria-labelledby="album-heading">
      <h2 id="album-heading">{{ event.name }}</h2>
      <SelectionTarget :key="'target-' + event.id" :event="event" :editor="editorFor(event)" />
      <PhotoUploadForm v-show="!activePhoto" :key="event.id" :event-id="event.id" :token="token"
        :refresh-photos="refreshPhotos" @uploaded="refreshPhotos" />
      <p v-if="photosLoading" role="status">正在加载照片…</p>
      <div v-else-if="photosError">
        <p class="error-message" role="alert">{{ photosError }}</p>
        <button class="button secondary" type="button" @click="refreshPhotos">重试照片列表</button>
      </div>
      <p v-else-if="!photos.length">这个活动还没有照片。</p>
      <template v-else>
        <p v-if="route.query.photo && !activePhoto" role="status">原照片版本已不可用，请从列表重新打开。</p>
        <section v-if="activePhoto" :key="activePhoto.photoVersionId" class="photo-detail" aria-labelledby="photo-heading">
          <button type="button" class="button secondary" @click="openPhoto()">返回照片列表</button>
          <h3 id="photo-heading">照片 {{ activePhotoNumber }}</h3>
          <PhotoThumbnail :event-id="event.id" :photo="activePhoto" :token="token" :alt="`照片 ${activePhotoNumber} 缩略图`" />
          <PhotoPreference :event-id="event.id" :photo="activePhoto" :token="token" @refresh="refreshPhotos" />
          <PhotoConsent :event-id="event.id" :photo="activePhoto" :token="token" @refresh="refreshPhotos" />
        </section>
        <ul v-else class="photo-grid">
          <li v-for="(photo, index) in photos" :key="photo.photoVersionId">
            <PhotoThumbnail :event-id="event.id" :photo="photo" :token="token" :alt="`照片 ${index + 1} 缩略图`" />
            <button class="button secondary" type="button" @click="openPhoto(photo)">打开照片 {{ index + 1 }}</button>
          </li>
        </ul>
      </template>
    </section>
  </section>
</template>

<style scoped>
.album-page {
  display: grid;
  gap: 20px;
  max-width: 900px;
  padding: 36px clamp(16px, 5vw, 40px) 48px;
  margin: 0 auto;
  overflow-wrap: anywhere;
}

.album-page h1 { font-size: 2rem; }
.album-page h2 { margin-bottom: 16px; font-size: 1.375rem; }
.album-page label { display: block; font-weight: 650; }
.album-connection { display: grid; gap: 8px; }
.album-connection p { margin: 6px 0 10px; font-size: .875rem; }
.album-actions { display: flex; flex-wrap: wrap; gap: 8px; }
.album-actions input { flex: 1 1 16rem; min-width: 0; }

.album-page input,
.album-page select {
  min-height: 44px;
  max-width: 100%;
  padding: 8px 12px;
  border: 1px solid var(--line);
  border-radius: 7px;
  color: inherit;
  background: #fcfcf9;
  font: inherit;
}

.secondary { color: var(--accent); background: #e8eddc; }
.secondary:hover { background: #dce6ce; }
.photo-detail { display: grid; gap: 12px; max-width: 520px; }
.photo-detail > button { justify-self: start; }
.photo-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 150px), 1fr));
  gap: 12px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.photo-grid li { display: grid; gap: 8px; min-width: 0; }
</style>
