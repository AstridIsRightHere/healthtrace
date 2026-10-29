package com.healthtrace.common.web;

import java.util.List;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * 列表响应：{@code items} 为本次返回的条目（受 limit 限制），{@code total} 为忽略 limit 时满足筛选条件的总数。
 */
public record ListPage<T>(List<T> items, long total) {

    public static final int MAX_LIMIT = 100;

    /**
     * 解析可选的 limit 查询参数：缺省返回 null（表示全部）；须为 1..100 的整数，否则 400 HT-1001。
     * 以字符串接收，使非数字也走字段校验错误而不是 HT-1002。
     */
    public static Integer parseLimit(String raw) {
        if (raw == null) {
            return null;
        }
        int value;
        try {
            value = Integer.parseInt(raw.strip());
        } catch (NumberFormatException e) {
            throw invalidLimit();
        }
        if (value < 1 || value > MAX_LIMIT) {
            throw invalidLimit();
        }
        return value;
    }

    /**
     * 组装分页结果。未截断（limit 为空或返回条数少于 limit）时总数即条数，省去 COUNT 查询；
     * 否则执行调用方提供的 COUNT。
     */
    public static <R, T> ListPage<T> of(List<R> rows, Integer limit, LongSupplier count, Function<R, T> mapper) {
        long total = limit == null || rows.size() < limit ? rows.size() : count.getAsLong();
        return new ListPage<>(rows.stream().map(mapper).toList(), total);
    }

    public <U> ListPage<U> map(Function<List<T>, List<U>> fn) {
        return new ListPage<>(fn.apply(items), total);
    }

    private static ApiException invalidLimit() {
        String message = "limit 须为 1 到 " + MAX_LIMIT + " 之间的整数";
        return new ApiException(ErrorCode.VALIDATION_FAILED, message,
                List.of(new FieldErrorItem("limit", "Range", message)));
    }
}
