<script setup lang="ts">
// 就诊时间线（FR-013）：就诊日期倒序；手填日期/医院/科室/备注；关联本档案报告。
// 就诊日与报告检查日分别记录，就诊次数按就诊记录计数。
import { computed, onMounted, reactive, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { errorMessage } from '../api'
import { reportApi, type ReportListItem } from '../api/report'
import { visitApi, type Visit } from '../api/visit'
import ReportSelect from '../components/report/ReportSelect.vue'
import { useProfileStore } from '../stores/profile'
import {
  formatShortDate,
  groupByYear,
  linkableReports,
  linkedReportLabel,
  localIsoDate,
  toPayload,
  validateVisitForm,
  visitTitle,
  type VisitForm,
  type VisitFormErrors,
} from '../utils/visit'

const profile = useProfileStore()

const visits = ref<Visit[]>([])
const reports = ref<ReportListItem[]>([])
const loading = ref(true)
const loadError = ref('')
const message = ref('')
const messageError = ref(false)

const today = localIsoDate()
const showForm = ref(false)
const editingId = ref<number | null>(null)
const form = reactive<VisitForm>({ visitDate: today, hospitalText: '', department: '', note: '' })
const errors = ref<VisitFormErrors>({})
const saving = ref(false)

const linkingVisitId = ref<number | null>(null)
const linkReportId = ref<number | null>(null)
const busyVisitId = ref<number | null>(null)
const confirmDeleteId = ref<number | null>(null)

const years = computed(() => groupByYear(visits.value))

function notify(text: string, isError = false) {
  message.value = text
  messageError.value = isError
}

function replaceVisit(v: Visit) {
  const i = visits.value.findIndex((x) => x.id === v.id)
  if (i >= 0) visits.value.splice(i, 1, v)
}

async function reload() {
  const pid = profile.profile?.id
  if (!pid) return
  visits.value = await visitApi.list(pid)
}

onMounted(async () => {
  const pid = profile.profile?.id
  if (!pid) {
    loading.value = false
    return
  }
  try {
    const [v, confirmed, drafts] = await Promise.all([
      visitApi.list(pid),
      reportApi.listConfirmed(pid),
      reportApi.listDrafts(pid),
    ])
    visits.value = v
    reports.value = [...confirmed, ...drafts]
  } catch (error) {
    loadError.value = errorMessage(error, '就诊记录加载失败，请稍后刷新')
  } finally {
    loading.value = false
  }
})

function openCreate() {
  editingId.value = null
  Object.assign(form, { visitDate: today, hospitalText: '', department: '', note: '' })
  errors.value = {}
  showForm.value = true
}

function openEdit(v: Visit) {
  editingId.value = v.id
  Object.assign(form, {
    visitDate: v.visitDate,
    hospitalText: v.hospitalText,
    department: v.department ?? '',
    note: v.note ?? '',
  })
  errors.value = {}
  showForm.value = true
}

function cancelForm() {
  showForm.value = false
  editingId.value = null
  errors.value = {}
}

async function submit() {
  errors.value = validateVisitForm(form, today)
  if (Object.keys(errors.value).length) return
  const pid = profile.profile?.id
  if (!pid || saving.value) return
  saving.value = true
  message.value = ''
  try {
    if (editingId.value == null) {
      await visitApi.create(pid, toPayload(form))
      notify('已添加就诊记录')
    } else {
      const current = visits.value.find((v) => v.id === editingId.value)
      if (!current) return
      await visitApi.update(current.id, current.version, toPayload(form))
      notify('已保存修改')
    }
    showForm.value = false
    editingId.value = null
    await reload()
  } catch (error) {
    notify(errorMessage(error, '保存失败，请稍后重试'), true)
  } finally {
    saving.value = false
  }
}

function startLink(v: Visit) {
  linkingVisitId.value = v.id
  linkReportId.value = null
}

async function confirmLink(v: Visit) {
  if (linkReportId.value == null || busyVisitId.value) return
  busyVisitId.value = v.id
  try {
    replaceVisit(await visitApi.linkReport(v.id, linkReportId.value))
    linkingVisitId.value = null
    notify('已关联报告')
  } catch (error) {
    notify(errorMessage(error, '关联失败，请稍后重试'), true)
  } finally {
    busyVisitId.value = null
  }
}

async function unlink(v: Visit, reportId: number) {
  if (busyVisitId.value) return
  busyVisitId.value = v.id
  try {
    replaceVisit(await visitApi.unlinkReport(v.id, reportId))
    notify('已解除关联，报告本身仍保留')
  } catch (error) {
    notify(errorMessage(error, '解除关联失败，请稍后重试'), true)
  } finally {
    busyVisitId.value = null
  }
}

async function remove(v: Visit) {
  if (busyVisitId.value) return
  busyVisitId.value = v.id
  try {
    await visitApi.remove(v.id)
    visits.value = visits.value.filter((x) => x.id !== v.id)
    confirmDeleteId.value = null
    notify('已删除就诊记录，关联的报告仍保留')
  } catch (error) {
    notify(errorMessage(error, '删除失败，请稍后重试'), true)
  } finally {
    busyVisitId.value = null
  }
}
</script>

<template>
  <main class="trend-page visit-page">
    <header class="home-header">
      <RouterLink class="btn-ghost btn-small" :to="{ name: 'home' }">← 返回首页</RouterLink>
      <p class="brand">就诊时间线</p>
      <span class="visit-count">{{ visits.length ? `共 ${visits.length} 次就诊` : '' }}</span>
    </header>

    <p v-if="message" class="notice" :class="messageError ? 'notice-error' : 'notice-success'" :role="messageError ? 'alert' : 'status'">
      {{ message }}
    </p>

    <section class="home-card visit-form-card">
      <div v-if="!showForm" class="visit-form-entry">
        <p class="field-hint">记录每次看病的日期、医院和科室，并关联当次的检查报告。</p>
        <button type="button" class="btn-primary" data-testid="visit-add" @click="openCreate">＋ 添加就诊</button>
      </div>

      <form v-else class="visit-form" novalidate @submit.prevent="submit">
        <h2>{{ editingId == null ? '添加就诊' : '编辑就诊' }}</h2>
        <div class="field">
          <label for="visit-date">就诊日期</label>
          <input id="visit-date" v-model="form.visitDate" type="date" :max="today" :aria-invalid="!!errors.visitDate" />
          <p v-if="errors.visitDate" class="field-error" role="alert">{{ errors.visitDate }}</p>
        </div>
        <div class="field">
          <label for="visit-hospital">医院</label>
          <input id="visit-hospital" v-model="form.hospitalText" type="text" maxlength="160" placeholder="如：市第一医院" :aria-invalid="!!errors.hospitalText" />
          <p v-if="errors.hospitalText" class="field-error" role="alert">{{ errors.hospitalText }}</p>
        </div>
        <div class="field">
          <label for="visit-department">科室（选填）</label>
          <input id="visit-department" v-model="form.department" type="text" maxlength="80" placeholder="如：呼吸内科" :aria-invalid="!!errors.department" />
          <p v-if="errors.department" class="field-error" role="alert">{{ errors.department }}</p>
        </div>
        <div class="field">
          <label for="visit-note">备注（选填）</label>
          <textarea id="visit-note" v-model="form.note" rows="3" maxlength="2000" placeholder="如：医生建议、复诊时间" :aria-invalid="!!errors.note" />
          <p v-if="errors.note" class="field-error" role="alert">{{ errors.note }}</p>
        </div>
        <div class="visit-form-actions">
          <button type="button" class="btn-ghost" :disabled="saving" @click="cancelForm">取消</button>
          <button type="submit" class="btn-primary" data-testid="visit-save" :disabled="saving">
            {{ saving ? '保存中…' : '保存' }}
          </button>
        </div>
      </form>
    </section>

    <p v-if="loading" class="field-hint" role="status">加载中…</p>
    <p v-else-if="loadError" class="notice notice-error" role="alert">{{ loadError }}</p>
    <p v-else-if="visits.length === 0" class="field-hint visit-empty">还没有就诊记录。</p>

    <template v-else>
    <section v-for="g in years" :key="g.year" class="visit-year" :aria-label="`${g.year} 年`">
      <h2 class="visit-year-title">{{ g.year }} 年</h2>
      <ol class="visit-timeline">
        <li v-for="v in g.visits" :key="v.id" class="visit-item" data-testid="visit-item">
          <div class="visit-dot" aria-hidden="true"></div>
          <article class="home-card visit-card">
            <header class="visit-card-head">
              <div>
                <p class="visit-date">{{ formatShortDate(v.visitDate) }}</p>
                <h3 class="visit-title">{{ visitTitle(v) }}</h3>
              </div>
              <div class="visit-card-actions">
                <button type="button" class="text-link" @click="openEdit(v)">编辑</button>
                <button type="button" class="text-link" @click="confirmDeleteId = v.id">删除</button>
              </div>
            </header>
            <p v-if="v.note" class="visit-note">{{ v.note }}</p>

            <div v-if="confirmDeleteId === v.id" class="notice visit-confirm" role="alert">
              <p>确定删除这条就诊记录吗？关联的报告不会被删除。</p>
              <div class="visit-form-actions">
                <button type="button" class="btn-ghost btn-small" @click="confirmDeleteId = null">取消</button>
                <button type="button" class="btn-ghost btn-small visit-danger" :disabled="busyVisitId === v.id" @click="remove(v)">确认删除</button>
              </div>
            </div>

            <div class="visit-reports">
              <p class="metric-picker-title">关联报告（{{ v.reports.length }}）</p>
              <ul v-if="v.reports.length" class="visit-report-list">
                <li v-for="r in v.reports" :key="r.id" class="visit-report">
                  <RouterLink
                    v-if="r.processingStatus === 'PENDING_CONFIRMATION' || r.processingStatus === 'CONFIRMED'"
                    class="text-link visit-report-name"
                    :to="{ name: 'report-review', params: { reportId: r.id } }"
                  >
                    {{ linkedReportLabel(r) }}
                  </RouterLink>
                  <span v-else class="visit-report-name">{{ linkedReportLabel(r) }}</span>
                  <button type="button" class="text-link" :disabled="busyVisitId === v.id" @click="unlink(v, r.id)">解除关联</button>
                </li>
              </ul>
              <p v-else class="field-hint">暂未关联报告。</p>

              <div v-if="linkingVisitId === v.id" class="visit-link-box">
                <template v-if="linkableReports(reports, v).length">
                  <ReportSelect v-model="linkReportId" label="选择报告" :reports="linkableReports(reports, v)" />
                  <div class="visit-form-actions">
                    <button type="button" class="btn-ghost btn-small" @click="linkingVisitId = null">取消</button>
                    <button
                      type="button"
                      class="btn-ghost btn-small"
                      data-testid="visit-link-confirm"
                      :disabled="linkReportId == null || busyVisitId === v.id"
                      @click="confirmLink(v)"
                    >
                      确认关联
                    </button>
                  </div>
                </template>
                <template v-else>
                  <p class="field-hint">没有可关联的报告。可先上传报告。</p>
                  <button type="button" class="text-link" @click="linkingVisitId = null">收起</button>
                </template>
              </div>
              <button v-else type="button" class="btn-ghost btn-small" data-testid="visit-link" @click="startLink(v)">＋ 关联报告</button>
            </div>
          </article>
        </li>
      </ol>
    </section>
    </template>
  </main>
</template>
