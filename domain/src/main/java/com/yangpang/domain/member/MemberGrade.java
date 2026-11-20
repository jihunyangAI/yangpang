package com.yangpang.domain.member;

public enum MemberGrade {
    NORMAL("일반"),
    PLUS("양팡플러스"),
    BIZ("비즈");

    private final String label;

    MemberGrade(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
