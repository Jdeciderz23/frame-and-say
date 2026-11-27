import { ref } from 'vue'
import { AlbumRequestError, albumError, createEvent, listEvents, type AlbumEvent } from '../api/album'
import { pendingCreationKey, readPendingCreation, type CreationAttempt } from './pendingEventCreation'

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

  let attempt: CreationAttempt | undefined
  let storageKey: string
  const restored = restoreAttempt()

  async function restoreAttempt() {
    try {
      storageKey = await pendingCreationKey(token)
      if (signal.aborted) return false
      attempt = readPendingCreation(storageKey)
      if (attempt) {
        name.value = attempt.name
        count.value = attempt.target ?? ''
        recovery.value = 'needs-list'
      }
      return true
    } catch {
      formError.value = '无法读取当前标签页的待确认记录，请检查浏览器会话存储后刷新页面。'
      return false
    }
  }

  function forgetAttempt() {
    sessionStorage.removeItem(storageKey)
    attempt = undefined
  }

  async function refresh() {
    if (loading.value) return
    loading.value = true
    listError.value = ''
    try {
      await restored
      if (signal.aborted) return
      if (recovery.value !== 'none') recovery.value = 'needs-list'
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
    if (!await restored || signal.aborted || saving.value || loading.value || listError.value || recovery.value !== 'none') return
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
    const previous = attempt
    const sameInput = previous && previous.name.trim() === name.value.trim() && previous.target === target
    const request = { id: sameInput ? previous.id : crypto.randomUUID(), name: name.value, target }
    try {
      sessionStorage.setItem(storageKey, JSON.stringify(request))
    } catch {
      formError.value = '无法保存本次创建记录，请检查浏览器会话存储后重试。'
      return
    }
    attempt = request
    saving.value = true
    try {
      const created = await createEvent({ name: request.name, desiredPhotoCount: target }, token, signal, request.id)
      if (signal.aborted) return
      forgetAttempt()
      confirmation.value = '已创建「' + created.name + '」。'
      name.value = ''
      count.value = ''
      await refresh()
    } catch (error) {
      if (signal.aborted) return
      if (error instanceof AlbumRequestError && error.status >= 400 && error.status < 500 && error.status !== 408) {
        if (!sameInput) forgetAttempt()
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
      forgetAttempt()
      name.value = ''
      count.value = ''
      confirmation.value = '已确认活动在列表中。'
    }
  }

  return { events, name, count, loading, saving, listError, formError, confirmation, recovery, refresh, submit, finishCheck }
}
