package com.nexus.usage.mapper;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class UsageApiCountRow {
    private String apiCode;
    private Long callCount;
}
