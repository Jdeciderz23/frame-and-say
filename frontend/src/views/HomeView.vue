<script setup lang="ts">
const steps = [
  { name: '一起选', detail: '在同一场活动中浏览照片，表达各自的偏好。' },
  { name: '分别授权', detail: '你可以喜欢一张照片，也可以决定它能分享给谁。' },
  { name: '公平推荐', detail: '在同意分享的照片里，挑出兼顾成员偏好的组合。' },
]
</script>

<template>
  <section class="hero">
    <div class="hero-copy">
      <h1>照片怎么选，<br />大家<span class="accent">有商量。</span></h1>
      <p class="hero-description">活动结束后，大家可以一起挑照片，<br class="desktop-break" />出镜的人各自决定照片可以分享给谁。</p>
      <RouterLink to="/health" class="button primary">检查服务连接 <span aria-hidden="true">↗</span></RouterLink>
      <p class="scope">面向 5–10 人的小型活动 · 单场不超过 100 张照片</p>
    </div>
    <aside class="project-note" aria-label="当前项目状态">
      <h2>项目进度</h2>
      <p>目前可检查服务连接。活动、照片、评分和授权的基础接口已可联调，协作页面仍在开发中。</p>
    </aside>
  </section>
  <section class="approach" aria-labelledby="approach-heading">
    <div class="section-label"><h2 id="approach-heading">一起选片的流程</h2></div>
    <div class="step-grid">
      <article v-for="step in steps" :key="step.name" class="step-card">
        <h3>{{ step.name }}</h3><p>{{ step.detail }}</p>
      </article>
    </div>
  </section>
</template>
