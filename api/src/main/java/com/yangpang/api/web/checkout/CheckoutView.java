package com.yangpang.api.web.checkout;

import com.yangpang.domain.member.Address;
import com.yangpang.domain.order.MockPayment;

import java.util.List;

/** 주문서 화면 데이터 (기획서 O-01, O-02) */
public record CheckoutView(
        String buyerName,
        String buyerEmail,
        String buyerPhone,
        List<Address> addresses,
        Address selectedAddress,
        List<Group> groups,
        long listAmount,
        long productAmount,
        long productCouponDiscount,
        long cartCouponDiscount,
        List<CouponOption> cartCouponOptions,
        Long chosenCartCoupon,
        long cashBalance,
        long cashUsed,
        long shippingFee,
        long payAmount,
        List<String> warnings,
        List<MockPayment.Method> paymentMethods,
        List<String> requestNotes,
        boolean needsCustomsCode,   // 양팡직구 상품이 있음
        String savedCustomsCode,    // 지난번에 쓴 개인통관고유부호
        List<InstallOption> installDates   // 양팡설치 상품이 있으면 고를 수 있는 설치일 (없으면 빈 목록)
) {
    public record InstallOption(java.time.LocalDate date, String label) {
    }

    public boolean needsInstallDate() {
        return !installDates.isEmpty();
    }

    public long productDiscount() {
        return listAmount - productAmount;
    }

    public long couponDiscount() {
        return productCouponDiscount + cartCouponDiscount;
    }

    public int itemCount() {
        return groups.stream().mapToInt(g -> g.lines().size()).sum();
    }

    public long maxCash() {
        return Math.min(cashBalance, productAmount - couponDiscount());
    }

    public record Group(String label, String arrivalText, long fee, List<Line> lines) {
    }

    /** @param index 주문서 안 상품 순서 (쿠폰 선택 값의 이름 pc_{index}) */
    public record Line(int index, Long productId, String productName, String optionText, String imageUrl, int quantity,
                       long salePrice, long lineAmount, List<CouponOption> couponOptions, Long chosenCoupon,
                       long couponDiscount) {
    }

    /** 쿠폰 선택지 하나. discount는 이 상품(또는 주문)에 썼을 때 할인액 */
    public record CouponOption(long memberCouponId, String name, String benefit, long discount) {
    }
}
