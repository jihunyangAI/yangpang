package com.yangpang.domain.subscription;

import com.yangpang.domain.catalog.ItemCondition;
import com.yangpang.domain.catalog.VendorItem;
import com.yangpang.domain.catalog.VendorItemRepository;
import com.yangpang.domain.common.BusinessException;
import com.yangpang.domain.delivery.ArrivalEstimator;
import com.yangpang.domain.member.Address;
import com.yangpang.domain.member.AddressRepository;
import com.yangpang.domain.membership.Membership;
import com.yangpang.domain.membership.MembershipService;
import com.yangpang.domain.notification.Notification;
import com.yangpang.domain.notification.NotificationService;
import com.yangpang.domain.order.CheckoutPricingService;
import com.yangpang.domain.order.MockPayment;
import com.yangpang.domain.order.Order;
import com.yangpang.domain.order.OrderItem;
import com.yangpang.domain.order.OrderRepository;
import com.yangpang.domain.order.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 정기배송 (M4 ④, 기획서 P-10·MY-09).
 * 신청하면 첫 회차를 바로 주문·결제(모의)하고, 이후에는 시뮬레이터가 배송 예정일 이틀 전에 주문한다.
 * 회차 주문은 일반 주문과 같은 경로(재고 차감·도착일·결제 후속)를 타고, 쿠폰·캐시는 쓰지 않으며 상품 금액의 5%를 깎는다.
 * 재고가 없거나 판매가 멈춘 회차는 건너뛰고 알린다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 마이양팡 정기배송 한 줄 */
    public record Row(Subscription sub, String productName, String optionText, String imageUrl, long unitPrice,
                      boolean soldOut, String addressText, List<Order> orders, int discountPercent) {
        public long roundPrice() {
            long amount = unitPrice * sub.getQuantity();
            return amount - OrderService.subscriptionDiscount(amount, discountPercent);
        }
    }

    /** 신청 화면용 미리보기 */
    public record Preview(VendorItem offer, int quantity, int cycleWeeks, long unitPrice, long amount, long discount,
                          LocalDate firstArrival) {
        public long pay() {
            return amount - discount;
        }
    }

    private final SubscriptionRepository repository;
    private final VendorItemRepository vendorItemRepository;
    private final AddressRepository addressRepository;
    private final OrderService orderService;
    private final OrderRepository orderRepository;
    private final MembershipService membershipService;
    private final com.yangpang.domain.policy.PolicyService policyService;
    private final NotificationService notificationService;
    private final ArrivalEstimator arrivalEstimator;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    // ------------------------------------------------------------------ 신청

    /** 정기배송을 고를 수 있는 상품인지: 재구매 주기가 있는 카테고리(식품·생활용품·뷰티·유아동·반려동물·건강식품)의 새 상품 */
    @Transactional(readOnly = true)
    public boolean subscribable(Long productId) {
        Integer days = jdbc.query("""
                select tc.repurchase_days from product p join category_tree t on t.id = p.category_id
                join category tc on tc.id = t.top_id where p.id = ?""", rs -> rs.next() ? (Integer) rs.getObject(1) : null, productId);
        return days != null;
    }

    @Transactional(readOnly = true)
    public Preview preview(Long memberId, boolean plusMember, Long vendorItemId, int quantity, int cycleWeeks) {
        Subscription.validateCycle(cycleWeeks);
        Subscription.validateQuantity(quantity);
        VendorItem vi = offer(vendorItemId);
        long unit = plusMember && vi.getPlusPrice() != null ? vi.getPlusPrice() : vi.getSalePrice();
        long amount = unit * quantity;
        return new Preview(vi, quantity, cycleWeeks, unit, amount, OrderService.subscriptionDiscount(amount, discountPercent()),
                arrivalEstimator.arrivalDate(vi.getDeliveryType(), com.yangpang.domain.common.Region.METRO));
    }

    /**
     * 정기배송 신청 → 첫 회차를 바로 주문·결제.
     * 첫 회차가 실패하면(재고 부족 등) 신청도 남기지 않는다.
     */
    public Subscription subscribe(Long memberId, Long vendorItemId, int quantity, int cycleWeeks, Long addressId,
                                  MockPayment.Method method, String customsCode) {
        Subscription.validateCycle(cycleWeeks);
        Subscription.validateQuantity(quantity);
        Subscription.validateMethod(method);
        Long subId = tx.execute(s -> {
            VendorItem vi = offer(vendorItemId);
            if (vi.isSoldOut()) throw new BusinessException("품절된 상품은 정기배송을 신청할 수 없어요");
            if (!subscribable(vi.getItem().getProduct().getId())) throw new BusinessException("정기배송을 고를 수 없는 상품이에요");
            if (repository.existsByMemberIdAndVendorItemIdAndStatus(memberId, vendorItemId, Subscription.Status.ACTIVE)) {
                throw new BusinessException("이미 정기배송 중인 상품이에요. 마이양팡 > 정기배송에서 수량·주기를 바꿔 주세요");
            }
            myAddress(memberId, addressId);
            return repository.save(Subscription.create(memberId, vendorItemId, vi.getItem().getProduct().getId(), quantity, cycleWeeks,
                    addressId, method, today(), clock.instant())).getId();
        });
        try {
            placeRound(subId, true, customsCode);
        } catch (BusinessException e) {
            tx.executeWithoutResult(s -> repository.deleteById(subId));
            throw e;
        }
        return repository.findById(subId).orElseThrow();
    }

    // ------------------------------------------------------------------ 회차 주문

    /** 시뮬레이터: 주문일이 된 정기배송을 주문·결제(모의). @return 주문한 회차 수 */
    public int runDue() {
        int n = 0;
        for (Long id : repository.findDueIds(today().plusDays(Subscription.ORDER_LEAD_DAYS))) {
            try {
                if (placeRound(id, false, null)) n++;
            } catch (RuntimeException e) {
                log.warn("[정기배송] #{} 회차 처리 실패: {}", id, e.getMessage());
            }
        }
        return n;
    }

    /**
     * 한 회차 주문. 주문 생성·결제는 OrderService가 각자 트랜잭션으로 처리하고, 실패하면 이번 회차를 건너뛴다.
     * @param first 신청 직후 첫 회차면 실패를 그대로 던진다 (신청 취소)
     * @return 주문했으면 true
     */
    boolean placeRound(Long subId, boolean first, String customsCode) {
        Subscription sub = repository.findById(subId).orElseThrow();
        if (!sub.active() || (!first && !sub.due(today()))) return false;
        boolean plus = membershipService.find(sub.getMemberId()).map(Membership::isBenefitActive).orElse(false);
        Long addressId = sub.getAddressId() != null ? sub.getAddressId()
                : addressRepository.findByMemberIdOrderByDefaultAddressDescIdAsc(sub.getMemberId()).stream().findFirst().map(Address::getId).orElse(null);
        Order order;
        try {
            if (addressId == null) throw new BusinessException("배송지가 없어요. 배송지를 등록해 주세요");
            VendorItem vi = vendorItemRepository.findAllWithDetails(List.of(sub.getVendorItemId())).stream().findFirst().orElse(null);
            if (vi == null || vi.getStatus() != com.yangpang.domain.catalog.ProductStatus.ON_SALE) throw new BusinessException("상품 판매가 멈췄어요");
            if (vi.getStock() < sub.getQuantity()) throw new BusinessException("재고가 부족해요 (남은 수량 " + vi.getStock() + "개)");
            order = orderService.place(new OrderService.PlaceOrder(sub.getMemberId(), plus, addressId, null,
                    List.of(new OrderService.Line(sub.getVendorItemId(), sub.getQuantity())),
                    new CheckoutPricingService.Selection(false, Map.of(), null, 0), null, null, sub.getId(), discountPercent(), customsCode));
        } catch (BusinessException e) {
            if (first) throw e;
            skipRound(subId, "이번 회차를 보내지 못했어요: " + e.getMessage());
            return false;
        }
        orderService.pay(sub.getMemberId(), order.getOrderNo(), sub.getMethod(), MockPayment.Status.APPROVED);
        LocalDate promised = order.getItems().stream().map(OrderItem::getPromisedDate).max(LocalDate::compareTo).orElse(today());
        int round = tx.execute(s -> {
            Subscription fresh = repository.findById(subId).orElseThrow();
            fresh.ordered(promised);
            return fresh.getRounds();
        });
        if (!first) {
            notificationService.notify(sub.getMemberId(), Notification.Type.ORDER, "정기배송 " + round + "회차를 주문했어요",
                    order.title() + " · " + String.format("%,d", order.getPaidAmount()) + "원 자동 결제 (모의) · " + arrivalEstimator.dayText(promised) + " 도착 예정",
                    "/my/orders/" + order.getOrderNo(), order.getItems().getFirst().getImageUrl());
        }
        return true;
    }

    private void skipRound(Long subId, String note) {
        Subscription sub = tx.execute(s -> {
            Subscription fresh = repository.findById(subId).orElseThrow();
            fresh.skip(note.length() > 200 ? note.substring(0, 200) : note);
            return fresh;
        });
        notificationService.notify(sub.getMemberId(), Notification.Type.ORDER, "정기배송 한 회차를 건너뛰었어요",
                note + " · 다음 배송은 " + arrivalEstimator.dayText(sub.getNextDeliveryDate()) + " 예정", "/my/subscriptions", null);
    }

    // ------------------------------------------------------------------ 관리 (MY-09)

    @Transactional(readOnly = true)
    public List<Row> mine(Long memberId) {
        List<Subscription> subs = repository.findByMemberIdOrderByStatusAscIdDesc(memberId);
        if (subs.isEmpty()) return List.of();
        boolean plus = membershipService.find(memberId).map(Membership::isBenefitActive).orElse(false);
        Map<Long, VendorItem> offers = vendorItemRepository.findAllWithDetails(subs.stream().map(Subscription::getVendorItemId).toList())
                .stream().collect(java.util.stream.Collectors.toMap(VendorItem::getId, v -> v));
        Map<Long, Address> addresses = addressRepository.findByMemberIdOrderByDefaultAddressDescIdAsc(memberId).stream()
                .collect(java.util.stream.Collectors.toMap(Address::getId, a -> a));
        Address fallback = addresses.values().stream().filter(Address::isDefaultAddress).findFirst().orElse(null);
        return subs.stream().map(s -> {
            VendorItem vi = offers.get(s.getVendorItemId());
            long unit = plus && vi.getPlusPrice() != null ? vi.getPlusPrice() : vi.getSalePrice();
            Address a = s.getAddressId() != null ? addresses.get(s.getAddressId()) : fallback;
            String addr = a == null ? "배송지 없음" : (s.getAddressId() == null ? "기본 배송지 · " : "") + a.getRecipient() + " · " + a.getAddress1();
            List<Order> orders = orderRepository.findBySubscription(s.getId()).stream().limit(5).toList();
            return new Row(s, vi.getItem().getProduct().getName(), vi.optionTextWithCondition(),
                    "/images/products/" + s.getProductId() + "/1.svg", unit, vi.isSoldOut(), addr, orders, discountPercent());
        }).toList();
    }

    @Transactional
    public void changeCycle(Long memberId, Long id, int weeks) {
        Subscription.validateCycle(weeks);
        activeOf(memberId, id).changeCycle(weeks, today());
    }

    @Transactional
    public void changeQuantity(Long memberId, Long id, int quantity) {
        Subscription.validateQuantity(quantity);
        Subscription s = activeOf(memberId, id);
        VendorItem vi = offer(s.getVendorItemId());
        if (quantity > vi.getMaxPerOrder()) throw new BusinessException("이 상품은 한 번에 " + vi.getMaxPerOrder() + "개까지 살 수 있어요");
        s.changeQuantity(quantity);
    }

    @Transactional
    public void changeAddress(Long memberId, Long id, Long addressId) {
        myAddress(memberId, addressId);
        activeOf(memberId, id).changeAddress(addressId);
    }

    @Transactional
    public void changeMethod(Long memberId, Long id, MockPayment.Method method) {
        Subscription.validateMethod(method);
        activeOf(memberId, id).changeMethod(method);
    }

    /** 다음 회차 한 번 건너뛰기 */
    @Transactional
    public Subscription skipNext(Long memberId, Long id) {
        Subscription s = activeOf(memberId, id);
        s.skip(null);
        return s;
    }

    @Transactional
    public void cancel(Long memberId, Long id) {
        activeOf(memberId, id).cancel(clock.instant());
    }

    @Transactional(readOnly = true)
    public Optional<Long> activeIdFor(Long memberId, Long vendorItemId) {
        return repository.findByMemberIdOrderByStatusAscIdDesc(memberId).stream()
                .filter(s -> s.active() && s.getVendorItemId().equals(vendorItemId)).map(Subscription::getId).findFirst();
    }

    // ------------------------------------------------------------------

    private Subscription activeOf(Long memberId, Long id) {
        Subscription s = repository.findByIdAndMemberId(id, memberId).orElseThrow(() -> new BusinessException("정기배송을 찾을 수 없어요"));
        if (!s.active()) throw new BusinessException("해지한 정기배송이에요");
        return s;
    }

    private VendorItem offer(Long vendorItemId) {
        VendorItem vi = vendorItemRepository.findAllWithDetails(List.of(vendorItemId)).stream().findFirst()
                .orElseThrow(() -> new BusinessException("상품을 찾을 수 없어요"));
        if (vi.getCondition() != ItemCondition.NEW) throw new BusinessException("리퍼·반품 상품은 정기배송을 신청할 수 없어요");
        return vi;
    }

    private void myAddress(Long memberId, Long addressId) {
        if (addressId == null) throw new BusinessException("배송지를 선택해 주세요");
        addressRepository.findById(addressId).filter(a -> a.getMember().getId().equals(memberId))
                .orElseThrow(() -> new BusinessException("배송지를 선택해 주세요"));
    }

    /** 정기배송 할인율 (관리자 설정) */
    public int discountPercent() {
        return policyService.getInt(com.yangpang.domain.policy.Policy.SUBSCRIPTION_DISCOUNT_PERCENT);
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(KST));
    }
}
