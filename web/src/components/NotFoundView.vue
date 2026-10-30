<script setup>
defineProps({
  title: { type: String, default: '页面不存在' },
  message: {
    type: String,
    default: '这个地址可能拼错了，或者内容已经被移除。'
  }
})
</script>

<template>
  <section class="notfound">
    <div class="code serif">404</div>
    <h1 class="title serif">{{ title }}</h1>
    <p class="message">{{ message }}</p>
    <div class="actions">
      <a class="btn primary" href="#/">回到首页</a>
      <a class="btn" href="#/ingredients">逛成分库</a>
    </div>
  </section>
</template>

<style scoped>
.notfound {
  max-width: 560px;
  margin: 8vh auto 0;
  padding: 40px 28px;
  border: 1px solid var(--line);
  border-radius: 20px;
  background: var(--card);
  box-shadow: var(--shadow-sm);
  text-align: center;
}

.code {
  font-size: 64px;
  line-height: 1;
  color: var(--accent);
  opacity: .22;
  letter-spacing: .04em;
}

.title { margin-top: 14px; font-size: 26px; font-weight: 600; }

.message { margin: 8px auto 22px; max-width: 400px; font-size: 13px; line-height: 1.9; color: var(--ink2); }

.actions { display: flex; justify-content: center; gap: 8px; flex-wrap: wrap; }

.actions .btn { text-decoration: none; }

@media (max-width: 640px) {
  .notfound { margin-top: 3vh; padding: 30px 18px; }
  .title { font-size: 22px; }
}
</style>
