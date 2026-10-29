import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'

vi.mock('../../api/followedMetric', () => ({
  followedMetricApi: { list: vi.fn(), save: vi.fn(), definitions: vi.fn() },
}))
vi.mock('../../api/trend', () => ({
  trendApi: { trend: vi.fn() },
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

describe('FollowedMetrics', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(followedMetricApi.list).mockReset()
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
    vi.mocked(trendApi.trend).mockResolvedValueOnce({
      metric: { code: 'WBC', standardName: '白细胞计数', standardUnit: '10^9/L', comparabilityNote: null },
      range: { from: '2021-01-01', to: '2026-01-01' },
      groups: [],
      unitWarning: false,
    } as never)
    await wrapper.find('[data-testid="follow-retry"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-testid="follow-list-error"]').exists()).toBe(false)
    expect(wrapper.findAll('[data-testid="follow-card"]')).toHaveLength(1)
    expect(followedMetricApi.list).toHaveBeenCalledTimes(2)
  })

  it('单项趋势请求失败只让该卡片显示加载失败，其他卡片正常；点击可重试', async () => {
    vi.mocked(followedMetricApi.list).mockResolvedValueOnce([
      { metricCode: 'WBC', name: '白细胞计数', sortOrder: 0 },
      { metricCode: 'HGB', name: '血红蛋白', sortOrder: 1 },
    ])
    vi.mocked(trendApi.trend).mockImplementation(async (_id, params) => {
      if (params.metricCode === 'HGB') throw networkError
      return {
        metric: { code: 'WBC', standardName: '白细胞计数', standardUnit: '10^9/L', comparabilityNote: null },
        range: { from: '2021-01-01', to: '2026-01-01' },
        groups: [],
        unitWarning: false,
      } as never
    })
    const wrapper = mountIt()
    await flushPromises()

    const failed = wrapper.find('[data-testid="follow-card-error"]')
    expect(failed.exists()).toBe(true)
    expect(failed.attributes('data-code')).toBe('HGB')
    expect(failed.text()).toContain('加载失败')
    expect(failed.text()).not.toContain('暂无记录')
    expect(wrapper.find('[data-testid="follow-card"][data-code="WBC"]').exists()).toBe(true)

    await failed.trigger('click')
    await flushPromises()
    expect(vi.mocked(trendApi.trend).mock.calls.filter(([, p]) => p.metricCode === 'HGB')).toHaveLength(2)
  })
})
