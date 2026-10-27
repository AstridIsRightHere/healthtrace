// 趋势折线图 option 构建（纯函数，便于测试与复用）。
// 规则：时间轴按区间铺满；Y 轴自适应且覆盖所有值与参考界，不裁剪极端值；
// 间隔超过常规间隔 3 倍处断线、不插值；颜色之外同时用形状区分偏高/偏低（NFR-006）。
import type { AbnormalFlag } from '../api/ocr'
import type { DateRange, TrendChange, TrendGroup, TrendPoint } from '../api/trend'
import type { LineSeriesOption } from 'echarts/charts'
import type { ChartOption } from './echarts'

export const COLORS = {
  line: '#e07a5f',
  abnormal: '#c0392b',
  normal: '#2e7d32',
  unknown: '#8a7d73',
  band: 'rgba(46, 125, 50, 0.08)',
  bandLine: 'rgba(46, 125, 50, 0.55)',
  axis: '#8a7d73',
  split: '#eadfd5',
}

const DAY_MS = 24 * 60 * 60 * 1000
/** 断线阈值：相邻间隔大于常规（中位数）间隔的倍数 */
const GAP_FACTOR = 3
/** 至少这么多个点才判断断线，避免 2 个点时永远“断开” */
const GAP_MIN_POINTS = 3
const Y_PAD_RATIO = 0.12

/** 'YYYY-MM-DD' → 本地零点时间戳（与 ECharts time 轴本地显示一致） */
export function dateToTime(date: string): number {
  const [y, m, d] = date.split('-').map(Number)
  return new Date(y!, (m ?? 1) - 1, d ?? 1).getTime()
}

export function flagText(flag: AbnormalFlag | null | undefined): string {
  switch (flag) {
    case 'HIGH':
      return '偏高 ↑'
    case 'LOW':
      return '偏低 ↓'
    case 'NORMAL':
      return '范围内'
    default:
      return '未判断'
  }
}

export function flagColor(flag: AbnormalFlag | null | undefined): string {
  if (flag === 'HIGH' || flag === 'LOW') return COLORS.abnormal
  if (flag === 'NORMAL') return COLORS.normal
  return COLORS.unknown
}

function flagSymbol(flag: AbnormalFlag | null | undefined): { symbol: string; symbolRotate: number } {
  if (flag === 'HIGH') return { symbol: 'triangle', symbolRotate: 0 }
  if (flag === 'LOW') return { symbol: 'triangle', symbolRotate: 180 }
  return { symbol: 'circle', symbolRotate: 0 }
}

function formatNumber(n: number): string {
  // 后端已去掉尾随 0；这里只防止浮点误差的长尾
  return String(Number(n.toPrecision(12)))
}

/** 增减文字：客观描述，不做“好转/恶化”判断 */
export function changeText(change: TrendChange | null | undefined): string {
  if (!change) return '—'
  if (change.note === 'SAME_DAY_AMBIGUOUS') return '同日多条，不计算'
  if (change.absolute == null) return '—'
  const abs = change.absolute
  const sign = abs > 0 ? '+' : ''
  const absText = `${sign}${formatNumber(abs)}`
  if (change.note === 'PREVIOUS_ZERO' || change.percent == null) return `${absText}（前值为 0，不计百分比）`
  const pSign = change.percent > 0 ? '+' : ''
  return `${absText}（${pSign}${formatNumber(change.percent)}%）`
}

export function referenceText(p: Pick<TrendPoint, 'referenceText' | 'referenceLow' | 'referenceHigh'>): string {
  if (p.referenceText) return p.referenceText
  if (p.referenceLow != null && p.referenceHigh != null) {
    return `${formatNumber(p.referenceLow)}-${formatNumber(p.referenceHigh)}`
  }
  if (p.referenceLow != null) return `≥${formatNumber(p.referenceLow)}`
  if (p.referenceHigh != null) return `≤${formatNumber(p.referenceHigh)}`
  return '未提供'
}

function median(values: number[]): number {
  const s = [...values].sort((a, b) => a - b)
  const mid = Math.floor(s.length / 2)
  return s.length % 2 ? s[mid]! : (s[mid - 1]! + s[mid]!) / 2
}

/** 返回需要断线的位置：索引 i 表示第 i-1 与第 i 个点之间断开 */
export function gapBreaks(times: number[]): Set<number> {
  const breaks = new Set<number>()
  if (times.length < GAP_MIN_POINTS) return breaks
  const intervals: number[] = []
  for (let i = 1; i < times.length; i++) intervals.push(times[i]! - times[i - 1]!)
  const usual = median(intervals.filter((v) => v > 0))
  if (!usual) return breaks
  intervals.forEach((v, idx) => {
    if (v > usual * GAP_FACTOR) breaks.add(idx + 1)
  })
  return breaks
}

/** Y 轴范围：覆盖所有数值与参考上下界，再上下各留 12% 余量 */
export function yExtent(points: TrendPoint[]): { min: number; max: number } {
  const values: number[] = []
  for (const p of points) {
    values.push(p.numericValue)
    if (p.referenceLow != null) values.push(p.referenceLow)
    if (p.referenceHigh != null) values.push(p.referenceHigh)
  }
  if (!values.length) return { min: 0, max: 1 }
  let min = Math.min(...values)
  let max = Math.max(...values)
  const span = max - min
  const pad = span > 0 ? span * Y_PAD_RATIO : Math.abs(max) * 0.1 || 1
  min -= pad
  max += pad
  // 全部数值非负时下界不低于 0，避免出现无意义的负刻度
  if (values.every((v) => v >= 0)) min = Math.max(0, min)
  return { min: Number(min.toPrecision(6)), max: Number(max.toPrecision(6)) }
}

type RefKey = string
function refKey(p: TrendPoint): RefKey {
  return `${p.referenceLow ?? ''}|${p.referenceHigh ?? ''}`
}

/** 参考范围：全部一致且上下界齐全 → markArea 色带；否则按连续一致的区段画虚线 markLine */
function referenceMarks(points: TrendPoint[]): Pick<LineSeriesOption, 'markArea' | 'markLine'> {
  const withRef = points.filter((p) => p.referenceLow != null || p.referenceHigh != null)
  if (!withRef.length) return {}
  const allSame = points.every((p) => refKey(p) === refKey(points[0]!))
  const first = points[0]!
  if (allSame && first.referenceLow != null && first.referenceHigh != null) {
    return {
      markArea: {
        silent: true,
        itemStyle: { color: COLORS.band },
        data: [[{ yAxis: first.referenceLow }, { yAxis: first.referenceHigh }] as [{ yAxis: number }, { yAxis: number }]],
      },
    }
  }

  // 按连续相同的参考范围分段；单点区段左右各延伸半天，保证可见
  const segments: Array<[{ coord: [number, number] }, { coord: [number, number] }]> = []
  let start = 0
  for (let i = 1; i <= points.length; i++) {
    if (i < points.length && refKey(points[i]!) === refKey(points[start]!)) continue
    const run = points.slice(start, i)
    const p = run[0]!
    let t0 = dateToTime(p.examinationDate)
    let t1 = dateToTime(run[run.length - 1]!.examinationDate)
    if (t0 === t1) {
      t0 -= DAY_MS / 2
      t1 += DAY_MS / 2
    }
    for (const bound of [p.referenceLow, p.referenceHigh]) {
      if (bound != null) segments.push([{ coord: [t0, bound] }, { coord: [t1, bound] }])
    }
    start = i
  }
  return {
    markLine: {
      silent: true,
      symbol: ['none', 'none'],
      label: { show: false },
      lineStyle: { type: 'dashed' as const, color: COLORS.bandLine, width: 1 },
      data: segments,
    },
  }
}

export function buildTrendOption(group: TrendGroup, range: DateRange, metricName: string): ChartOption {
  const points = [...group.points].sort((a, b) => dateToTime(a.examinationDate) - dateToTime(b.examinationDate))
  const unit = group.unitText ?? ''
  const times = points.map((p) => dateToTime(p.examinationDate))
  const breaks = gapBreaks(times)

  // 断线：在两点中间插入值为 null 的数据项，配合 connectNulls:false 断开，不做插值
  const data: Array<unknown> = []
  points.forEach((p, i) => {
    if (breaks.has(i)) data.push({ value: [(times[i - 1]! + times[i]!) / 2, null], symbol: 'none' })
    const color = flagColor(p.abnormalFlag)
    data.push({
      value: [times[i]!, p.numericValue],
      ...flagSymbol(p.abnormalFlag),
      symbolSize: p.abnormalFlag === 'HIGH' || p.abnormalFlag === 'LOW' ? 12 : 9,
      itemStyle: { color, borderColor: '#fff', borderWidth: 1 },
      point: p,
    })
  })

  const { min, max } = yExtent(points)

  return {
    animationDuration: 400,
    aria: {
      enabled: true,
      label: {
        description: `${metricName}趋势图，共 ${points.length} 个数值点，单位 ${unit || '未标注'}。完整数据见图下表格。`,
      },
    },
    grid: { left: 12, right: 20, top: 24, bottom: 12, containLabel: true },
    tooltip: {
      trigger: 'item',
      confine: true,
      formatter: (params: unknown) => {
        const p = (params as { data?: { point?: TrendPoint } }).data?.point
        if (!p) return ''
        return [
          `<strong>${p.examinationDate}</strong>`,
          `结果：${escapeHtml(p.originalValue)} ${escapeHtml(unit)}`,
          `参考：${escapeHtml(referenceText(p))}`,
          `状态：${flagText(p.abnormalFlag)}`,
          `与上次相比：${changeText(p.change)}`,
        ].join('<br/>')
      },
    },
    xAxis: {
      type: 'time',
      min: dateToTime(range.from),
      max: dateToTime(range.to),
      axisLine: { lineStyle: { color: COLORS.split } },
      axisLabel: { color: COLORS.axis, hideOverlap: true },
      splitLine: { show: false },
    },
    yAxis: {
      type: 'value',
      min,
      max,
      name: unit,
      nameTextStyle: { color: COLORS.axis, align: 'left' },
      axisLabel: { color: COLORS.axis },
      splitLine: { lineStyle: { color: COLORS.split, type: 'dashed' } },
    },
    series: [
      {
        type: 'line',
        name: metricName,
        connectNulls: false,
        showAllSymbol: true,
        lineStyle: { color: COLORS.line, width: 2 },
        itemStyle: { color: COLORS.line },
        emphasis: { scale: 1.4 },
        data: data as LineData,
        ...referenceMarks(points),
      },
    ],
  }
}

type LineData = LineSeriesOption['data']

function escapeHtml(s: string): string {
  return s.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]!)
}
