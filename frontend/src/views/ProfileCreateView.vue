<script setup lang="ts">
// 建档页（US-003）：选择与患者的关系、可选昵称、勾选授权声明后建立唯一档案
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { errorMessage, fieldErrors } from '../api'
import type { Relationship } from '../api/profile'
import { useAuthStore } from '../stores/auth'
import { useProfileStore } from '../stores/profile'

// 与后端 ProfilePolicy.NICKNAME_MAX_LENGTH 一致
const NICKNAME_MAX_LENGTH = 80

const options: { value: Relationship; label: string; hint: string }[] = [
  { value: 'SELF', label: '本人', hint: '为自己记录健康资料' },
  { value: 'CHILD', label: '子女', hint: '为父母记录健康资料' },
  { value: 'SPOUSE', label: '配偶', hint: '为伴侣记录健康资料' },
  { value: 'OTHER', label: '其他', hint: '为其他家人记录健康资料' },
]

const auth = useAuthStore()
const profile = useProfileStore()
const router = useRouter()

const relationship = ref<Relationship | null>(null)
const nickname = ref('')
const declared = ref(false)
const submitting = ref(false)
const errors = ref<Record<string, string>>({})
const message = ref('')

const isSelf = computed(() => relationship.value === 'SELF')
// 本人与代管均须声明（合规底线），文案区分
const declarationText = computed(() =>
  isSelf.value
    ? '我确认本人已年满 18 周岁，并同意为自己记录健康资料。'
    : '我确认已获得该成年患者本人的授权，代为记录其健康资料。',
)
// 切换关系后声明文案变化，需重新勾选
watch(relationship, () => {
  declared.value = false
})
const nicknameLength = computed(() => Array.from(nickname.value.trim()).length)
const canSubmit = computed(
  () => relationship.value !== null && declared.value && !submitting.value,
)

async function onSubmit() {
  if (!canSubmit.value || relationship.value === null) return
  message.value = ''
  errors.value = {}
  if (nicknameLength.value > NICKNAME_MAX_LENGTH) {
    errors.value = { nickname: `昵称不能超过 ${NICKNAME_MAX_LENGTH} 个字符` }
    return
  }
  submitting.value = true
  try {
    const trimmed = nickname.value.trim()
    await profile.create({
      relationship: relationship.value,
      nickname: trimmed || undefined,
      authorizationDeclared: true,
    })
    await router.replace({ name: 'home' })
  } catch (error) {
    errors.value = fieldErrors(error)
    message.value = errorMessage(error, '建档失败，请稍后重试')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <main class="auth-page">
    <section class="auth-card" aria-labelledby="profile-title">
      <p class="brand">HealthTrace</p>
      <h1 id="profile-title">建立健康档案</h1>
      <p class="subtitle">先告诉我们这份档案是为谁记录的</p>

      <!-- 未验证邮箱可建档，但后续上传资料需先验证（由上传接口拦截） -->
      <p v-if="auth.user && !auth.user.emailVerified" class="notice" role="status">
        你的邮箱尚未验证。可以先建立档案，上传检查资料前请先完成邮箱验证。
      </p>

      <form novalidate @submit.prevent="onSubmit">
        <fieldset class="field relationship-group">
          <legend>与患者的关系</legend>
          <div class="relationship-options">
            <label
              v-for="option in options"
              :key="option.value"
              class="relationship-option"
              :class="{ selected: relationship === option.value }"
            >
              <input v-model="relationship" type="radio" name="relationship" :value="option.value" />
              <span class="option-label">{{ option.label }}</span>
              <span class="option-hint">{{ option.hint }}</span>
            </label>
          </div>
          <p v-if="errors.relationship" class="field-error">{{ errors.relationship }}</p>
        </fieldset>

        <div class="field">
          <label for="profile-nickname">{{ isSelf ? '昵称（可选）' : '患者昵称（可选）' }}</label>
          <input
            id="profile-nickname"
            v-model="nickname"
            type="text"
            autocomplete="off"
            :placeholder="isSelf ? '例如：我' : '例如：妈妈'"
            :aria-invalid="errors.nickname ? 'true' : 'false'"
            aria-describedby="profile-nickname-hint"
          />
          <p id="profile-nickname-hint" :class="errors.nickname ? 'field-error' : 'field-hint'">
            {{ errors.nickname || '不需要填写真实姓名' }}
          </p>
        </div>

        <div v-if="relationship" class="field">
          <label class="declaration">
            <input v-model="declared" type="checkbox" :aria-invalid="errors.authorizationDeclared ? 'true' : 'false'" />
            <span>{{ declarationText }}</span>
          </label>
          <p v-if="errors.authorizationDeclared" class="field-error">{{ errors.authorizationDeclared }}</p>
        </div>

        <p v-if="message" class="notice notice-error" role="alert">{{ message }}</p>

        <button class="btn-primary" type="submit" :disabled="!canSubmit">
          {{ submitting ? '建档中…' : '建立档案' }}
        </button>
      </form>
    </section>
  </main>
</template>
