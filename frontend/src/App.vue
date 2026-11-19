<template>
  <a class="skip-link" href="#main-content">跳到主要内容</a>
  <header class="site-header">
    <RouterLink to="/" class="brand" aria-label="合照都满意首页">
      <span class="brand-icon" aria-hidden="true"><span></span><span></span></span>
      <span>合照都满意<small>GROUP PHOTO CONSENSUS</small></span>
    </RouterLink>
    <nav aria-label="主导航">
      <RouterLink to="/" class="nav-link">项目首页</RouterLink>
      <RouterLink to="/health" class="nav-link">服务连接</RouterLink>
    </nav>
  </header>
  <main id="main-content" tabindex="-1"><RouterView /></main>
  <footer class="site-footer">
    <span>好照片，也要让照片里的人安心。</span>
    <span>本科软件工程课程设计 · 项目启动阶段</span>
  </footer>
</template>
