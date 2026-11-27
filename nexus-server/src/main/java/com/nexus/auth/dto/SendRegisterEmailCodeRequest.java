package com.nexus.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SendRegisterEmailCodeRequest(
        @NotBlank(message = "邮箱不能为空")
        @Email(message = "邮箱格式不正确")
        @Size(max = 254, message = "邮箱长度不能超过254个字符")
        String email,

        @NotBlank(message = "图形验证码标识不能为空")
        @Size(max = 64, message = "图形验证码标识过长")
        String captchaId,

        @NotBlank(message = "请输入图形验证码答案")
        @Size(max = 16, message = "图形验证码答案过长")
        String captchaAnswer
) {
}
