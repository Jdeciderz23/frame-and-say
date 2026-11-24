<script setup lang="ts">
import { ref } from 'vue'
import { localTokenKey } from '../api/album'
import MemberEvents from '../components/MemberEvents.vue'

const token = ref(sessionStorage.getItem(localTokenKey) ?? '')
const enteredToken = ref('')

function connect() {
  const value = enteredToken.value.trim()
  if (!value) return
  sessionStorage.setItem(localTokenKey, value)
  token.value = value
  enteredToken.value = ''
}

function disconnect() {
  sessionStorage.removeItem(localTokenKey)
  token.value = ''
}
</script>

<template>
  <section class="events-page">
    <header class="events-heading">
      <h1>我的活动</h1>
      <button v-if="token" type="button" class="button" @click="disconnect">断开联调身份</button>
    </header>
    <MemberEvents v-if="token" :key="token" :token="token" />
    <form v-else class="health-panel event-form" @submit.prevent="connect">
      <label for="events-token">本机联调令牌</label>
      <p id="token-help" class="muted">使用本机的合成身份查看或创建选片活动。</p>
      <input id="events-token" v-model="enteredToken" type="password" autocomplete="off" required aria-describedby="token-help" />
      <button class="button primary" type="submit">连接活动</button>
    </form>
  </section>
</template>

<style>
.events-page {
  max-width: 980px;
  margin: 0 auto;
  padding: 40px clamp(16px, 4vw, 40px) 64px;
}
.events-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 24px; }
.events-page h1 { font-size: clamp(2rem, 4vw, 2.75rem); }
.events-page h2 { font-size: 1.25rem; }
.events-page .event-form { display: grid; align-content: start; gap: 12px; }
.events-page .event-form label { font-weight: 600; }
.events-page input {
  min-width: 0;
  width: 100%;
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: 6px;
  color: inherit;
  font: inherit;
}
.events-page .button { justify-self: start; }
</style>
