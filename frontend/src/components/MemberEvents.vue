<script setup lang="ts">
import { onBeforeUnmount, onMounted } from 'vue'
import { useMemberEvents } from './useMemberEvents'

const props = defineProps<{ token: string }>()
const session = new AbortController()
const {
  events, name, count, loading, saving, listError, formError, confirmation,
  recovery, refresh, submit, finishCheck,
} = useMemberEvents(props.token, session.signal)

onMounted(refresh)
onBeforeUnmount(() => session.abort())
</script>

<template>
  <div class="events-grid">
    <section class="health-panel" aria-labelledby="member-events-heading">
      <header class="list-heading">
        <h2 id="member-events-heading">已加入的活动</h2>
        <button class="button" type="button" :disabled="loading || saving" @click="refresh">
          {{ recovery === 'needs-list' ? '核对活动列表' : listError ? '重试活动列表' : '刷新列表' }}
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
      <input id="event-name" v-model="name" required maxlength="80" :disabled="saving || recovery !== 'none'" />
      <label for="event-count">目标选片数（选填）</label>
      <input id="event-count" v-model="count" type="number" min="1" max="100" step="1" placeholder="1–100" :disabled="saving || recovery !== 'none'" />
      <p v-if="recovery === 'needs-list'" class="error-message" role="alert">创建结果待核对。请点击「核对活动列表」查看活动是否已创建。</p>
      <div v-if="recovery === 'needs-choice'" class="recovery-actions">
        <p role="status">列表已刷新，请核对本次活动。</p>
        <button class="button" type="button" @click="finishCheck(true)">已找到该活动</button>
        <button class="button" type="button" @click="finishCheck(false)">未找到，返回表单</button>
      </div>
      <p v-if="formError" class="error-message" role="alert">{{ formError }}</p>
      <p v-if="confirmation" class="success" role="status">{{ confirmation }}</p>
      <button class="button primary" type="submit" :disabled="saving || loading || !!listError || recovery !== 'none'">
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
.recovery-actions { display: flex; flex-wrap: wrap; gap: 8px; }
.recovery-actions p { width: 100%; }
.event-list { list-style: none; margin: 0; padding: 0; }
.event-list li { border-top: 1px solid var(--line); }
.event-list a { display: grid; gap: 4px; padding: 12px 0; overflow-wrap: anywhere; }
.event-list strong { color: var(--accent); text-decoration: underline; text-underline-offset: 3px; white-space: pre-wrap; }
.event-list span { color: var(--muted); font-size: .875rem; }
@media (max-width: 700px) {
  .events-grid { grid-template-columns: 1fr; }
}
</style>
