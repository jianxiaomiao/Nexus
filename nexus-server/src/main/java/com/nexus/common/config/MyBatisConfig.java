package com.nexus.common.config;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan(
        basePackages = "com.nexus",
        markerInterface = BaseMapper.class
)
public class MyBatisConfig {
}