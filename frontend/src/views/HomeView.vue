<script setup lang="ts">
const steps = [
  { index: '01', name: '一起选', detail: '在同一场活动中浏览照片，表达各自的偏好。' },
  { index: '02', name: '分别授权', detail: '喜欢一张照片，与同意公开这张照片，独立处理。' },
  { index: '03', name: '公平推荐', detail: '遵守发布授权，再兼顾每位成员的选片满意度。' },
]
</script>

<template>
  <section class="hero">
    <div class="hero-copy">
      <p class="eyebrow"><span class="status-dot"></span> 项目启动 · 基础工程已就绪</p>
      <h1>一张合照，<br />每个人都<span class="accent">满意。</span></h1>
      <p class="hero-description">把反复沟通的选片过程，变成一次有共识的决定。<br class="desktop-break" />尊重每个人的偏好，也尊重每一次发布授权。</p>
      <RouterLink to="/health" class="button primary">检查服务连接 <span aria-hidden="true">↗</span></RouterLink>
      <p class="scope">面向 5–10 人的小型活动 · 单场不超过 100 张照片</p>
    </div>
    <aside class="project-note" aria-label="当前项目状态">
      <p class="eyebrow">THIS IS WHERE WE BEGIN</p>
      <h2>先把基础<br />一起搭好。</h2>
      <p>当前提供项目首页与前后端健康检查。活动管理、照片上传、评价与授权等业务功能尚待开发。</p>
      <div class="note-line"><span>当前可验证</span><strong>页面路由 / API 连接</strong></div>
      <div class="note-line"><span>本次工作范围</span><strong>前两周项目启动</strong></div>
    </aside>
  </section>
  <section class="approach" aria-labelledby="approach-heading">
    <div class="section-label"><p class="eyebrow">OUR APPROACH</p><h2 id="approach-heading">共识，从尊重差异开始。</h2></div>
    <div class="step-grid">
      <article v-for="step in steps" :key="step.index" class="step-card">
        <div class="step-top"><span class="step-index">{{ step.index }}</span><span class="pending-tag">待开发</span></div>
        <h3>{{ step.name }}</h3><p>{{ step.detail }}</p>
      </article>
    </div>
    <p class="boundary-note">范围边界：暂不包含自动人脸识别、AI 美颜、商业交易或社交平台自动发布。</p>
  </section>
</template>
