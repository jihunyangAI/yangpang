package com.yangpang.domain.claim;

/** 클레임 관련 코드값 모음 */
public final class ClaimEnums {

    private ClaimEnums() {
    }

    public enum Type {
        CANCEL("취소"), RETURN("반품"), EXCHANGE("교환");

        private final String label;

        Type(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum Status {
        REQUESTED("접수"), PICKING_UP("회수중"), PICKED_UP("회수완료·검수중"), COMPLETED("완료"), WITHDRAWN("철회"),
        /** 판매자가 검수 후 거절 → 상품을 고객에게 다시 보냄 */
        REJECTED("판매자 거절");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** 사유. 고객 귀책이면 반품비를 고객이 낸다 */
    public enum Reason {
        CHANGE_OF_MIND("단순 변심", Fault.CUSTOMER),
        WRONG_OPTION("옵션을 잘못 선택함", Fault.CUSTOMER),
        DEFECTIVE("상품 불량", Fault.SELLER),
        DAMAGED("배송 중 파손", Fault.SELLER),
        WRONG_ITEM("다른 상품이 옴", Fault.SELLER),
        DELAYED("배송 지연", Fault.SELLER);

        private final String label;
        private final Fault fault;

        Reason(String label, Fault fault) {
            this.label = label;
            this.fault = fault;
        }

        public String label() {
            return label;
        }

        public Fault fault() {
            return fault;
        }

        /** 불량·파손 상품은 다시 팔 수 없어 재고로 되돌리지 않는다 (리퍼마켓은 M3) */
        public boolean restocks() {
            return this != DEFECTIVE && this != DAMAGED;
        }
    }

    public enum Fault {CUSTOMER, SELLER}

    public enum PickupMethod {
        DOOR("문 앞 회수"), CONVENIENCE("편의점 반품");

        private final String label;

        PickupMethod(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }
}
