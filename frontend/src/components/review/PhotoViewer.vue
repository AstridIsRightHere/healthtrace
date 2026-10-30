<script setup lang="ts">
// 原图对照（FR-007）：图片支持预览/原图切换与放大；PDF 用浏览器内置阅读器；HEIC 浏览器不支持时给出提示
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { reportApi, type PhotoView } from '../../api/report'

const props = defineProps<{ photos: PhotoView[] }>()

const index = ref(0)
const zoomed = ref(false)
const useOriginal = ref(false)
const imageFailed = ref(false)

const current = computed(() => props.photos[index.value] ?? null)
const isPdf = computed(() => current.value?.mediaType === 'application/pdf')
const isHeic = computed(() => current.value?.mediaType === 'image/heic')
const originalUrl = computed(() => (current.value ? reportApi.photoUrl(current.value.id, 'original') : ''))
const imageUrl = computed(() => {
  const photo = current.value
  if (!photo) return ''
  return reportApi.photoUrl(photo.id, photo.hasPreview && !useOriginal.value ? 'preview' : 'original')
})

watch(index, () => {
  zoomed.value = false
  useOriginal.value = false
  imageFailed.value = false
})

/*
 * PDF 不直接把接口地址放进 iframe：后端全站 X-Frame-Options: DENY，浏览器会拒绝在框架中显示接口响应
 * （表现为“localhost 拒绝连接”）。改为 axios 取回二进制，生成同源 Blob URL 交给浏览器内置阅读器，
 * 切页或卸载时 revoke 释放内存。
 */
const pdfUrl = ref('')
const pdfLoading = ref(false)
const pdfFailed = ref(false)
let pdfController: AbortController | null = null

function releasePdf() {
  pdfController?.abort()
  pdfController = null
  if (pdfUrl.value) URL.revokeObjectURL(pdfUrl.value)
  pdfUrl.value = ''
}

async function loadPdf(photoId: number) {
  releasePdf()
  pdfFailed.value = false
  pdfLoading.value = true
  const controller = new AbortController()
  pdfController = controller
  try {
    const blob = await reportApi.fetchPhotoBlob(photoId, 'original', controller.signal)
    if (controller.signal.aborted) return
    pdfUrl.value = URL.createObjectURL(new Blob([blob], { type: 'application/pdf' }))
  } catch {
    if (!controller.signal.aborted) pdfFailed.value = true
  } finally {
    if (pdfController === controller) pdfLoading.value = false
  }
}

watch(
  () => (isPdf.value ? current.value?.id : null),
  (photoId) => {
    if (photoId != null) void loadPdf(photoId)
    else releasePdf()
  },
  { immediate: true },
)

onBeforeUnmount(releasePdf)

function select(next: number) {
  if (next >= 0 && next < props.photos.length) index.value = next
}

defineExpose({ select })
</script>

<template>
  <section class="viewer" aria-label="原图对照">
    <p v-if="!current" class="field-hint">这份报告没有可显示的文件。</p>
    <template v-else>
      <div v-if="photos.length > 1" class="viewer-pages" role="group" aria-label="切换页">
        <button
          v-for="(photo, i) in photos"
          :key="photo.id"
          type="button"
          class="range-btn"
          :class="{ active: i === index }"
          :aria-pressed="i === index"
          @click="select(i)"
        >
          第 {{ photo.pageOrder }} 页
        </button>
      </div>

      <div class="viewer-stage" :class="{ 'is-zoomed': zoomed, 'is-pdf': isPdf }">
        <div v-if="isPdf" class="viewer-pdf-frame">
          <iframe
            v-if="pdfUrl"
            class="viewer-pdf"
            :src="pdfUrl"
            :title="`第 ${current.pageOrder} 页 PDF 原件`"
            data-testid="pdf-frame"
          ></iframe>
          <p v-else-if="pdfFailed" class="viewer-fallback" role="note" data-testid="pdf-error">
            PDF 加载失败，可以在新标签页打开原件查看。
          </p>
          <p v-else class="viewer-fallback field-hint">PDF 加载中…</p>
        </div>
        <template v-else-if="!imageFailed">
          <button
            type="button"
            class="viewer-image-btn"
            :aria-label="zoomed ? '缩小图片' : '放大图片'"
            @click="zoomed = !zoomed"
          >
            <img :src="imageUrl" :alt="`第 ${current.pageOrder} 页报告原图`" @error="imageFailed = true" />
          </button>
        </template>
        <div v-else class="viewer-fallback" role="note">
          <p>{{ isHeic ? '当前浏览器无法直接显示 HEIC 图片。' : '图片加载失败。' }}</p>
          <p class="field-hint">可以下载原件后用系统相册查看，再对照下方表格核对。</p>
        </div>
      </div>

      <div class="viewer-tools">
        <button
          v-if="!isPdf && !imageFailed && current.hasPreview"
          type="button"
          class="btn-ghost btn-small"
          @click="useOriginal = !useOriginal"
        >
          {{ useOriginal ? '查看压缩预览' : '查看原图（更清晰）' }}
        </button>
        <a class="text-link" :href="originalUrl" target="_blank" rel="noopener">在新标签页打开原件</a>
      </div>
    </template>
  </section>
</template>
