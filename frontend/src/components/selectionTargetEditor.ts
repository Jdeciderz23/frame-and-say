import { reactive } from 'vue'
import { AlbumRequestError, albumError, listEvents, setSelectionTarget, type AlbumEvent } from '../api/album'

export function selectionTargetEditor(event: AlbumEvent, token: string, session: AbortSignal) {
  let revision = 0
  const state = reactive({
    saving: false, loading: false, uncertain: false, error: '', message: '',
    async refreshIfUncertain() {
      if (!state.uncertain || state.loading || session.aborted) return
      const atStart = revision
      state.loading = true
      try {
        const latest = (await listEvents(token, session)).find(item => item.id === event.id)
        if (session.aborted || revision !== atStart) return
        if (!latest) throw new Error('活动不可用')
        event.desiredPhotoCount = latest.desiredPhotoCount
        state.error = ''
      } catch (error) {
        if (!session.aborted && revision === atStart) state.error = albumError(error)
      } finally {
        state.loading = false
      }
    },
    async save(count: number | '') {
      if (state.saving || state.loading || session.aborted || event.memberRole !== 'ORGANIZER') return
      state.error = ''
      state.message = ''
      if (typeof count !== 'number' || !Number.isInteger(count) || count < 1 || count > 100) {
        state.error = '请输入 1 至 100 之间的整数。'
        return
      }
      state.saving = true
      state.uncertain = true
      try {
        await setSelectionTarget(event.id, count, token, session)
        if (session.aborted) return
        revision++
        event.desiredPhotoCount = count
        state.uncertain = false
        state.message = '选片目标已保存。'
      } catch (error) {
        if (session.aborted) return
        state.error = error instanceof AlbumRequestError && error.status === 400
          ? '请输入 1 至 100 之间的整数。' : albumError(error)
      } finally {
        state.saving = false
      }
    },
  })
  return state
}
