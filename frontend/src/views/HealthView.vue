<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { getHealth, type HealthStatus } from '../api/health'

const loading = ref(false)
const health = ref<HealthStatus | null>(null)
const error = ref('')
const checkedAt = ref('')

async function checkConnection() {
  if (loading.value) return
  loading.value = true
  health.value = null
  error.value = ''
  checkedAt.value = ''
  try {
    health.value = await getHealth()
    checkedAt.value = new Date().toLocaleTimeString('zh-CN')
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '连接失败，请稍后重试。'
  } finally {
    loading.value = false
  }
}

onMounted(checkConnection)
</script>

<template>
  <section class="health-page">
    <RouterLink to="/" class="back-link">← 返回项目首页</RouterLink>
    <p class="eyebrow">SERVICE CONNECTION</p>
    <h1>确认连接，<br />再开始协作。</h1>
    <p class="health-description">检查浏览器能否通过 API 访问后端应用。</p>
    <div class="health-panel" :aria-busy="loading">
      <div class="panel-heading"><h2>后端应用</h2><code>GET /api/health</code></div>
      <div aria-live="polite" aria-atomic="true" class="health-result">
        <p v-if="loading" class="connection-state">正在连接…</p>
        <template v-else-if="health">
          <p class="connection-state success"><span class="status-dot"></span> 连接正常</p>
          <p>应用：<code>{{ health.application }}</code></p>
          <p class="muted">检查时间 {{ checkedAt }}</p>
        </template>
      </div>
      <p v-if="error" role="alert" class="error-message">{{ error }}</p>
      <button class="button primary" type="button" :disabled="loading" @click="checkConnection">{{ loading ? '检查中…' : '重新检查' }}</button>
      <p class="health-disclaimer">此项只验证应用连接。MySQL、图片存储及业务接口的可用性需分别验证。</p>
    </div>
  </section>
</template>
