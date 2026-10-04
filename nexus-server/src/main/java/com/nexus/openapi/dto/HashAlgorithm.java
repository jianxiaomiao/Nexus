package com.nexus.openapi.dto;

public enum HashAlgorithm {
    SHA256("SHA-256"),
    SHA512("SHA-512");

    private final String javaAlgorithmName;

    HashAlgorithm(String javaAlgorithmName) {
        this.javaAlgorithmName = javaAlgorithmName;
    }

    public String getJavaAlgorithmName() {
        return javaAlgorithmName;
    }
}