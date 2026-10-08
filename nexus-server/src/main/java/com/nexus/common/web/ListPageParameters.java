package com.nexus.common.web;

public final class ListPageParameters {
    private ListPageParameters() {
    }

    public static void validate(long current, long size) {
        if (current < 1 || size < 1 || size > 100) {
            throw new InvalidListPageException();
        }
    }
}
