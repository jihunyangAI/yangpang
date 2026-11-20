package com.yangpang.domain.delivery;

/**
 * 배송비 기준 (기획서 8.2). 값은 나중에 관리자 설정으로 옮긴다.
 * - 양팡 물류(양팡배송·프레시·판매자양팡): 일반 회원은 최종 결제금액 19,800원 이상 무료, 플러스 회원은 항상 무료
 * - 판매자배송: 판매자별 배송비·조건부 무료 기준
 */
/** 기본값 (실제 값은 관리자 설정 PolicyService, V27). 단위 테스트와 설정이 없을 때만 쓴다 */
public final class ShippingPolicy {

    private ShippingPolicy() {
    }

    public static final long YANGPANG_FREE_THRESHOLD = 19_800;
    public static final long YANGPANG_SHIPPING_FEE = 3_000;
    /** 양팡직구: 양팡배송과 따로 묶고 같은 기준(19,800원 이상 무료, 플러스 무료) */
    public static final long GLOBAL_SHIPPING_FEE = 3_000;
    /** 양팡직구 고객 귀책 반품비 (해외 반송, 묶음마다 한 번, 플러스도 부담) */
    public static final long GLOBAL_RETURN_FEE = 15_000;
}
