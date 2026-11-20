package com.yangpang.domain.order;

/** 주문(상품) 상태 (기획서 8.4) */
public enum OrderStatus {
    PENDING_PAYMENT("입금대기"),
    PAID("결제완료"),
    PREPARING("상품준비중"),
    SHIPPED("출고완료"),
    IN_TRANSIT("배송중"),
    DELIVERED("배송완료"),
    CONFIRMED("구매확정"),
    // ---- 반품·교환 진행 중 (주문 상품 단위) ----
    RETURN_REQUESTED("반품 신청"),
    RETURN_PICKING_UP("반품 회수중"),
    RETURN_PICKED_UP("반품 회수완료"),
    EXCHANGE_REQUESTED("교환 신청"),
    EXCHANGE_PICKING_UP("교환 회수중"),
    // ---- 끝난 상태 ----
    RETURNED("반품완료"),
    CANCELED("주문취소"),
    PAYMENT_FAILED("결제실패");

    private final String label;

    OrderStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /**
     * 진행 단계. 주문 전체 상태는 "진행 중인 상품 중 가장 작은 단계"로 정한다.
     * 반품·교환 진행 중은 배송 이후 단계(7~8)로 두어, 다른 상품이 아직 배송 중이면 주문은 배송중으로 보이게 한다.
     * 끝난 상태(반품완료·취소·결제실패)는 -1 (진행 중 아님).
     */
    public int step() {
        return switch (this) {
            case PENDING_PAYMENT -> 0;
            case PAID -> 1;
            case PREPARING -> 2;
            case SHIPPED -> 3;
            case IN_TRANSIT -> 4;
            case DELIVERED -> 5;
            case CONFIRMED -> 6;
            case RETURN_REQUESTED, RETURN_PICKING_UP, RETURN_PICKED_UP -> 7;
            case EXCHANGE_REQUESTED, EXCHANGE_PICKING_UP -> 8;
            case RETURNED, CANCELED, PAYMENT_FAILED -> -1;
        };
    }

    public boolean isInClaim() {
        return step() == 7 || step() == 8;
    }
}
