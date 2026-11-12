<script setup lang="ts">
// 数据授权说明：家属代管授权、第三方识别（百度 OCR）同意、撤回授权的方式与影响。
// 只写已实现的流程；人工撤回渠道与时限（DEC-002/016）及供应商合同（DEC-005）未确认，明确标注“待确定”。
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const backTarget = computed(() => (auth.isAuthenticated ? { name: 'me' } : { name: 'login' }))
const backText = computed(() => (auth.isAuthenticated ? '← 返回我的' : '← 返回登录'))
</script>

<template>
  <main class="home-page legal-page" data-testid="authorization-page">
    <header class="home-header">
      <RouterLink class="btn-ghost btn-small" :to="backTarget">{{ backText }}</RouterLink>
      <p class="brand">数据授权说明</p>
    </header>

    <article class="home-card legal-article" aria-labelledby="auth-title">
      <h1 id="auth-title">数据授权说明</h1>
      <p class="field-hint">版本：MVP 试用版 · 更新于 2026-11-12</p>

      <section aria-labelledby="a-profile">
        <h2 id="a-profile">一、档案授权</h2>
        <p>
          建立档案时，您需要确认档案本人已年满 18 周岁；为家人建档时，还需确认已获得对方授权由您代为记录。系统会保存这项确认和确认时间。家属关系本身不代表授权，请在对方同意后再上传其资料。
        </p>
      </section>

      <section aria-labelledby="a-ocr" data-testid="authorization-ocr">
        <h2 id="a-ocr">二、报告识别（百度 OCR）如何处理您的数据</h2>
        <ul>
          <li><strong>逐份同意</strong>：每份报告上传后，只有您勾选“同意发送至第三方识别”才会提交识别，系统会记录这次同意。不同意可以选择手动录入，功能不受影响。</li>
          <li><strong>发送什么</strong>：只发送该报告的图片内容，<strong>不发送</strong>您的邮箱、档案称呼或其他报告。</li>
          <li><strong>发给谁</strong>：百度智能云“医疗检验报告单识别”接口，通过 HTTPS 传输。</li>
          <li><strong>返回后</strong>：识别结果只是草稿，需要您逐项核对、确认后才会进入图表；识别原文在我们这边加密保存，用于日后核对。</li>
          <li><strong>失败时</strong>：识别失败会自动重试，最多尝试 3 次，仍失败则进入“待处理”，您可以改为手动录入。</li>
        </ul>
        <p class="field-hint">
          百度侧的数据留存期限、是否用于模型训练及删除方式，以百度智能云的服务条款为准；我们与服务商的正式处理协议（DEC-005）待确定，确定前请勿上传含有不希望第三方处理信息的报告，可选择手动录入。
        </p>
      </section>

      <section aria-labelledby="a-scope">
        <h2 id="a-scope">三、授权范围</h2>
        <ul>
          <li>数据只在您本人的档案中使用，其他账号无法查看。</li>
          <li>系统管理员不通过系统界面查看您的报告内容。</li>
          <li>未知检查项可由您设为“临时指标”，它只在本档案内有效，不会加入全局字典或与他人数据合并。</li>
        </ul>
      </section>

      <section aria-labelledby="a-withdraw" data-testid="authorization-withdraw">
        <h2 id="a-withdraw">四、如何撤回授权</h2>
        <ol>
          <li><strong>停止第三方识别</strong>：以后上传时不勾选同意即可，已识别的报告不受影响。</li>
          <li><strong>删除单份报告</strong>：报告详情页“移入回收站”后，在回收站“彻底删除”；或在首页待处理列表直接彻底删除。照片、识别结果、检查项、修改记录会一并删除。</li>
          <li>
            <strong>撤回全部授权并删除资料</strong>：在
            <RouterLink v-if="auth.isAuthenticated" :to="{ name: 'settings' }" data-testid="authorization-to-settings">我的 → 账号设置</RouterLink>
            <span v-else>我的 → 账号设置</span>
            中验证密码后注销账号，账号、档案及全部资料将被永久删除并退出登录。
          </li>
          <li><strong>人工撤回</strong>：如家人撤回对您的代管授权，或您无法自行登录，试用期间请联系邀请您的项目负责人协助处理；正式的人工渠道、身份核验材料和处理时限（DEC-016、DEC-002）待确定。</li>
        </ol>
        <p class="field-hint">撤回后，已删除的数据无法恢复。第三方服务侧已处理数据的删除，以服务商条款和后续协议为准。</p>
      </section>

      <p>
        更多信息见 <RouterLink :to="{ name: 'privacy-policy' }" data-testid="authorization-to-privacy">隐私政策</RouterLink>。
      </p>
    </article>
  </main>
</template>
