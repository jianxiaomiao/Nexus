package com.nexus.auth.service;

public interface EmailSender {
    void sendVerificationCode(String toEmail, String code, int ttlMinutes);
}
