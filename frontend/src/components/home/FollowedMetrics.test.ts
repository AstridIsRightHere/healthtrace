import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'

vi.mock('../../api/followedMetric', () => ({
  followedMetricApi: { list: vi.fn(), save: vi.fn(), definitions: vi.fn() },
}))
vi.mock('../../api/trend', () => ({
  trendApi: { summaries: vi.fn(), trend: vi.fn() },
}))

import { followedMetricApi } from '../../api/followedMetric'
import { trendApi } from '../../api/trend'
import FollowedMetrics from './FollowedMetrics.vue'

const router = createRouter({
  history: createMemoryHistory(),
  routes: [
    { path: '/', name: 'home', component: { template: '<div />' } },
    { path: '/trends', name: 'trends', component: { template: '<div />' } },
  ],
})

function mountIt() {
  return mount(FollowedMetrics, { props: { profileId: 7 }, global: { plugins: [router] } })
}

const networkError = Object.assign(new Error('Network Error'), { isAxiosError: true, code: 'ERR_NETWORK' })

const SIX = ['WBC', 'HGB', 'PLT', 'ALT', 'AST', 'CREA'].map((metricCode, sortOrder) => ({
  metricCode,
  name: metricCode,
  sortOrder,
}))

describe('FollowedMetrics', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(followedMetricApi.list).mockReset()
    vi.mocked(trendApi.summaries).mockReset()
    vi.mocked(trendApi.trend).mockReset()
  })

  it('关注列表请求失败时显示“加载失败，请点击重试”，不显示“暂无记录”，重试后恢复', async () => {
    vi.mocked(followedMetricApi.list).mockRejectedValueOnce(networkError)
    const wrapper = mountIt()
    await flushPromises()

    const error = wrapper.find('[data-testid="follow-list-error"]')
    expect(error.exists()).toBe(true)
    expect(error.text()).toContain('加载失败')
    expect(error.text()).toContain('请点击重试')
    expect(wrapper.text()).not.toContain('暂无记录')

    vi.mocked(followedMetricApi.list).mockResolvedValueOnce([{ metricCode: 'WBC', name: '白细胞计数', sortOrder: 0 }])
    vi.mocked(trendApi.summaries).mockResolvedValueOnce({
      items: [{ metricCode: 'WBC', standardName: '白细胞计数', standardUnit: '10^9/L', latest: null, change: null }],
    })
    await wrapper.find('[data-testid="follow-retry"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-testid="follow-list-error"]').exists()).toBe(false)
    expect(wrapper.findAll('[data-testid="follow-card"]')).toHaveLength(1)
    expect(followedMetricApi.list).toHaveBeenCalledTimes(2)
  })

  it('6 个关注指标只发 1 次批量 metric-summaries 请求，不再逐项请求趋势', async () => {
    vi.mocked(followedMetricApi.list).mockResolvedValueOnce(SIX)
    vi.mocked(trendApi.summaries).mockResolvedValueOnce({
      items: [
        {
          metricCode: 'WBC',
          standardName: '白细胞计数',
          standardUnit: '10^9/L',
          latest: {
            observationId: 9,
            reportId: 3,
            examinationDate: '2026-05-01',
            originalValue: '6.5',
            numericValue: 6.5,
            unitText: '10^9/L',
            abnormalFlag: null,
          },
          change: { absolute: 0.5, percent: 8.3, note: null },
        },
        ...SIX.slice(1).map((m) => ({
          metricCode: m.metricCode,
          standardName: m.metricCode,
          standardUnit: '',
          latest: null,
          change: null,
        })),
      ],
    })
    const wrapper = mountIt()
    await flushPromises()

    expect(trendApi.summaries).toHaveBeenCalledTimes(1)
    expect(trendApi.summaries).toHaveBeenCalledWith(7, ['WBC', 'HGB', 'PLT', 'ALT', 'AST', 'CREA'])
    expect(trendApi.trend).not.toHaveBeenCalled()
    expect(wrapper.findAll('[data-testid="follow-card"]')).toHaveLength(6)
    const wbc = wrapper.find('[data-testid="follow-card"][data-code="WBC"]')
    expect(wbc.text()).toContain('6.5')
    expect(wbc.text()).toContain('↑')
    expect(wrapper.find('[data-testid="follow-card"][data-code="HGB"]').text()).toContain('暂无记录')
  })

  it('批量请求失败时卡片显示加载失败而非暂无记录，点击后重新发起一次批量请求', async () => {
    vi.mocked(followedMetricApi.list).mockResolvedValueOnce(SIX.slice(0, 2))
    vi.mocked(trendApi.summaries).mockRejectedValueOnce(networkError)
    const wrapper = mountIt()
    await flushPromises()

    const failed = wrapper.findAll('[data-testid="follow-card-error"]')
    expect(failed).toHaveLength(2)
    expect(failed[0]!.text()).toContain('加载失败')
    expect(wrapper.text()).not.toContain('暂无记录')

    vi.mocked(trendApi.summaries).mockResolvedValueOnce({
      items: SIX.slice(0, 2).map((m) => ({
        metricCode: m.metricCode,
        standardName: m.metricCode,
        standardUnit: '',
        latest: null,
        change: null,
      })),
    })
    await failed[1]!.trigger('click')
    await flushPromises()
    expect(trendApi.summaries).toHaveBeenCalledTimes(2)
    expect(wrapper.findAll('[data-testid="follow-card"]')).toHaveLength(2)
    expect(wrapper.find('[data-testid="follow-card-error"]').exists()).toBe(false)
  })
})
