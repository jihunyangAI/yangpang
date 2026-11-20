package com.yangpang.domain.common;

/** 공동현관 출입 방법 */
public enum EntranceType {
    FREE("자유출입 가능"),
    PASSWORD("공동현관 비밀번호"),
    CALL("경비실 호출"),
    ETC("기타");

    private final String label;

    EntranceType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
