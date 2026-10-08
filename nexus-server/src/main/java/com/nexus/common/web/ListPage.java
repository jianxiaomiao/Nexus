package com.nexus.common.web;

import com.baomidou.mybatisplus.core.metadata.IPage;

import java.util.List;
import java.util.function.Function;

public record ListPage<T>(long total, long current, long size, List<T> records) {
    public static <S, T> ListPage<T> map(IPage<S> page, Function<S, T> mapper) {
        return new ListPage<>(page.getTotal(), page.getCurrent(), page.getSize(),
                page.getRecords().stream().map(mapper).toList());
    }
}
