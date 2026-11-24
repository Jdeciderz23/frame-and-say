<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { AlbumRequestError, albumError, createEvent, listEvents, type AlbumEvent } from '../api/album'

const props = defineProps<{ token: string }>()
const session = new AbortController()
const events = ref<AlbumEvent[]>([])
const name = ref('')
const count = ref<number | ''>('')
const loading = ref(false)
const saving = ref(false)
const listError = ref('')
const formError = ref('')
const confirmation = ref('')

async function refresh() {
  if (loading.value) return
  loading.value = true
  listError.value = ''
  try {
    events.value = await listEvents(props.token, session.signal)
  } catch (error) {
    if (!session.signal.aborted) {
      events.value = []
      listError.value = albumError(error)
    }
  } finally {
    loading.value = false
  }
}

async function submit() {
  if (saving.value || loading.value || listError.value) return
  formError.value = ''
  confirmation.value = ''
  if (!name.value.trim() || name.value.length > 80) {
    formError.value = '请填写 1 至 80 个字符的活动名称。'
    return
  }
  const target = count.value === '' ? undefined : count.value
  if (target !== undefined && (!Number.isInteger(target) || target < 1 || target > 100)) {
    formError.value = '目标选片数须为 1 至 100 之间的整数。'
    return
  }
  saving.value = true
  try {
    const created = await createEvent({ name: name.value, desiredPhotoCount: target }, props.token, session.signal)
    if (session.signal.aborted) return
    confirmation.value = '已创建「' + created.name + '」。'
    name.value = ''
    count.value = ''
    await refresh()
  } catch (error) {
    if (session.signal.aborted) return
    if (error instanceof AlbumRequestError) {
      formError.value = error.status === 400 ? '请检查活动名称和目标选片数后重试。' : error.message
    } else {
      formError.value = '未收到创建结果，请先刷新活动列表，确认后再重试。'
    }
  } finally {
    saving.value = false
  }
}

onMounted(refresh)
onBeforeUnmount(() => session.abort())
</script>

<template>
  <div class="events-grid">
    <section class="health-panel" aria-labelledby="member-events-heading">
      <header class="list-heading">
        <h2 id="member-events-heading">已加入的活动</h2>
        <button class="button" type="button" :disabled="loading || saving" @click="refresh">
          {{ listError ? '重试活动列表' : '刷新列表' }}
        </button>
      </header>
      <p v-if="loading" role="status">正在加载活动…</p>
      <p v-else-if="listError" class="error-message" role="alert">{{ listError }}</p>
      <p v-else-if="!events.length" class="muted">你还没有加入活动。</p>
      <ul v-else class="event-list">
        <li v-for="event in events" :key="event.id">
          <RouterLink :to="{ path: '/album', query: { event: event.id } }">
            <strong>{{ event.name }}</strong>
            <span>{{ event.desiredPhotoCount == null ? '未设置选片目标' : '目标 ' + event.desiredPhotoCount + ' 张' }}</span>
          </RouterLink>
        </li>
      </ul>
    </section>
    <form class="health-panel event-form" aria-labelledby="create-event-heading" @submit.prevent="submit">
      <h2 id="create-event-heading">创建活动</h2>
      <label for="event-name">活动名称</label>
      <input id="event-name" v-model="name" required maxlength="80" :disabled="saving" />
      <label for="event-count">目标选片数（选填）</label>
      <input id="event-count" v-model="count" type="number" min="1" max="100" step="1" placeholder="1–100" :disabled="saving" />
      <p v-if="formError" class="error-message" role="alert">{{ formError }}</p>
      <p v-if="confirmation" class="success" role="status">{{ confirmation }}</p>
      <button class="button primary" type="submit" :disabled="saving || loading || !!listError">
        {{ saving ? '正在创建…' : '创建活动' }}
      </button>
    </form>
  </div>
</template>

<style scoped>
.events-grid { display: grid; grid-template-columns: 1.2fr 1fr; gap: 20px; align-items: start; }
.events-grid > * { min-width: 0; }
.list-heading { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-bottom: 16px; }
.list-heading button { padding: 10px 14px; }
.event-list { list-style: none; margin: 0; padding: 0; }
.event-list li { border-top: 1px solid var(--line); }
.event-list a { display: grid; gap: 4px; padding: 12px 0; overflow-wrap: anywhere; }
.event-list strong { color: var(--accent); text-decoration: underline; text-underline-offset: 3px; white-space: pre-wrap; }
.event-list span { color: var(--muted); font-size: .875rem; }
@media (max-width: 700px) {
  .events-grid { grid-template-columns: 1fr; }
}
</style>
