package com.yangpang.domain.claim;

import com.yangpang.domain.catalog.ProductSummaryService;
import com.yangpang.domain.catalog.VendorItem;
import com.yangpang.domain.catalog.VendorItemRepository;
import com.yangpang.domain.common.BusinessException;
import com.yangpang.domain.common.DeliveryType;
import com.yangpang.domain.delivery.ArrivalEstimator;
import com.yangpang.domain.delivery.ShippingPolicy;
import com.yangpang.domain.order.Order;
import com.yangpang.domain.order.OrderEvent;
import com.yangpang.domain.order.OrderEventRepository;
import com.yangpang.domain.order.OrderItem;
import com.yangpang.domain.order.OrderProgressService;
import com.yangpang.domain.order.OrderRepository;
import com.yangpang.domain.order.OrderStatus;
import com.yangpang.domain.seller.Seller;
import com.yangpang.domain.seller.SellerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 취소·반품·교환·환불 (기획서 5.10).
 * 취소는 즉시 완료, 반품·교환은 접수 → 회수중 → 회수완료(검수) → 완료 순서로 시뮬레이터가 진행한다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class ClaimService {

    private static final EnumSet<OrderStatus> CANCELLABLE = EnumSet.of(OrderStatus.PAID, OrderStatus.PREPARING);

    private final com.yangpang.domain.notification.NotificationService notificationService;
    private final com.yangpang.domain.catalog.RefurbService refurbService;
    private final com.yangpang.domain.deal.DealService dealService;
    private final ClaimRepository claimRepository;
    private final RefundRepository refundRepository;
    private final OrderRepository orderRepository;
    private final OrderEventRepository eventRepository;
    private final OrderProgressService progressService;
    private final VendorItemRepository vendorItemRepository;
    private final SellerRepository sellerRepository;
    private final ProductSummaryService productSummaryService;
    private final ArrivalEstimator arrivalEstimator;
    private final com.yangpang.domain.coupon.CashService cashService;
    private final com.yangpang.domain.coupon.CouponService couponService;
    private final com.yangpang.domain.policy.PolicyService policyService;
    private final Clock clock;

    // =====================================================================
    // 취소 (출고 전)
    // =====================================================================
    public Claim cancel(Long memberId, String orderNo, Collection<Long> orderItemIds, ClaimEnums.Reason reason, String detail) {
        Order order = myOrder(memberId, orderNo);
        Instant now = clock.instant();

        // 입금 전 주문은 통째로 취소 (환불할 돈 없음)
        if (order.getStatus() == OrderStatus.PENDING_PAYMENT) {
            Claim claim = newClaim(order, ClaimEnums.Type.CANCEL, reason, detail, null, 0, now);
            order.getItems().forEach(i -> claim.addItem(ClaimItem.create(claim, i, null, 0)));
            claim.changeStatus(ClaimEnums.Status.COMPLETED, now);
            claimRepository.save(claim);
            progressService.cancelUnpaid(order.getId(), "고객 주문 취소 (입금 전)");
            return claim;
        }

        return cancelPaid(order, pick(order, orderItemIds), reason, detail, "MEMBER", "고객 취소 · " + reason.label(), now);
    }

    /**
     * 시스템 취소 (선물 배송지 미입력 등): 출고 전 상품을 모두 취소하고 전액 환불(모의).
     * @return 취소했으면 true (이미 끝났거나 출고된 주문이면 false)
     */
    public boolean cancelBySystem(Long orderId, String detail) {
        Order order = orderRepository.findById(orderId).orElseThrow();
        if (order.getStatus() == OrderStatus.PENDING_PAYMENT) return progressService.cancelUnpaid(orderId, detail);
        List<OrderItem> items = order.getItems().stream().filter(i -> CANCELLABLE.contains(i.getStatus())).toList();
        if (items.isEmpty()) return false;
        cancelPaid(order, items, ClaimEnums.Reason.CHANGE_OF_MIND, detail, "SYSTEM", detail, clock.instant());
        return true;
    }

    private Claim cancelPaid(Order order, List<OrderItem> items, ClaimEnums.Reason reason, String detail, String actor,
                             String eventNote, Instant now) {
        for (OrderItem i : items) {
            if (!CANCELLABLE.contains(i.getStatus())) {
                throw new BusinessException("'" + i.getProductName() + "'은(는) 이미 출고되어 취소할 수 없어요. 배송 완료 후 반품을 신청해 주세요");
            }
        }
        RefundCalculator.Result refund = RefundCalculator.calculate(toRefundItems(items), order.getShippingFee(), 0,
                order.allEndedExcept(ids(items)), 0);

        Claim claim = newClaim(order, ClaimEnums.Type.CANCEL, reason, detail, null, 0, now);
        for (OrderItem i : items) {
            claim.addItem(ClaimItem.create(claim, i, null, i.paidAmount()));
            OrderStatus prev = i.getStatus();
            order.changeItemStatus(i, OrderStatus.CANCELED, now);
            eventRepository.save(OrderEvent.ofItem(i, prev, OrderStatus.CANCELED, actor, eventNote, now));
            vendorItemRepository.increaseStock(i.getVendorItemId(), i.getQuantity());
        }
        dealService.release(items); // 출고 전 취소면 특가 수량도 되돌린다
        claim.setRefund(refund.total(), 0);
        claim.changeStatus(ClaimEnums.Status.COMPLETED, now);
        claimRepository.save(claim);
        refund(claim, order, refund.total(), items, now);
        productSummaryService.refresh(productIds(items));
        notifyClaim(claim, order, "주문 취소가 완료되었어요", String.format("%,d", refund.total()) + "원 환불 (모의)");
        return claim;
    }

    // =====================================================================
    // 반품 신청 (배송완료 후)
    // =====================================================================
    public Claim requestReturn(Long memberId, boolean plusMember, String orderNo, Collection<Long> orderItemIds,
                               ClaimEnums.Reason reason, String detail, ClaimEnums.PickupMethod pickup) {
        Order order = myOrder(memberId, orderNo);
        List<OrderItem> items = pick(order, orderItemIds);
        Instant now = clock.instant();
        for (OrderItem i : items) checkReturnable(i, plusMember, now, "반품");

        long fee = returnFee(items, reason, plusMember, 1);
        RefundCalculator.Result estimate = RefundCalculator.calculate(toRefundItems(items), order.getShippingFee(), 0,
                order.allEndedExcept(ids(items)), fee);

        Claim claim = newClaim(order, ClaimEnums.Type.RETURN, reason, detail, pickup, fee, now);
        for (OrderItem i : items) {
            claim.addItem(ClaimItem.create(claim, i, null, i.paidAmount()));
            moveItem(order, i, OrderStatus.RETURN_REQUESTED, "MEMBER", "반품 신청 · " + reason.label(), now);
        }
        claim.setRefund(estimate.total(), estimate.returnFee());
        claim.assignSeller(soleSeller(items));
        return claimRepository.save(claim);
    }

    // =====================================================================
    // 교환 신청 (배송완료 후, 같은 상품의 다른 옵션)
    // =====================================================================
    public Claim requestExchange(Long memberId, boolean plusMember, String orderNo, Long orderItemId, Long newVendorItemId,
                                 ClaimEnums.Reason reason, String detail, ClaimEnums.PickupMethod pickup) {
        Order order = myOrder(memberId, orderNo);
        OrderItem item = pick(order, List.of(orderItemId)).getFirst();
        Instant now = clock.instant();
        checkReturnable(item, plusMember, now, "교환");

        VendorItem target = vendorItemRepository.findAllWithDetails(List.of(newVendorItemId)).stream().findFirst()
                .orElseThrow(() -> new BusinessException("교환할 옵션을 골라 주세요"));
        if (!target.getItem().getProduct().getId().equals(item.getProductId())) {
            throw new BusinessException("같은 상품의 다른 옵션으로만 교환할 수 있어요");
        }
        if (target.isRefurb()) throw new BusinessException("리퍼 상품으로는 교환할 수 없어요");
        if (target.getSalePrice() != item.getUnitPrice()) {
            throw new BusinessException("가격이 다른 옵션으로는 교환할 수 없어요. 반품 후 다시 주문해 주세요");
        }
        // 새로 보낼 상품 재고를 미리 잡아 둔다
        if (vendorItemRepository.decreaseStock(target.getId(), item.getQuantity()) == 0) {
            throw new BusinessException("교환할 옵션의 재고가 부족해요");
        }
        long fee = returnFee(List.of(item), reason, plusMember, 2); // 고객 귀책 교환은 왕복 배송비

        Claim claim = newClaim(order, ClaimEnums.Type.EXCHANGE, reason, detail, pickup, fee, now);
        claim.addItem(ClaimItem.create(claim, item, target.getId(), 0));
        claim.assignSeller(soleSeller(List.of(item)));
        moveItem(order, item, OrderStatus.EXCHANGE_REQUESTED, "MEMBER",
                "교환 신청 → " + target.getItem().optionText() + " · " + reason.label(), now);
        claimRepository.save(claim);
        productSummaryService.refresh(Set.of(item.getProductId()));
        return claim;
    }

    /** 반품·교환 철회 (회수 전까지) */
    public void withdraw(Long memberId, String claimNo) {
        Claim claim = claimRepository.findByClaimNoAndMemberId(claimNo, memberId)
                .orElseThrow(() -> new BusinessException("신청 내역을 찾을 수 없어요"));
        if (!claim.isWithdrawable()) throw new BusinessException("이미 회수가 시작되어 철회할 수 없어요");
        Order order = orderRepository.findById(claim.getOrderId()).orElseThrow();
        Instant now = clock.instant();
        for (ClaimItem ci : claim.getItems()) {
            moveItem(order, ci.getOrderItem(), OrderStatus.DELIVERED, "MEMBER", claim.getType().label() + " 철회", now);
            if (ci.getExchangeVendorItemId() != null) {
                vendorItemRepository.increaseStock(ci.getExchangeVendorItemId(), ci.getOrderItem().getQuantity());
            }
        }
        claim.changeStatus(ClaimEnums.Status.WITHDRAWN, now);
    }

    // =====================================================================
    // 진행 (시뮬레이터·관리자)
    // =====================================================================
    /** 다음 단계로: 접수 → 회수중 → 회수완료 → 완료(환불 또는 재발송) */
    public boolean advance(Long claimId, ClaimEnums.Status expected, Instant at) {
        Claim claim = claimRepository.findById(claimId).orElseThrow();
        if (claim.getStatus() != expected || claim.getType() == ClaimEnums.Type.CANCEL) return false;
        Instant now = clock.instant();
        Instant when = at.isAfter(now) ? now : at;
        Order order = orderRepository.findById(claim.getOrderId()).orElseThrow();
        boolean isReturn = claim.getType() == ClaimEnums.Type.RETURN;

        switch (expected) {
            case REQUESTED -> {
                claim.changeStatus(ClaimEnums.Status.PICKING_UP, when);
                claim.getItems().forEach(ci -> moveItem(order, ci.getOrderItem(),
                        isReturn ? OrderStatus.RETURN_PICKING_UP : OrderStatus.EXCHANGE_PICKING_UP, "SYSTEM",
                        "회수 기사 배정 (" + (claim.getPickupMethod() == null ? "문 앞" : claim.getPickupMethod().label()) + ")", when));
            }
            case PICKING_UP -> {
                claim.changeStatus(ClaimEnums.Status.PICKED_UP, when);
                if (isReturn) {
                    claim.getItems().forEach(ci -> moveItem(order, ci.getOrderItem(), OrderStatus.RETURN_PICKED_UP,
                            "SYSTEM", "회수 완료 · 검수중", when));
                } else {
                    eventRepository.save(OrderEvent.of(order.getId(), OrderStatus.EXCHANGE_PICKING_UP,
                            OrderStatus.EXCHANGE_PICKING_UP, "SYSTEM", "교환 상품 회수 완료 · 검수중", when));
                }
            }
            case PICKED_UP -> {
                if (claim.getSellerId() != null) claim.decide("SYSTEM", null); // 판매자가 기한 안에 처리하지 않아 자동 승인
                if (isReturn) completeReturn(claim, order, when);
                else completeExchange(claim, order, when);
                claim.changeStatus(ClaimEnums.Status.COMPLETED, when);
                notifyClaim(claim, order, isReturn ? "반품이 완료되었어요" : "교환 상품을 다시 보냈어요",
                        isReturn ? String.format("%,d", claim.getRefundAmount()) + "원 환불 (모의)" : "새 옵션으로 출고했어요");
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    // =====================================================================
    // 판매자 검수 (셀러센터): 회수 완료된 판매자배송 반품·교환을 승인하거나 거절
    // =====================================================================
    public void sellerApprove(Long sellerId, String claimNo) {
        Claim claim = sellerClaim(sellerId, claimNo);
        if (!claim.awaitingSeller()) throw new BusinessException("회수가 끝난(검수 대기) 신청만 승인할 수 있어요");
        Instant now = clock.instant();
        Order order = orderRepository.findById(claim.getOrderId()).orElseThrow();
        boolean isReturn = claim.getType() == ClaimEnums.Type.RETURN;
        claim.decide("SELLER", null);
        if (isReturn) completeReturn(claim, order, now);
        else completeExchange(claim, order, now);
        claim.changeStatus(ClaimEnums.Status.COMPLETED, now);
        notifyClaim(claim, order, isReturn ? "판매자가 반품을 승인했어요" : "판매자가 교환 상품을 다시 보냈어요",
                isReturn ? String.format("%,d", claim.getRefundAmount()) + "원 환불 (모의)" : "새 옵션으로 출고했어요");
    }

    /** 거절: 고객 귀책 신청만. 회수한 상품을 고객에게 다시 보내고(배송완료로 되돌림) 교환용으로 잡아 둔 재고를 푼다 */
    public void sellerReject(Long sellerId, String claimNo, String reason) {
        Claim claim = sellerClaim(sellerId, claimNo);
        if (!claim.awaitingSeller()) throw new BusinessException("회수가 끝난(검수 대기) 신청만 거절할 수 있어요");
        if (!claim.rejectable()) throw new BusinessException("판매자 귀책 사유(불량·파손·오배송·지연)는 거절할 수 없어요");
        String r = reason == null ? "" : reason.trim();
        if (r.length() < 5) throw new BusinessException("거절 사유를 5자 이상 써 주세요");
        if (r.length() > 300) r = r.substring(0, 300);
        Instant now = clock.instant();
        Order order = orderRepository.findById(claim.getOrderId()).orElseThrow();
        for (ClaimItem ci : claim.getItems()) {
            moveItem(order, ci.getOrderItem(), OrderStatus.DELIVERED, "SELLER",
                    claim.getType().label() + " 거절 · 상품 재발송 (" + r + ")", now);
            if (ci.getExchangeVendorItemId() != null) {
                vendorItemRepository.increaseStock(ci.getExchangeVendorItemId(), ci.getOrderItem().getQuantity());
            }
        }
        claim.setRefund(0, 0);
        claim.decide("SELLER", r);
        claim.changeStatus(ClaimEnums.Status.REJECTED, now);
        notifyClaim(claim, order, "판매자가 " + claim.getType().label() + " 신청을 거절했어요", "사유: " + r);
    }

    private Claim sellerClaim(Long sellerId, String claimNo) {
        return claimRepository.findByClaimNo(claimNo).filter(c -> sellerId.equals(c.getSellerId()))
                .orElseThrow(() -> new BusinessException("내 상품의 신청이 아니에요"));
    }

    /** 담긴 상품이 모두 한 판매자의 판매자배송이면 그 판매자, 아니면 null(양팡이 처리) */
    private static Long soleSeller(List<OrderItem> items) {
        Set<Long> sellers = items.stream().map(OrderItem::getSellerId).collect(Collectors.toSet());
        boolean allSellerShipped = items.stream().noneMatch(i -> i.getDeliveryType().isYangpangLogistics());
        return sellers.size() == 1 && allSellerShipped ? sellers.iterator().next() : null;
    }

    private void completeReturn(Claim claim, Order order, Instant when) {
        Set<Long> productIds = new LinkedHashSet<>();
        for (ClaimItem ci : claim.getItems()) {
            OrderItem i = ci.getOrderItem();
            moveItem(order, i, OrderStatus.RETURNED, "SYSTEM", "검수 완료 · 반품 완료", when);
            if (claim.getReason().restocks()) {
                vendorItemRepository.increaseStock(i.getVendorItemId(), i.getQuantity());
                productIds.add(i.getProductId());
            } else if (refurbService.fromReturn(i, claim.getReason()) != null) {
                // 불량·파손 반품은 새 상품 재고로 돌리지 않고 검수 등급을 매겨 리퍼마켓으로
                productIds.add(i.getProductId());
            }
        }
        // 확정 환불액: 이제 모든 상품이 끝났는지 다시 보고 배송비 환불 여부를 정한다
        RefundCalculator.Result r = RefundCalculator.calculate(
                claim.getItems().stream().map(ci -> new RefundCalculator.ItemRefund(ci.getOrderItem().getId(),
                        ci.getOrderItem().paidAmount())).toList(),
                order.getShippingFee(), 0, order.allEndedExcept(Set.of()), claim.getReturnShippingFee());
        claim.setRefund(r.total(), r.returnFee());
        refund(claim, order, r.total(), claim.getItems().stream().map(ClaimItem::getOrderItem).toList(), when);
        productSummaryService.refresh(productIds);
    }

    private void completeExchange(Claim claim, Order order, Instant when) {
        ClaimItem ci = claim.getItems().getFirst();
        OrderItem item = ci.getOrderItem();
        VendorItem target = vendorItemRepository.findAllWithDetails(List.of(ci.getExchangeVendorItemId())).getFirst();
        Long oldVendorItemId = item.getVendorItemId();
        if (claim.getReason().restocks()) {
            vendorItemRepository.increaseStock(oldVendorItemId, item.getQuantity());
        }
        item.switchOffer(target.getId(), target.getItem().optionText());
        item.repromise(arrivalEstimator.arrivalDate(item.getDeliveryType(), order.getRegion()));
        // 새 상품을 다시 출고 → 이후 배송은 시뮬레이터가 평소처럼 진행
        moveItem(order, item, OrderStatus.SHIPPED, "SYSTEM", "교환 상품 재출고 (" + target.getItem().optionText() + ")", when);
        productSummaryService.refresh(Set.of(item.getProductId()));
    }

    // =====================================================================
    // 도우미
    // =====================================================================
    private void checkReturnable(OrderItem i, boolean plusMember, Instant now, String what) {
        if (i.getStatus() != OrderStatus.DELIVERED) {
            throw new BusinessException("'" + i.getProductName() + "'은(는) 배송 완료 후(구매확정 전)에만 " + what + "할 수 있어요");
        }
        if (i.getDeliveryType() == DeliveryType.FRESH) {
            throw new BusinessException("신선식품은 " + what + "할 수 없어요. 상품에 문제가 있으면 고객센터로 문의해 주세요");
        }
        Duration window = policyService.days(plusMember && i.getDeliveryType().isYangpangLogistics() ? com.yangpang.domain.policy.Policy.PLUS_RETURN_WINDOW_DAYS : com.yangpang.domain.policy.Policy.RETURN_WINDOW_DAYS);
        if (i.getDeliveredAt() != null && now.isAfter(i.getDeliveredAt().plus(window))) {
            throw new BusinessException(what + " 신청 기간(배송완료 후 " + window.toDays() + "일)이 지났어요");
        }
    }

    /**
     * 고객 귀책 반품비. 양팡 물류 상품은 3,000원(플러스 회원 무료), 양팡직구는 해외 반송 15,000원, 판매자배송은 판매자 배송비. 배송 묶음마다 한 번.
     * @param ways 1=편도(반품), 2=왕복(교환)
     */
    private long returnFee(List<OrderItem> items, ClaimEnums.Reason reason, boolean plusMember, int ways) {
        if (reason.fault() == ClaimEnums.Fault.SELLER) return 0;
        long fee = 0;
        boolean yangpang = items.stream().anyMatch(i -> i.getDeliveryType().isYangpangLogistics());
        if (yangpang && !plusMember) fee += policyService.get(com.yangpang.domain.policy.Policy.SHIPPING_FEE);
        if (items.stream().anyMatch(i -> i.getDeliveryType() == com.yangpang.domain.common.DeliveryType.GLOBAL)) {
            fee += policyService.get(com.yangpang.domain.policy.Policy.GLOBAL_RETURN_FEE); // 해외 반송 (플러스도 부담)
        }
        Set<Long> sellerIds = items.stream().filter(i -> !i.getDeliveryType().isYangpangLogistics()
                        && i.getDeliveryType() != com.yangpang.domain.common.DeliveryType.GLOBAL)
                .map(OrderItem::getSellerId).collect(Collectors.toSet());
        for (Seller s : sellerRepository.findAllById(sellerIds)) fee += s.getShippingFee();
        return fee * ways;
    }

    /**
     * 환불: 캐시로 냈던 몫은 양팡캐시로, 나머지는 원결제수단(모의)으로.
     * 주문의 모든 상품이 끝나면(전체 취소·반품) 쓴 쿠폰도 돌려준다.
     */
    private void notifyClaim(Claim claim, Order order, String title, String detail) {
        notificationService.notify(order.getMemberId(), com.yangpang.domain.notification.Notification.Type.CLAIM, title, order.title() + " · " + detail,
                "/my/claims/" + claim.getClaimNo(), order.getItems().isEmpty() ? null : order.getItems().getFirst().getImageUrl());
    }

    private void refund(Claim claim, Order order, long amount, List<OrderItem> items, Instant now) {
        if (amount > 0) {
            long cashPart = Math.min(amount, items.stream().mapToLong(OrderItem::getCashAmount).sum());
            long moneyPart = amount - cashPart;
            if (cashPart > 0) {
                refundRepository.save(Refund.completed(claim.getId(), order.getId(), cashPart, Refund.Method.CASH, now));
                cashService.giveBack(order.getMemberId(), cashPart, com.yangpang.domain.coupon.CashLedger.Type.REFUND,
                        order.getId(), claim.getType().label() + " 환불 (" + claim.getClaimNo() + ")");
            }
            if (moneyPart > 0) {
                refundRepository.save(Refund.completed(claim.getId(), order.getId(), moneyPart, Refund.Method.ORIGINAL, now));
            }
            order.addRefund(amount);
        }
        if (order.allEndedExcept(Set.of())) couponService.restoreForOrder(order.getId());
    }

    private void moveItem(Order order, OrderItem i, OrderStatus next, String actor, String note, Instant now) {
        OrderStatus prev = i.getStatus();
        order.changeItemStatus(i, next, now);
        eventRepository.save(OrderEvent.ofItem(i, prev, next, actor, note, now));
    }

    private Claim newClaim(Order order, ClaimEnums.Type type, ClaimEnums.Reason reason, String detail,
                           ClaimEnums.PickupMethod pickup, long fee, Instant now) {
        String no = "C" + now.atZone(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.ofPattern("yyMMdd"))
                + String.format("%07d", claimRepository.nextSequence());
        String trimmed = detail == null || detail.isBlank() ? null : detail.trim();
        if (trimmed != null && trimmed.length() > 500) trimmed = trimmed.substring(0, 500);
        return Claim.create(no, order.getId(), order.getMemberId(), type, reason, trimmed, pickup, fee, now);
    }

    private Order myOrder(Long memberId, String orderNo) {
        return orderRepository.findWithItems(orderNo, memberId)
                .orElseThrow(() -> new BusinessException("주문을 찾을 수 없어요"));
    }

    private static List<OrderItem> pick(Order order, Collection<Long> orderItemIds) {
        if (orderItemIds == null || orderItemIds.isEmpty()) throw new BusinessException("상품을 선택해 주세요");
        List<OrderItem> items = order.getItems().stream().filter(i -> orderItemIds.contains(i.getId())).toList();
        if (items.size() != new LinkedHashSet<>(orderItemIds).size()) throw new BusinessException("주문에 없는 상품이 있어요");
        return items;
    }

    private static List<RefundCalculator.ItemRefund> toRefundItems(List<OrderItem> items) {
        return items.stream().map(i -> new RefundCalculator.ItemRefund(i.getId(), i.paidAmount())).toList();
    }

    private static Set<Long> ids(List<OrderItem> items) {
        return items.stream().map(OrderItem::getId).collect(Collectors.toSet());
    }

    private static Set<Long> productIds(List<OrderItem> items) {
        return items.stream().map(OrderItem::getProductId).collect(Collectors.toSet());
    }
}
