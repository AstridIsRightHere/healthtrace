<script setup lang="ts">
// PWA 提示：可安装时提示“添加到主屏幕”（iOS Safari 给出手动步骤）；新版本就绪时提示刷新。
import { applyUpdate, dismissInstall, dismissUpdate, promptInstall, pwaState } from '../../utils/pwa'
</script>

<template>
  <div v-if="pwaState.needRefresh" class="pwa-banner" role="status" data-testid="pwa-update">
    <p>HealthTrace 有新版本，刷新后生效。</p>
    <button class="btn-primary btn-small" type="button" @click="applyUpdate">立即刷新</button>
    <button class="btn-ghost btn-small" type="button" @click="dismissUpdate">稍后</button>
  </div>
  <div v-else-if="pwaState.hint !== 'none'" class="pwa-banner" role="dialog" aria-label="添加到主屏幕" data-testid="pwa-install">
    <p v-if="pwaState.hint === 'prompt'">把 HealthTrace 添加到主屏幕，下次打开更方便。</p>
    <p v-else>添加到主屏幕：点浏览器底部的“分享”按钮，再选“添加到主屏幕”。</p>
    <button
      v-if="pwaState.hint === 'prompt'"
      class="btn-primary btn-small"
      type="button"
      data-testid="pwa-install-accept"
      @click="promptInstall"
    >
      添加
    </button>
    <button class="btn-ghost btn-small" type="button" data-testid="pwa-install-dismiss" @click="dismissInstall">不用了</button>
  </div>
</template>
