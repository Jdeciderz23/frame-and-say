<template>
  <a class="skip-link" href="#main-content">跳到主要内容</a>
  <header class="site-header">
    <RouterLink to="/" class="brand" aria-label="照有商量首页">
      <span class="brand-icon" aria-hidden="true"><span></span><span></span></span>
      <span>照有商量<small>FRAME &amp; SAY</small></span>
    </RouterLink>
    <nav aria-label="主导航">
      <RouterLink to="/" class="nav-link">项目首页</RouterLink>
      <RouterLink to="/events" class="nav-link">我的活动</RouterLink>
      <RouterLink to="/health" class="nav-link">服务连接</RouterLink>
    </nav>
  </header>
  <main id="main-content" tabindex="-1"><RouterView /></main>
  <footer class="site-footer">
    <span>好照片，也要让照片里的人安心。</span>
  </footer>
</template>
