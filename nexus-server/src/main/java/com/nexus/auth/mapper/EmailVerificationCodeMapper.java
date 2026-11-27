package com.nexus.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nexus.auth.entity.EmailVerificationCode;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface EmailVerificationCodeMapper extends BaseMapper<EmailVerificationCode> {
}
