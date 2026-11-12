<script setup lang="ts">
// 显示设置（NFR-006）：主题（跟随系统/浅色/深色）与四档字号。偏好只保存在本机浏览器。
import { ref } from 'vue'
import { RouterLink } from 'vue-router'
import {
  FONT_SIZE_OPTIONS,
  THEME_OPTIONS,
  loadFontSizePreference,
  loadThemePreference,
  saveFontSizePreference,
  saveThemePreference,
  type FontSizePreference,
  type ThemePreference,
} from '../utils/appearance'

const theme = ref<ThemePreference>(loadThemePreference())
const fontSize = ref<FontSizePreference>(loadFontSizePreference())

function onTheme(value: ThemePreference): void {
  theme.value = value
  saveThemePreference(value)
}

function onFontSize(value: FontSizePreference): void {
  fontSize.value = value
  saveFontSizePreference(value)
}
</script>

<template>
  <main class="home-page settings-page" data-testid="display-page">
    <header class="home-header">
      <RouterLink class="btn-ghost btn-small" :to="{ name: 'me' }">← 返回我的</RouterLink>
      <p class="brand">显示设置</p>
    </header>

    <section class="home-card" aria-labelledby="theme-title">
      <h2 id="theme-title">外观</h2>
      <p class="field-hint">“跟随系统”会随手机或电脑的深色模式自动切换。</p>
      <div class="choice-group" role="radiogroup" aria-labelledby="theme-title" data-testid="theme-options">
        <label v-for="o in THEME_OPTIONS" :key="o.value" class="choice" :class="{ active: theme === o.value }">
          <input
            type="radio"
            name="theme"
            :value="o.value"
            :checked="theme === o.value"
            :data-testid="`theme-${o.value}`"
            @change="onTheme(o.value)"
          />
          <span>{{ o.label }}</span>
        </label>
      </div>
    </section>

    <section class="home-card" aria-labelledby="font-title">
      <h2 id="font-title">字号</h2>
      <div class="choice-group" role="radiogroup" aria-labelledby="font-title" data-testid="font-options">
        <label v-for="o in FONT_SIZE_OPTIONS" :key="o.value" class="choice" :class="{ active: fontSize === o.value }">
          <input
            type="radio"
            name="font-size"
            :value="o.value"
            :checked="fontSize === o.value"
            :data-testid="`font-${o.value}`"
            @change="onFontSize(o.value)"
          />
          <span>{{ o.label }}</span>
          <span class="field-hint">{{ o.px }}px</span>
        </label>
      </div>
      <p class="font-preview" data-testid="font-preview">预览：白细胞计数 6.5 ×10⁹/L，参考范围 3.5–9.5。</p>
      <p class="field-hint">设置只保存在当前浏览器，换设备需要重新选择。</p>
    </section>
  </main>
</template>
