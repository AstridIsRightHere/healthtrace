<script setup lang="ts">
// 隐私政策（MVP 试用版）。内容须与实现一致：只写已经做到的保护措施，未确认事项（DEC-002/003/005/016）明确标注“待确定”，不作固定承诺。
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const backTarget = computed(() => (auth.isAuthenticated ? { name: 'me' } : { name: 'login' }))
const backText = computed(() => (auth.isAuthenticated ? '← 返回我的' : '← 返回登录'))
</script>

<template>
  <main class="home-page legal-page" data-testid="privacy-page">
    <header class="home-header">
      <RouterLink class="btn-ghost btn-small" :to="backTarget">{{ backText }}</RouterLink>
      <p class="brand">隐私政策</p>
    </header>

    <article class="home-card legal-article" aria-labelledby="privacy-title">
      <h1 id="privacy-title">HealthTrace 隐私政策</h1>
      <p class="field-hint">版本：MVP 试用版 · 更新于 2026-11-12</p>
      <p class="notice notice-warning">
        本系统目前处于邀请制小范围试用阶段，尚未正式上线。下文标注“待确定”的事项会在正式上线前补充，届时将更新本页并提示您。
      </p>

      <section aria-labelledby="p-collect">
        <h2 id="p-collect">一、我们收集哪些信息</h2>
        <ul>
          <li><strong>账号信息</strong>：邮箱、登录密码（只保存不可逆的哈希值，我们无法看到原密码）。</li>
          <li><strong>档案信息</strong>：档案称呼、与您的关系、成年及授权声明和确认时间。</li>
          <li><strong>您主动上传的健康资料</strong>：检验报告照片或 PDF、识别或手动录入的检查项目与数值、就诊记录。</li>
          <li><strong>安全记录</strong>：登录、上传、确认、修改、删除等操作的时间和结果（审计记录），用于安全追溯，不记录报告内容。</li>
        </ul>
        <p>我们不收集通讯录、定位、设备标识，也不使用第三方统计或广告追踪。</p>
      </section>

      <section aria-labelledby="p-use">
        <h2 id="p-use">二、信息如何使用</h2>
        <ul>
          <li>只用于为您本人档案生成趋势图、报告对比和就诊时间线。</li>
          <li>不出售、不出租，不用于广告；不参与跨用户统计。</li>
          <li>系统只做数值对比和整理，<strong>不提供诊断或用药建议</strong>，请以医生意见为准。</li>
        </ul>
      </section>

      <section aria-labelledby="p-protect" data-testid="privacy-encryption">
        <h2 id="p-protect">三、我们如何保护您的数据</h2>
        <ul>
          <li><strong>传输</strong>：正式部署时全程使用 HTTPS 加密传输。</li>
          <li><strong>邮箱</strong>：使用 AES-256-GCM 加密存储，检索时使用单向哈希索引，数据库中不出现明文邮箱。</li>
          <li><strong>密码</strong>：使用 BCrypt 单向哈希保存，登录失败多次会临时锁定。</li>
          <li><strong>识别原文</strong>：第三方识别返回的原始文字与结构化结果使用 AES-256-GCM 加密存储；修改留痕中的修改前后内容同样加密。</li>
          <li><strong>报告照片与 PDF</strong>：写入服务器磁盘前使用 AES-256-GCM 加密，每个文件绑定自己的存储位置，单独拷走文件或挪到别处都无法解密；只有在您本人查看时才在服务器内存中解密。</li>
          <li><strong>访问控制</strong>：每次访问报告、照片、就诊等资料都会核对是否属于您的档案；不属于您的资料一律当作“不存在”处理。</li>
          <li><strong>登录状态</strong>：使用 HttpOnly 会话 Cookie 并启用 CSRF 防护，网页脚本无法读取登录凭据。</li>
          <li><strong>本机缓存</strong>：安装到主屏幕后，手机只缓存页面程序文件，<strong>不缓存任何报告、照片或检查数值</strong>，离线时无法查看健康数据。</li>
        </ul>
        <p class="field-hint">
          说明：加密密钥与数据分开保管；加密备份与恢复方案（DEC-003）仍待确定。
        </p>
      </section>

      <section aria-labelledby="p-third">
        <h2 id="p-third">四、第三方服务</h2>
        <p>
          只有在您每次上传后<strong>主动勾选同意</strong>时，报告图片才会发送给百度智能云“医疗检验报告单识别”服务进行文字识别。具体处理方式见
          <RouterLink :to="{ name: 'data-authorization' }" data-testid="privacy-to-authorization">数据授权说明</RouterLink>。
          不同意也可以选择手动录入。
        </p>
      </section>

      <section aria-labelledby="p-rights">
        <h2 id="p-rights">五、您的权利</h2>
        <ul>
          <li><strong>查看与更正</strong>：已确认的报告可在报告页修改，修改前后内容会留痕。</li>
          <li><strong>移除与恢复</strong>：报告可移入回收站，之后可恢复。</li>
          <li><strong>彻底删除</strong>：在回收站或待处理列表中彻底删除报告，照片、识别结果、检查项和修改记录一并删除，不可恢复。</li>
          <li><strong>注销账号</strong>：在“我的 → 账号设置”中验证密码后注销，账号、档案及全部资料将被永久删除。</li>
          <li><strong>撤回授权</strong>：见 <RouterLink :to="{ name: 'data-authorization' }">数据授权说明</RouterLink> 第四节。</li>
        </ul>
        <p class="field-hint">删除在服务器备份和第三方服务侧的完成时限（DEC-002）待确定。</p>
      </section>

      <section aria-labelledby="p-retention">
        <h2 id="p-retention">六、保存期限</h2>
        <p>资料在您删除报告或注销账号前一直保存。删除操作会写入删除记录（只记录对象编号和时间，不含健康内容），用于确认删除已经完成。</p>
      </section>

      <section aria-labelledby="p-minor">
        <h2 id="p-minor">七、未成年人</h2>
        <p>本系统只面向成年人使用。为家人建立档案时，需要确认对方已成年并已授权您代为记录。</p>
      </section>

      <section aria-labelledby="p-contact">
        <h2 id="p-contact">八、联系我们</h2>
        <p>试用期间请通过邀请您的项目负责人联系；正式的人工支持渠道（DEC-016）待确定，确定后将在此公布。请不要通过公开渠道发送报告内容。</p>
      </section>
    </article>
  </main>
</template>
