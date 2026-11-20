package com.yangpang.simulator;

import com.yangpang.domain.claim.Claim;
import com.yangpang.domain.claim.ClaimEnums;
import com.yangpang.domain.claim.ClaimRepository;
import com.yangpang.domain.claim.ClaimService;
import com.yangpang.domain.coupon.CashLedger;
import com.yangpang.domain.coupon.CashService;
import com.yangpang.domain.order.MockPayment;
import com.yangpang.domain.order.MockPaymentRepository;
import com.yangpang.domain.order.Order;
import com.yangpang.domain.order.OrderItem;
import com.yangpang.domain.order.OrderItemRepository;
import com.yangpang.domain.order.OrderProgressService;
import com.yangpang.domain.order.OrderRepository;
import com.yangpang.domain.order.OrderService;
import com.yangpang.domain.order.OrderStatus;
import com.yangpang.domain.common.DeliveryType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;

/**
 * 시뮬레이터 한 번 돌리기 (기획서 7.4).
 * 1) 결제하지 않은 주문 정리  2) 가상계좌 입금(일부)·기한 초과 취소  3) 배송 단계 진행·자동 구매확정
 * 각 상품은 OrderProgressService가 따로 트랜잭션으로 처리한다 (한 건 실패가 전체를 막지 않도록).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeliverySimulator {

    /** 결제 화면에서 이 시간 안에 결제하지 않으면 주문 취소·재고 복구 (기획서 O-04) */
    static final Duration PAYMENT_TIMEOUT = Duration.ofMinutes(10);
    /** 가상계좌 고객 10명 중 7명은 발급 2시간 뒤 입금한다고 가정 */
    static final Duration DEPOSIT_DELAY = Duration.ofHours(2);
    static final int BATCH = 300;

    public record Result(int expired, int deposited, int depositExpired, int advanced, int claims) {
        public int total() {
            return expired + deposited + depositExpired + advanced + claims;
        }
    }

    /** 반품·교환: 접수 →(12시간) 회수중 →(12시간) 회수완료 →(6시간) 완료 */
    /** 판매자배송 반품·교환은 회수 후 판매자가 검수할 시간(24시간)을 주고, 그때까지 처리하지 않으면 자동 승인 */
    static final Duration SELLER_DECISION_WINDOW = Duration.ofHours(24);

    static Duration claimStepDuration(ClaimEnums.Status s, boolean sellerDecides) {
        return switch (s) {
            case REQUESTED, PICKING_UP -> Duration.ofHours(12);
            case PICKED_UP -> sellerDecides ? SELLER_DECISION_WINDOW : Duration.ofHours(6);
            default -> null;
        };
    }

    private static final EnumSet<OrderStatus> PROGRESSING = EnumSet.of(
            OrderStatus.PAID, OrderStatus.PREPARING, OrderStatus.SHIPPED, OrderStatus.IN_TRANSIT, OrderStatus.DELIVERED);

    private final OrderRepository orderRepository;
    private final OrderItemRepository itemRepository;
    private final MockPaymentRepository paymentRepository;
    private final OrderProgressService progressService;
    private final OrderService orderService;
    private final ClaimService claimService;
    private final ClaimRepository claimRepository;
    private final CashService cashService;
    private final com.yangpang.domain.membership.MembershipService membershipService;
    private final com.yangpang.domain.deal.DealService dealService;
    private final com.yangpang.domain.notification.NotificationService notificationService;
    private final com.yangpang.domain.catalog.RankingService rankingService;
    private final com.yangpang.domain.reco.RecommendationService recommendationService;
    private final com.yangpang.domain.partners.PartnersService partnersService;
    private final com.yangpang.domain.subscription.SubscriptionService subscriptionService;
    private final com.yangpang.domain.gift.GiftService giftService;
    private final com.yangpang.domain.biz.TaxInvoiceService taxInvoiceService;
    private final com.yangpang.domain.seller.SettlementService settlementService;
    private final com.yangpang.domain.seller.SellerScoreService sellerScoreService;
    private final com.yangpang.domain.policy.PolicyService policyService;
    private final java.util.Random browseRandom = new java.util.Random();
    private java.time.Instant lastCashExpiryHour;
    private final Clock clock;

    public Result tick() {
        Instant now = clock.instant();
        int expired = 0, deposited = 0, depositExpired = 0, advanced = 0;

        for (Order o : orderRepository.findUnpaidBefore(now.minus(Duration.ofMinutes(policyService.get(com.yangpang.domain.policy.Policy.PAYMENT_TIMEOUT_MINUTES))))) {
            if (progressService.cancelUnpaid(o.getId(), "결제 시간 초과 (" + policyService.get(com.yangpang.domain.policy.Policy.PAYMENT_TIMEOUT_MINUTES) + "분) · 재고 복구")) expired++;
        }

        for (Order o : orderRepository.findAwaitingDeposit()) {
            MockPayment p = paymentRepository.findFirstByOrderIdOrderByIdDesc(o.getId()).orElse(null);
            if (p == null) continue;
            boolean willPay = o.getId() % 10 < 7;
            if (willPay && !now.isBefore(p.getCreatedAt().plus(DEPOSIT_DELAY))) {
                orderService.confirmDeposit(o.getOrderNo(), "SYSTEM");
                deposited++;
            } else if (!willPay && p.getDepositDeadline() != null && now.isAfter(p.getDepositDeadline())) {
                if (progressService.cancelUnpaid(o.getId(), "입금 기한 초과 · 재고 복구")) depositExpired++;
            }
        }

        // 한 번에 여러 단계가 밀려 있으면(빨리 감기) 따라잡을 때까지 반복.
        // 각 단계는 원래 일어났어야 할 시각으로 기록되므로 이력이 자연스럽다
        for (int round = 0; round < 6; round++) {
            int moved = advanceDueItems(now);
            advanced += moved;
            if (moved == 0) break;
        }

        int claims = 0;
        for (int round = 0; round < 3; round++) {
            int moved = 0;
            for (Claim c : claimRepository.findInProgress(EnumSet.of(ClaimEnums.Status.REQUESTED,
                    ClaimEnums.Status.PICKING_UP, ClaimEnums.Status.PICKED_UP), PageRequest.of(0, BATCH))) {
                Instant due = c.getStatusChangedAt().plus(claimStepDuration(c.getStatus(), c.getSellerId() != null));
                if (!now.isBefore(due) && claimService.advance(c.getId(), c.getStatus(), due)) moved++;
            }
            claims += moved;
            if (moved == 0) break;
        }
        // 교환 재출고된 상품이 있으면 배송도 이어서 진행
        if (claims > 0) advanced += advanceDueItems(now);

        // 양팡플러스: 기간이 끝난 멤버십 갱신(모의 청구) 또는 해지 종료
        int memberships = membershipService.processDue();
        if (memberships > 0) log.info("[시뮬레이터] 양팡플러스 갱신·종료 {}건", memberships);

        // 오늘의 특가: 07:00이 지나 이번 기간 특가가 없으면 새로 고른다
        dealService.rotateIfNeeded(false);

        // 찜 상품 가격 인하·재입고 알림
        notificationService.scanWishlist();

        // 양팡 랭킹 점수: 10분마다 다시 계산
        rankingService.recomputeIfStale();

        // 가상 회원 둘러보기(행동 로그) — 실제 5초마다 30% 확률로 한 명. 함께 본 상품은 30분마다 다시 계산
        if (browseRandom.nextDouble() < 0.3) recommendationService.simulateBrowsing(browseRandom);
        recommendationService.recomputeCoViewsIfStale();

        // 파트너스: 주문 상품이 모두 끝난 예정 실적 확정·취소
        int settled = partnersService.settle();
        if (settled > 0) log.info("[시뮬레이터] 파트너스 실적 확정·취소 {}건", settled);

        // 정기배송: 배송 예정일 이틀 전이 된 회차 주문·모의 결제 / 선물: 5일 안에 배송지를 받지 못하면 자동 취소·환불
        int rounds = subscriptionService.runDue();
        if (rounds > 0) log.info("[시뮬레이터] 정기배송 회차 주문 {}건", rounds);
        int gifts = giftService.expireDue();
        if (gifts > 0) log.info("[시뮬레이터] 선물 배송지 미입력 자동 취소 {}건", gifts);
        // 비즈: 세금계산서 요청 1시간 뒤 모의 발행
        int invoices = taxInvoiceService.issueDue();
        if (invoices > 0) log.info("[시뮬레이터] 세금계산서 모의 발행 {}건", invoices);
        // 판매자: 주 단위 정산·모의 지급, 하루 한 번 판매자 점수(→ 아이템 위너) 다시 계산
        settlementService.settleDue();
        sellerScoreService.recomputeIfStale();

        // 양팡캐시 소멸: 시뮬레이션 시각으로 1시간에 한 번
        java.time.Instant hour = now.truncatedTo(java.time.temporal.ChronoUnit.HOURS);
        if (!hour.equals(lastCashExpiryHour)) {
            lastCashExpiryHour = hour;
            int expiredMembers = cashService.expireDue();
            if (expiredMembers > 0) log.info("[시뮬레이터] 양팡캐시 소멸 {}명", expiredMembers);
        }

        Result r = new Result(expired, deposited, depositExpired, advanced, claims);
        if (r.total() > 0) log.info("[시뮬레이터] {} 기준: 미결제취소 {}, 입금 {}, 입금기한취소 {}, 배송진행 {}, 반품·교환 {}",
                now, expired, deposited, depositExpired, advanced, claims);
        return r;
    }

    private int advanceDueItems(Instant now) {
        List<Candidate> due = loadDue(now);
        int moved = 0;
        for (Candidate c : due) {
            String actor = (c.status == OrderStatus.PREPARING || c.status == OrderStatus.PAID) && c.seller ? "SELLER" : "SYSTEM";
            String note = switch (c.status) {
                case PAID -> "발주 확인";
                case PREPARING -> "출고 (송장 등록)";
                case SHIPPED -> "배송 출발";
                case IN_TRANSIT -> c.install ? "방문 설치 완료" : delayed(c) ? "배송 완료 (하루 지연)" : "배송 완료 · 문 앞 사진 등록";
                case DELIVERED -> "자동 구매확정 (배송완료 7일 후)";
                default -> null;
            };
            if (progressService.advance(c.id, c.status, actor, note, c.dueAt)) {
                moved++;
                // 하루 늦게 도착한 상품은 양팡캐시로 보상 (기획서 5.9)
                if (c.status == OrderStatus.IN_TRANSIT && delayed(c)) {
                    cashService.earnOnce(c.memberId, policyService.get(com.yangpang.domain.policy.Policy.DELAY_COMPENSATION_CASH), CashLedger.Type.EARN_DELAY, c.orderId,
                            "배송 지연 보상 · 주문상품 #" + c.id);
                }
            }
        }
        return moved;
    }

    private record Candidate(long id, OrderStatus status, boolean seller, Instant dueAt, long orderId, long memberId, boolean install) {
    }

    /** 하루 늦게 도착하는 상품 (설치 상품은 약속일을 지킴) */
    private static boolean delayed(Candidate c) {
        return !c.install && DeliveryTimeline.isDelayed(c.id);
    }

    /** 지금 다음 단계로 넘어가야 하는 상품들 */
    private List<Candidate> loadDue(Instant now) {
        return itemRepository.findByStatuses(PROGRESSING, PageRequest.of(0, BATCH)).stream()
                .filter(i -> DeliveryTimeline.isDue(toTimeline(i), now))
                .map(i -> new Candidate(i.getId(), i.getStatus(), i.getDeliveryType() == DeliveryType.SELLER,
                        DeliveryTimeline.nextDueAt(toTimeline(i)), i.getOrder().getId(), i.getOrder().getMemberId(), i.getDeliveryType() == DeliveryType.INSTALL))
                .toList();
    }

    static DeliveryTimeline.Item toTimeline(OrderItem i) {
        return new DeliveryTimeline.Item(i.getId(), i.getStatus(), i.getStatusChangedAt(), i.getDeliveryType(),
                i.getPromisedDate(), i.getDeliveredAt());
    }
}
