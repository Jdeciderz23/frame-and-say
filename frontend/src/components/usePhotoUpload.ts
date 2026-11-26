import { computed, ref, shallowRef } from 'vue'
import { photoUploadError, photoUploadOutcomeUnknown } from '../api/album'

type UploadState = 'idle' | 'uploading' | 'uploaded' | 'needs-check' | 'checking' | 'checked'
const maxBytes = 8 * 1024 * 1024

export function usePhotoUpload(
  send: (file: File, signal: AbortSignal) => Promise<void>,
  refreshPhotos: () => Promise<boolean>,
) {
  const state = ref<UploadState>('idle')
  const selected = shallowRef<File>()
  const error = ref('')
  const canSelect = computed(() => state.value === 'idle' || state.value === 'uploaded')
  const canSubmit = computed(() => state.value === 'idle' && Boolean(selected.value))
  let upload: AbortController | undefined
  let check: Promise<boolean> | undefined

  function reset() {
    upload?.abort()
    upload = undefined
    check = undefined
    state.value = 'idle'
    selected.value = undefined
    error.value = ''
  }

  function chooseFile(file?: File) {
    if (!canSelect.value) return
    selected.value = file
    state.value = 'idle'
    error.value = ''
  }

  async function submit() {
    const file = selected.value
    if (!file || !canSubmit.value) return false
    error.value = ''
    if (file.size === 0) {
      error.value = '所选文件为空，请重新选择。'
      return false
    }
    if (file.size > maxBytes) {
      error.value = '单张照片最多 8 MiB，请缩小后再上传。'
      return false
    }

    const controller = new AbortController()
    upload = controller
    state.value = 'uploading'
    try {
      await send(file, controller.signal)
      if (controller.signal.aborted) return false
      selected.value = undefined
      state.value = 'uploaded'
      return true
    } catch (cause) {
      if (controller.signal.aborted) return false
      state.value = photoUploadOutcomeUnknown(cause) ? 'needs-check' : 'idle'
      error.value = photoUploadError(cause)
      return false
    } finally {
      if (upload === controller) upload = undefined
    }
  }

  async function checkPhotos() {
    if (state.value !== 'needs-check' && state.value !== 'checked') return
    state.value = 'checking'
    const attempt = refreshPhotos()
    check = attempt
    const refreshed = await attempt.catch(() => false)
    if (check !== attempt) return
    check = undefined
    state.value = refreshed ? 'checked' : 'needs-check'
    error.value = refreshed ? '' : '刷新失败。请重新刷新照片列表，再继续核对。'
  }

  function finishCheck() {
    if (state.value !== 'checked') return false
    reset()
    return true
  }

  return { state, error, canSelect, canSubmit, chooseFile, submit, checkPhotos, finishCheck, reset }
}
