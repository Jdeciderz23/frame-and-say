import { ref } from 'vue'
import { AlbumRequestError, albumError, createEvent, listEvents, type AlbumEvent } from '../api/album'

export function useMemberEvents(token: string, signal: AbortSignal) {
  const events = ref<AlbumEvent[]>([])
  const name = ref('')
  const count = ref<number | ''>('')
  const loading = ref(false)
  const saving = ref(false)
  const listError = ref('')
  const formError = ref('')
  const confirmation = ref('')
  const recovery = ref<'none' | 'needs-list' | 'needs-choice'>('none')

  async function refresh() {
    if (loading.value) return
    if (recovery.value !== 'none') recovery.value = 'needs-list'
    loading.value = true
    listError.value = ''
    try {
      const result = await listEvents(token, signal)
      if (signal.aborted) return
      events.value = result
      if (recovery.value === 'needs-list') recovery.value = 'needs-choice'
    } catch (error) {
      if (!signal.aborted) {
        events.value = []
        listError.value = albumError(error)
      }
    } finally {
      loading.value = false
    }
  }

  async function submit() {
    if (saving.value || loading.value || listError.value || recovery.value !== 'none') return
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
      const created = await createEvent({ name: name.value, desiredPhotoCount: target }, token, signal)
      if (signal.aborted) return
      confirmation.value = '已创建「' + created.name + '」。'
      name.value = ''
      count.value = ''
      await refresh()
    } catch (error) {
      if (signal.aborted) return
      if (error instanceof AlbumRequestError && error.status >= 400 && error.status < 500 && error.status !== 408) {
        formError.value = error.status === 400 ? '请检查活动名称和目标选片数后重试。' : error.message
      } else {
        recovery.value = 'needs-list'
      }
    } finally {
      saving.value = false
    }
  }

  function finishCheck(found: boolean) {
    if (recovery.value !== 'needs-choice') return
    recovery.value = 'none'
    if (found) {
      name.value = ''
      count.value = ''
      confirmation.value = '已确认活动在列表中。'
    }
  }

  return { events, name, count, loading, saving, listError, formError, confirmation, recovery, refresh, submit, finishCheck }
}
