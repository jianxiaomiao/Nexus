package com.nexus.auth.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nexus.auth.entity.EmailVerificationCode;
import com.nexus.auth.mapper.EmailVerificationCodeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class EmailVerificationAttemptService {

    private final EmailVerificationCodeMapper codeMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailedAttempt(long codeId) {
        codeMapper.update(null, Wrappers.<EmailVerificationCode>lambdaUpdate()
                .eq(EmailVerificationCode::getId, codeId)
                .isNull(EmailVerificationCode::getConsumedAt)
                .setSql("attempt_count = attempt_count + 1"));
    }
}
