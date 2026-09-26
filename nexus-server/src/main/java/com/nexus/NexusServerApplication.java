package com.nexus;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class NexusServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(NexusServerApplication.class, args);
    }

}
