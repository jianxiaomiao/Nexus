package com.nexus.usage.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

public enum TimeRange {
    TODAY("今天"),
    YESTERDAY("昨天"),
    LAST_7_DAYS("近7天"),
    LAST_30_DAYS("近30天"),
    THIS_MONTH("本月"),
    LAST_MONTH("上月"),
    CUSTOM("自定义");

    private final String label;

    TimeRange(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    // 返回 [start, end) 左闭右开时间区间，和数据库occurredAt匹配
    public record TimeRangeBound(LocalDateTime start, LocalDateTime end) {}

    public TimeRangeBound getBound(LocalDate now) {
        return switch (this) {
            case TODAY -> new TimeRangeBound(now.atStartOfDay(), now.plusDays(1).atStartOfDay());
            case YESTERDAY -> new TimeRangeBound(now.minusDays(1).atStartOfDay(), now.atStartOfDay());
            case LAST_7_DAYS -> new TimeRangeBound(now.minusDays(6).atStartOfDay(), now.plusDays(1).atStartOfDay());
            case LAST_30_DAYS -> new TimeRangeBound(now.minusDays(29).atStartOfDay(), now.plusDays(1).atStartOfDay());
            case THIS_MONTH -> {
                LocalDate firstDay = now.withDayOfMonth(1);
                LocalDate nextMonth = firstDay.plusMonths(1);
                yield new TimeRangeBound(firstDay.atStartOfDay(), nextMonth.atStartOfDay());
            }
            case LAST_MONTH -> {
                LocalDate lastMonthFirst = now.minusMonths(1).withDayOfMonth(1);
                LocalDate thisMonthFirst = now.withDayOfMonth(1);
                yield new TimeRangeBound(lastMonthFirst.atStartOfDay(), thisMonthFirst.atStartOfDay());
            }
            case CUSTOM -> throw new IllegalArgumentException("CUSTOM 需要外部传入起止时间");
        };
    }
}
