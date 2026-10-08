package com.nexus.usage.mapper;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
public class UsageDailyCountRow {
    private LocalDate date;
    private Long callCount;
}
