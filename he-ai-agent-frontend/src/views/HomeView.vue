<script setup>
import { useRouter } from "vue-router";

const router = useRouter();

function goLove() {
  router.push("/love");
}

function goManus() {
  router.push("/manus");
}
</script>

<template>
  <div class="home">
    <h1>AI 应用中心</h1>
    <p class="sub-title">请选择一个应用开始体验</p>
    <div class="app-grid">
      <div class="app-card" @click="goLove">
        <h3>AI 恋爱大师</h3>
        <p>场景化恋爱建议，支持多轮会话与 chatId 区分上下文。</p>
      </div>
      <div class="app-card" @click="goManus">
        <h3>AI 超级智能体</h3>
        <p>通用智能体聊天，流式返回结果，实时展示回答过程。</p>
      </div>
    </div>
  </div>
</template>
