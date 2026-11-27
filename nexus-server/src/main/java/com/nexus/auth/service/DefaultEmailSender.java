package com.nexus.auth.service;

import com.nexus.auth.exception.EmailDeliveryException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
public class DefaultEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(DefaultEmailSender.class);

    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final String fromAddress;

    public DefaultEmailSender(
            ObjectProvider<JavaMailSender> mailSenderProvider,
            @Value("${spring.mail.username:}") String fromAddress) {
        this.mailSenderProvider = mailSenderProvider;
        this.fromAddress = fromAddress;
    }

    @Override
    public void sendVerificationCode(String toEmail, String code, int ttlMinutes) {
        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null || fromAddress == null || fromAddress.isBlank()) {
            log.info("SMTP 未配置，跳过实际发送注册验证码邮件: to={}", toEmail);
            return;
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(toEmail);
        message.setSubject("Nexus 注册验证码");
        message.setText("您好，您正在注册 Nexus 账号。\n\n您的邮箱验证码为：" + code
                + "\n\n验证码 " + ttlMinutes + " 分钟内有效，请勿泄露给他人。");
        try {
            mailSender.send(message);
        } catch (MailException exception) {
            throw new EmailDeliveryException(exception);
        }
    }
}
