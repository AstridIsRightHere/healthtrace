// ECharts 按需引入：只注册趋势图用到的图表、组件与渲染器，避免整包打入
import * as echarts from 'echarts/core'
import { LineChart, type LineSeriesOption } from 'echarts/charts'
import {
  AriaComponent,
  GridComponent,
  MarkAreaComponent,
  MarkLineComponent,
  TooltipComponent,
  type AriaComponentOption,
  type GridComponentOption,
  type MarkAreaComponentOption,
  type MarkLineComponentOption,
  type TooltipComponentOption,
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { ComposeOption } from 'echarts/core'

echarts.use([
  LineChart,
  GridComponent,
  TooltipComponent,
  MarkAreaComponent,
  MarkLineComponent,
  AriaComponent,
  CanvasRenderer,
])

/** 只包含已注册部件的 option 类型 */
export type ChartOption = ComposeOption<
  | LineSeriesOption
  | GridComponentOption
  | TooltipComponentOption
  | MarkAreaComponentOption
  | MarkLineComponentOption
  | AriaComponentOption
>

export { echarts }
