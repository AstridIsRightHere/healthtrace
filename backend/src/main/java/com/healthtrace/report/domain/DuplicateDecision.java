package com.healthtrace.report.domain;

/**
 * 同档案出现完全相同文件时的处理（FR-005）。
 * ASK：命中重复时不保存，返回提示由用户决定；KEEP：用户确认后仍然保存。不会自动合并或删除。
 */
public enum DuplicateDecision {
    ASK,
    KEEP
}
