<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import type { AlbumEvent } from '../api/album'
import type { selectionTargetEditor } from './selectionTargetEditor'

const props = defineProps<{ event: AlbumEvent; editor: ReturnType<typeof selectionTargetEditor> }>()
const draft = ref<number | ''>(props.event.desiredPhotoCount ?? '')
watch(() => props.event.desiredPhotoCount, value => { draft.value = value ?? '' })
onMounted(() => props.editor.refreshIfUncertain())
</script>

<template>
  <section class="selection-target" aria-label="活动选片目标">
    <p v-if="editor.loading" role="status">正在核对当前目标…</p>
    <p v-else>当前选片目标 · <strong>{{ event.desiredPhotoCount == null ? '未设置' : event.desiredPhotoCount + ' 张' }}</strong></p>
    <form v-if="event.memberRole === 'ORGANIZER'" novalidate @submit.prevent="editor.save(draft)">
      <label for="selection-target">调整目标（1–100 张）</label>
      <div class="target-actions">
        <input id="selection-target" v-model="draft" type="number" min="1" max="100" step="1" required
          :disabled="editor.saving || editor.loading" @input="editor.error = ''; editor.message = ''" />
        <button type="submit" class="button primary" :disabled="editor.saving || editor.loading">{{ editor.saving ? '正在保存…' : '保存目标' }}</button>
      </div>
      <p v-if="editor.error" role="alert" class="error-message">{{ editor.error }}</p>
      <button v-if="editor.uncertain && !editor.saving && !editor.loading" type="button" class="button secondary"
        @click="editor.refreshIfUncertain()">重新核对目标</button>
      <p v-if="editor.message" role="status" class="success">{{ editor.message }}</p>
    </form>
  </section>
</template>

<style scoped>
.selection-target { display: grid; gap: 12px; margin-bottom: 20px; padding: 16px; border: 1px solid var(--line); border-radius: 8px; }
.selection-target form { display: grid; gap: 8px; }
.target-actions { display: flex; flex-wrap: wrap; gap: 8px; }
.target-actions input { width: 7em; min-height: 44px; padding: 8px 12px; border: 1px solid var(--line); border-radius: 7px; font: inherit; color: inherit; }
</style>
