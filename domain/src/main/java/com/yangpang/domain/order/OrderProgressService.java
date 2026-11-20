package com.yangpang.domain.order;

import com.yangpang.domain.catalog.ProductSummaryService;
import com.yangpang.domain.catalog.VendorItemRepository;
import com.yangpang.domain.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.stream.Collectors;

/**
 * 주문 상품 단위 상태 진행 (기획서 8.4).
 * 시뮬레이터(배송 진행·자동 구매확정·미결제 취소)와 관리자 강제 처리가 함께 쓴다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class OrderProgressService {

    private final com.yangpang.domain.notification.NotificationService notificationService;
    private final com.yangpang.domain.deal.DealService dealService;
    private final OrderItemRepository itemRepository;
    private final OrderRepository orderRepository;
    private final OrderEventRepository eventRepository;
    private final VendorItemRepository vendorItemRepository;
    private final ProductSummaryService productSummaryService;
    private final MockPaymentRepository paymentRepository;
    private final com.yangpang.domain.coupon.CouponService couponService;
    private final com.yangpang.domain.coupon.CashService cashService;
    private final com.yangpang.domain.policy.PolicyService policyService;
    private final Clock clock;

    /** 배송 흐름상 바로 다음 단계 (결제완료 → 상품준비중 → 출고 → 배송중 → 배송완료 → 구매확정) */
    public static OrderStatus nextOf(OrderStatus s) {
        return switch (s) {
            case PAID -> OrderStatus.PREPARING;
            case PREPARING -> OrderStatus.SHIPPED;
            case SHIPPED -> OrderStatus.IN_TRANSIT;
            case IN_TRANSIT -> OrderStatus.DELIVERED;
            case DELIVERED -> OrderStatus.CONFIRMED;
            default -> null;
        };
    }

    /** 상품 하나를 지금 시각에 다음 단계로 (관리자 강제 진행 등) */
    public boolean advance(Long orderItemId, OrderStatus expectedCurrent, String actor, String note) {
        return advance(orderItemId, expectedCurrent, actor, note, clock.instant());
    }

    /**
     * 상품 하나를 다음 단계로. 이미 다른 곳에서 바뀌었으면(동시 처리) 아무것도 하지 않는다.
     * @param at 단계가 바뀐 시각. 시뮬레이터가 밀린 단계를 따라잡을 때는 원래 일어났어야 할 시각을 넘긴다 (지금보다 미래면 지금)
     */
    public boolean advance(Long orderItemId, OrderStatus expectedCurrent, String actor, String note, Instant at) {
        OrderItem item = itemRepository.findById(orderItemId).orElseThrow();
        if (item.getStatus() != expectedCurrent) return false;
        // 선물: 받는 분이 배송지를 입력하기 전에는 발주 확인(출고 준비)하지 않는다
        if (expectedCurrent == OrderStatus.PAID && item.getOrder().isGiftWaiting()) return false;
        OrderStatus next = nextOf(expectedCurrent);
        if (next == null) throw new BusinessException("더 진행할 수 없는 상태입니다: " + expectedCurrent.label());
        Instant now = clock.instant();
        Instant when = at.isAfter(now) ? now : at;
        item.getOrder().changeItemStatus(item, next, when);
        eventRepository.save(OrderEvent.ofItem(item, expectedCurrent, next, actor, note, when));
        if (next == OrderStatus.CONFIRMED) earnPurchaseCash(item);
        notifyDelivery(item, next);
        return true;
    }

    private void notifyDelivery(OrderItem item, OrderStatus next) {
        String title = switch (next) {
            case SHIPPED -> "상품이 출고되었어요";
            case DELIVERED -> item.getDeliveryType() == com.yangpang.domain.common.DeliveryType.INSTALL ? "방문 설치가 완료되었어요" : "배송이 완료되었어요";
            default -> null;
        };
        if (title == null) return;
        String body = item.getProductName() + (next == OrderStatus.DELIVERED ? " · 문 앞 사진을 확인해 보세요" : " · 곧 배송을 시작해요");
        notificationService.notify(item.getOrder().getMemberId(), com.yangpang.domain.notification.Notification.Type.DELIVERY, title, body,
                "/my/orders/" + item.getOrder().getOrderNo(), item.getImageUrl());
    }

    /** 구매확정 적립: 양페이로 결제한 금액(캐시로 낸 부분 제외)의 1% */
    private void earnPurchaseCash(OrderItem item) {
        Order order = item.getOrder();
        boolean yangpay = paymentRepository.findFirstByOrderIdOrderByIdDesc(order.getId())
                .map(p -> p.getMethod() == MockPayment.Method.YANGPAY).orElse(false);
        if (!yangpay) return;
        long base = item.paidAmount() - item.getCashAmount();
        cashService.earnOnce(order.getMemberId(), base * policyService.get(com.yangpang.domain.policy.Policy.PURCHASE_CASH_PERCENT) / 100,
                com.yangpang.domain.coupon.CashLedger.Type.EARN_PURCHASE, order.getId(),
                "구매확정 적립 · " + item.getProductName());
    }

    /** 결제하지 않은 주문 취소 + 재고 복구 (입금 기한·결제 시간 초과) */
    public boolean cancelUnpaid(Long orderId, String note) {
        Order order = orderRepository.findById(orderId).orElseThrow();
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) return false;
        Instant now = clock.instant();
        order.changeStatus(OrderStatus.CANCELED, now);
        eventRepository.save(OrderEvent.of(order.getId(), OrderStatus.PENDING_PAYMENT, OrderStatus.CANCELED, "SYSTEM", note, now));
        order.getItems().forEach(i -> vendorItemRepository.increaseStock(i.getVendorItemId(), i.getQuantity()));
        dealService.release(order.getItems());
        productSummaryService.refresh(order.getItems().stream().map(OrderItem::getProductId).collect(Collectors.toSet()));
        // 쓴 쿠폰·캐시 돌려주기
        couponService.restoreForOrder(order.getId());
        cashService.giveBack(order.getMemberId(), order.getCashUsed(), com.yangpang.domain.coupon.CashLedger.Type.USE_CANCEL,
                order.getId(), "주문 " + order.getOrderNo() + " 취소로 사용 취소");
        notificationService.notify(order.getMemberId(), com.yangpang.domain.notification.Notification.Type.ORDER, "주문이 취소되었어요", order.title() + " · " + note,
                "/my/orders/" + order.getOrderNo(), order.getItems().isEmpty() ? null : order.getItems().getFirst().getImageUrl());
        return true;
    }
}
