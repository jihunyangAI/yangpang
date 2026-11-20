package com.yangpang.domain.common;

/** 사용자에게 그대로 보여 줘도 되는 업무 규칙 위반 (예: 품절, 재고 부족) */
public class BusinessException extends RuntimeException {

    public BusinessException(String message) {
        super(message);
    }
}
