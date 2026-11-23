<script setup lang="ts">
import { onUnmounted, ref } from 'vue'
import { albumError, type AlbumPhoto } from '../api/album'
import { readPreference, savePreference, type Preference } from '../api/preference'

const props = defineProps<{ eventId: string; photo: AlbumPhoto; token: string }>()
const controller = new AbortController()
onUnmounted(() => controller.abort())

const options = [
  { value: -1, label: '−1 不喜欢' },
  { value: 0, label: '0 一般' },
  { value: 1, label: '1 喜欢' },
] as const
const current = ref<Preference | null>(null)
const selected = ref<Preference | null>(null)
const loaded = ref(false)
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const saved = ref(false)

async function loadPreference() {
  loading.value = true
  error.value = ''
  try {
    const value = await readPreference(props.eventId, props.photo, props.token, controller.signal)
    current.value = value
    selected.value = value
    loaded.value = true
  } catch (cause) {
    if (!controller.signal.aborted) error.value = albumError(cause)
  } finally {
    loading.value = false
  }
}

async function submit() {
  const value = selected.value
  if (!loaded.value || saving.value || value === null || value === current.value) return
  saving.value = true
  error.value = ''
  saved.value = false
  try {
    await savePreference(props.eventId, props.photo, props.token, value, controller.signal)
    current.value = value
    saved.value = true
  } catch (cause) {
    if (!controller.signal.aborted) error.value = albumError(cause)
  } finally {
    saving.value = false
  }
}

void loadPreference()
</script>

<template>
  <section class="preference" aria-labelledby="preference-heading">
    <h3 id="preference-heading">我的评分</h3>
    <p v-if="loading" role="status">正在读取评分…</p>
    <p v-if="error" role="alert" class="error-message">{{ error }}</p>
    <button v-if="!loaded && !loading" type="button" class="button primary" @click="loadPreference">重试读取评分</button>
    <form v-if="loaded" @submit.prevent="submit" @change="saved = false">
      <p role="status">{{ saved ? '评分已保存。' : '' }}{{ current === null ? '当前未评分。' : `当前评分 ${current}。` }}</p>
      <fieldset :disabled="saving">
        <legend>选择评分</legend>
        <label v-for="option in options" :key="option.value">
          <input v-model="selected" type="radio" name="preference" :value="option.value" />
          {{ option.label }}
        </label>
      </fieldset>
      <button class="button primary" type="submit" :disabled="saving || selected === null || selected === current">
        {{ saving ? '正在保存…' : error ? '重试保存评分' : '保存评分' }}
      </button>
    </form>
  </section>
</template>

<style scoped>
.preference { display: grid; gap: 8px; padding-top: 16px; border-top: 1px solid var(--line); }
.preference form { display: grid; gap: 10px; justify-items: start; }
.preference fieldset { display: flex; flex-wrap: wrap; gap: 4px 12px; padding: 0; margin: 0; border: 0; }
.preference label { display: inline-flex; gap: 5px; align-items: center; min-height: 44px; }
.preference input { accent-color: var(--accent); }
</style>
