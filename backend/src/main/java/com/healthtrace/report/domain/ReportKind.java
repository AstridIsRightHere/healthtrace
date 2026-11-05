package com.healthtrace.report.domain;

/**
 * 报告种类（docs/32 任务二）。
 * <ul>
 *   <li>STRUCTURED：结构化检验报告（血常规、肝功能等），含观测值，可绘制趋势图。</li>
 *   <li>IMAGING：影像报告（超声、X光、CT等），只有结论文本，无结构化指标。</li>
 *   <li>UNSTRUCTURED：其他非结构化内容，保留后续扩展。</li>
 * </ul>
 */
public enum ReportKind {
    STRUCTURED,
    IMAGING,
    UNSTRUCTURED
}
