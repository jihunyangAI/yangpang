package com.yangpang.domain.order;

import com.yangpang.domain.catalog.ProductRepository;
import com.yangpang.domain.catalog.ProductSummaryService;
import com.yangpang.domain.catalog.VendorItem;
import com.yangpang.domain.catalog.VendorItemRepository;
import com.yangpang.domain.common.BusinessException;
import com.yangpang.domain.delivery.ArrivalEstimator;
import com.yangpang.domain.coupon.CashService;
import com.yangpang.domain.coupon.CouponService;
import com.yangpang.domain.member.Address;
import com.yangpang.domain.member.AddressRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 주문 생성과 모의 결제 (기획서 5.6, 6장).
 * 주문 생성 시 재고를 바로 차감(선점)하고, 결제 실패 시 되돌린다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class OrderService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter ORDER_NO_DATE = DateTimeFormatter.ofPattern("yyMMdd");

    public record Line(Long vendorItemId, int quantity) {
    }

    /** 선물하기: 받는 분 이름·연락처와 메시지. 배송지는 받는 분이 링크에서 입력한다 */
    public record GiftInfo(String senderName, String recipientName, String recipientPhone, String message) {
    }

    /**
     * @param expectedAmount 고객이 주문서에서 본 최종 결제금액 (null이면 확인 안 함)
     * @param gift           선물이면 받는 분 정보 (이때 addressId는 쓰지 않는다)
     * @param subscriptionId 정기배송 회차 주문이면 그 정기배송
     * @param subscriptionDiscountPercent 정기배송 할인율 (상품 금액에서 깎아 10원 단위로 내림)
     * @param customsCode    직구 상품이 있으면 개인통관고유부호 (비우면 회원에게 저장된 번호)
     */
    public record PlaceOrder(Long memberId, boolean plusMember, Long addressId, String requestNote, List<Line> lines,
                             CheckoutPricingService.Selection selection, Long expectedAmount,
                             GiftInfo gift, Long subscriptionId, int subscriptionDiscountPercent, String customsCode,
                             LocalDate installDate) {
        public PlaceOrder(Long memberId, boolean plusMember, Long addressId, String requestNote, List<Line> lines,
                          CheckoutPricingService.Selection selection, Long expectedAmount, GiftInfo gift, Long subscriptionId,
                          int subscriptionDiscountPercent, String customsCode) {
            this(memberId, plusMember, addressId, requestNote, lines, selection, expectedAmount, gift, subscriptionId,
                    subscriptionDiscountPercent, customsCode, null);
        }

        public PlaceOrder(Long memberId, boolean plusMember, Long addressId, String requestNote, List<Line> lines,
                          CheckoutPricingService.Selection selection, Long expectedAmount) {
            this(memberId, plusMember, addressId, requestNote, lines, selection, expectedAmount, null, null, 0, null);
        }

        public PlaceOrder(Long memberId, boolean plusMember, Long addressId, String requestNote, List<Line> lines,
                          CheckoutPricingService.Selection selection) {
            this(memberId, plusMember, addressId, requestNote, lines, selection, null);
        }
    }

    /** 정기배송 할인액: 상품 금액 x 할인율, 10원 단위 내림 */
    public static long subscriptionDiscount(long amount, int percent) {
        return percent <= 0 ? 0 : amount * percent / 100 / 10 * 10;
    }

    private final com.yangpang.domain.notification.NotificationService notificationService;
    private final com.yangpang.domain.deal.DealService dealService;
    private final OrderRepository orderRepository;
    private final GiftRepository giftRepository;
    private final com.yangpang.domain.event.InviteService inviteService;
    private final com.yangpang.domain.policy.PolicyService policyService;
    private final com.yangpang.domain.member.MemberRepository memberRepository;
    private final MockPaymentRepository paymentRepository;
    private final OrderEventRepository eventRepository;
    private final VendorItemRepository vendorItemRepository;
    private final ProductRepository productRepository;
    private final AddressRepository addressRepository;
    private final ProductSummaryService productSummaryService;
    private final ArrivalEstimator arrivalEstimator;
    private final CheckoutPricingService pricingService;
    private final CouponService couponService;
    private final CashService cashService;
    private final com.yangpang.domain.reco.UserEventService userEventService;
    private final com.yangpang.domain.ad.AdService adService;
    private final com.yangpang.domain.partners.PartnersService partnersService;
    private final Clock clock;

    /** 주문 생성 (입금대기). 쿠폰·캐시를 적용하고 재고를 차감한다 */
    public Order place(PlaceOrder cmd) {
        if (cmd.lines().isEmpty()) throw new BusinessException("주문할 상품이 없습니다");
        Address address = cmd.gift() != null ? null : addressRepository.findById(cmd.addressId() == null ? -1L : cmd.addressId())
                .filter(a -> a.getMember().getId().equals(cmd.memberId()))
                .orElseThrow(() -> new BusinessException("배송지를 선택해 주세요"));
        if (cmd.gift() != null) validateGift(cmd.gift());
        com.yangpang.domain.common.Region region = address == null ? com.yangpang.domain.common.Region.METRO : address.getRegion();

        List<CheckoutPricingService.Line> pricingLines = cmd.lines().stream()
                .map(l -> new CheckoutPricingService.Line(l.vendorItemId(), l.quantity())).toList();
        CheckoutPricingService.Pricing pricing = pricingService.price(cmd.memberId(), cmd.plusMember(), pricingLines,
                cmd.selection() == null ? CheckoutPricingService.Selection.automatic() : cmd.selection());
        Map<Long, VendorItem> offers = pricing.offers();
        if (cmd.expectedAmount() != null && cmd.expectedAmount() != pricing.payAmount()) {
            // 주문서를 연 뒤 특가가 다 팔렸거나 가격·쿠폰이 바뀐 경우: 모르는 사이 다른 금액으로 주문되지 않게 막는다
            throw new BusinessException("그사이 주문 금액이 " + String.format("%,d", cmd.expectedAmount()) + "원 → "
                    + String.format("%,d", pricing.payAmount()) + "원으로 바뀌었어요 (특가 소진 등). 주문서를 다시 확인해 주세요");
        }

        for (Line l : cmd.lines()) {
            VendorItem vi = offers.get(l.vendorItemId());
            if (l.quantity() < 1 || l.quantity() > vi.getMaxPerOrder()) {
                throw new BusinessException("'" + vi.getItem().getProduct().getName() + "'은(는) 한 번에 "
                        + vi.getMaxPerOrder() + "개까지 살 수 있습니다");
            }
        }

        Instant now = clock.instant();
        Order order = cmd.gift() != null
                ? Order.createGift(nextOrderNo(now), cmd.memberId(), cmd.gift().recipientName().trim(),
                        normalizePhone(cmd.gift().recipientPhone()), pricing.shipping().totalFee(), now)
                : Order.create(nextOrderNo(now), cmd.memberId(), address, cmd.requestNote(), pricing.shipping().totalFee(), now);
        if (cmd.subscriptionId() != null) order.markSubscription(cmd.subscriptionId());
        LocalDate installDate = null;
        if (offers.values().stream().anyMatch(v -> v.getDeliveryType() == com.yangpang.domain.common.DeliveryType.INSTALL)) {
            installDate = cmd.installDate();
            if (installDate == null) throw new BusinessException("양팡설치 상품은 방문 설치일을 골라 주세요");
            if (!installDates(region).contains(installDate)) throw new BusinessException("고를 수 없는 설치일이에요. 설치일을 다시 골라 주세요");
            order.setInstallDate(installDate);
        }
        if (offers.values().stream().anyMatch(v -> v.getDeliveryType() == com.yangpang.domain.common.DeliveryType.GLOBAL)) {
            var member = memberRepository.findById(cmd.memberId()).orElseThrow();
            String code = cmd.customsCode() == null || cmd.customsCode().isBlank() ? member.getCustomsCode() : normalizeCustomsCode(cmd.customsCode());
            if (code == null) throw new BusinessException("양팡직구 상품은 개인통관고유부호가 필요해요");
            if (!validCustomsCode(code)) throw new BusinessException("개인통관고유부호는 P로 시작하는 13자리예요 (예: P123456789012)");
            order.setCustomsCode(code);
            member.rememberCustomsCode(code);
        }

        Set<Long> productIds = new LinkedHashSet<>();
        for (int i = 0; i < cmd.lines().size(); i++) {
            Line l = cmd.lines().get(i);
            VendorItem vi = offers.get(l.vendorItemId());
            // 원자적 재고 차감: 그 사이 다른 주문이 재고를 가져갔으면 실패 → 트랜잭션 전체 취소
            if (vendorItemRepository.decreaseStock(vi.getId(), l.quantity()) == 0) {
                throw new BusinessException("'" + vi.getItem().getProduct().getName() + "'의 재고가 부족합니다");
            }
            var product = vi.getItem().getProduct();
            productIds.add(product.getId());
            var up = pricing.unitPrices().get(i);
            if (up.deal() != null) dealService.reserve(up.deal(), cmd.memberId(), l.quantity(), product.getName());
            var d = pricing.discounts().line(i);
            LocalDate promised = vi.getDeliveryType() == com.yangpang.domain.common.DeliveryType.INSTALL && installDate != null
                    ? installDate : arrivalEstimator.arrivalDate(vi.getDeliveryType(), region);
            long extra = subscriptionDiscount(up.unit() * l.quantity(), cmd.subscriptionDiscountPercent());
            OrderItem oi = OrderItem.create(order, vi.getId(), product.getId(), vi.getSeller().getId(),
                    product.getName(), vi.optionTextWithCondition(), "/images/products/" + product.getId() + "/1.svg",
                    vi.getDeliveryType(), up.unit(), l.quantity(), promised,
                    d.productCouponId(), d.discount() + extra, d.cash());
            oi.setPlusSaving(pricing.plusSavings().getOrDefault(i, 0L));
            if (up.deal() != null) oi.setDealId(up.deal().getId());
            order.addItem(oi);
        }
        if (pricing.discounts().cartCouponId() != null) order.useCartCoupon(pricing.discounts().cartCouponId());
        order.setShippingSaved(pricing.shippingSaved());

        orderRepository.save(order);
        if (cmd.gift() != null) {
            String message = cmd.gift().message() == null || cmd.gift().message().isBlank() ? null : cmd.gift().message().trim();
            giftRepository.save(Gift.create(order.getId(), cmd.gift().senderName(), order.getRecipient(), order.getRecipientPhone(),
                    message, now, policyService.days(com.yangpang.domain.policy.Policy.GIFT_ACCEPT_DAYS)));
        }

        // 쿠폰 사용 표시 · 캐시 차감
        java.util.List<Long> usedCoupons = new java.util.ArrayList<>(pricing.chosenProductCoupons().values());
        if (pricing.chosenCartCoupon() != null) usedCoupons.add(pricing.chosenCartCoupon());
        couponService.use(cmd.memberId(), usedCoupons, order.getId());
        cashService.use(cmd.memberId(), order.getCashUsed(), order.getId());

        eventRepository.save(OrderEvent.of(order.getId(), null, OrderStatus.PENDING_PAYMENT, "MEMBER", "주문서 작성", now));
        productSummaryService.refresh(productIds); // 재고가 0이 되면 품절 표시
        return order;
    }

    /** 모의 결제 결과 반영 */
    public MockPayment pay(Long memberId, String orderNo, MockPayment.Method method, MockPayment.Status result) {
        Order order = orderRepository.findByOrderNoAndMemberId(orderNo, memberId)
                .orElseThrow(() -> new BusinessException("주문을 찾을 수 없습니다"));
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            throw new BusinessException("이미 처리된 주문입니다");
        }
        Instant now = clock.instant();
        MockPayment payment = paymentRepository.save(
                MockPayment.of(order.getId(), method, order.getPaidAmount(), result, now));

        switch (result) {
            case APPROVED -> {
                transition(order, OrderStatus.PAID, "MEMBER", "모의 결제 승인 (" + method.label() + ")", now);
                order.getItems().forEach(i -> productRepository.increaseSales(i.getProductId(), i.getQuantity()));
                onPaid(order);
                notifyOrder(order, "주문이 완료되었어요", order.title() + " · " + won(order.getPaidAmount()) + "원 결제");
            }
            case FAILED -> {
                transition(order, OrderStatus.PAYMENT_FAILED, "MEMBER", "모의 결제 실패", now);
                restoreStock(order);
                restoreBenefits(order);
            }
            case PENDING -> {
                eventRepository.save(OrderEvent.of(order.getId(), OrderStatus.PENDING_PAYMENT,
                        OrderStatus.PENDING_PAYMENT, "MEMBER", "가상계좌 발급 (모의)", now));
                notifyOrder(order, "입금을 기다리고 있어요", order.title() + " · " + won(order.getPaidAmount()) + "원 · 24시간 안에 입금해 주세요 (모의)");
            }
        }
        return payment;
    }

    /** 무통장 입금 확인 (모의: 운영자·시뮬레이터가 호출) */
    public void confirmDeposit(String orderNo, String actor) {
        Order order = orderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> new BusinessException("주문을 찾을 수 없습니다"));
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) throw new BusinessException("입금대기 주문이 아닙니다");
        Instant now = clock.instant();
        transition(order, OrderStatus.PAID, actor, "입금 확인 (모의)", now);
        order.getItems().forEach(i -> productRepository.increaseSales(i.getProductId(), i.getQuantity()));
        onPaid(order);
        notifyOrder(order, "입금이 확인되었어요", order.title() + " · 상품 준비를 시작할게요");
    }

    /** 결제 완료 후속: 행동 로그, 광고 전환, 파트너스 예정 실적 */
    private void onPaid(Order order) {
        userEventService.ordered(order.getMemberId(), order.getItems().stream().map(OrderItem::getProductId).toList());
        adService.attributeOrder(order.getMemberId(), order.getId(), order.getItems().stream()
                .collect(java.util.stream.Collectors.groupingBy(OrderItem::getProductId, java.util.stream.Collectors.summingLong(OrderItem::paidAmount))));
        partnersService.onPaid(order.getId());
        inviteService.firstPaid(order.getMemberId()); // 초대받은 회원의 첫 결제면 초대한 회원 보상
        if (order.isGiftWaiting()) {
            giftRepository.findByOrderId(order.getId()).ifPresent(g -> {
                g.restartWindow(clock.instant(), policyService.days(com.yangpang.domain.policy.Policy.GIFT_ACCEPT_DAYS)); // 입금이 늦은 가상계좌도 결제 시점부터
                notificationService.notify(order.getMemberId(), com.yangpang.domain.notification.Notification.Type.ORDER,
                        "선물 링크를 보냈어요 (모의 문자)", g.getRecipientName() + "님(" + g.maskedPhone() + ")이 배송지를 입력하면 출고돼요 · " + policyService.get(com.yangpang.domain.policy.Policy.GIFT_ACCEPT_DAYS) + "일 안에 입력하지 않으면 자동 취소",
                        "/my/orders/" + order.getOrderNo(), order.getItems().isEmpty() ? null : order.getItems().getFirst().getImageUrl());
            });
        }
    }

    private static void validateGift(GiftInfo g) {
        if (g.recipientName() == null || g.recipientName().isBlank() || g.recipientName().trim().length() > 50) {
            throw new BusinessException("선물 받는 분 이름을 입력해 주세요");
        }
        if (g.recipientPhone() == null || !g.recipientPhone().trim().matches("^01\\d-?\\d{3,4}-?\\d{4}$")) {
            throw new BusinessException("선물 받는 분 휴대폰 번호를 확인해 주세요 (예: 010-1234-5678)");
        }
        if (g.message() != null && g.message().length() > 200) throw new BusinessException("선물 메시지는 200자까지 쓸 수 있어요");
    }

    /** 고를 수 있는 방문 설치일: 가장 빠른 설치 가능일부터 2주, 일요일 제외 */
    public List<LocalDate> installDates(com.yangpang.domain.common.Region region) {
        LocalDate first = arrivalEstimator.arrivalDate(com.yangpang.domain.common.DeliveryType.INSTALL, region);
        List<LocalDate> out = new java.util.ArrayList<>();
        for (LocalDate d = first; out.size() < 12; d = d.plusDays(1)) {
            if (d.getDayOfWeek() != java.time.DayOfWeek.SUNDAY) out.add(d);
        }
        return out;
    }

    public static String normalizeCustomsCode(String code) {
        return code == null ? null : code.replaceAll("[\\s-]", "").toUpperCase();
    }

    /** 개인통관고유부호 형식 (가짜 번호, 형식만 검사): P + 숫자 12자리 */
    public static boolean validCustomsCode(String code) {
        return code != null && code.matches("^P\\d{12}$");
    }

    static String normalizePhone(String phone) {
        String d = phone.replaceAll("\\D", "");
        return d.length() == 11 ? d.substring(0, 3) + "-" + d.substring(3, 7) + "-" + d.substring(7)
                : d.length() == 10 ? d.substring(0, 3) + "-" + d.substring(3, 6) + "-" + d.substring(6) : phone.trim();
    }

    private void notifyOrder(Order order, String title, String body) {
        notificationService.notify(order.getMemberId(), com.yangpang.domain.notification.Notification.Type.ORDER, title, body, "/my/orders/" + order.getOrderNo(),
                order.getItems().isEmpty() ? null : order.getItems().getFirst().getImageUrl());
    }

    private static String won(long amount) {
        return String.format("%,d", amount);
    }

    private void transition(Order order, OrderStatus next, String actor, String note, Instant now) {
        OrderStatus prev = order.getStatus();
        order.changeStatus(next, now);
        eventRepository.save(OrderEvent.of(order.getId(), prev, next, actor, note, now));
    }

    /** 결제 실패·입금 전 취소: 쓴 쿠폰과 캐시를 돌려준다 */
    public void restoreBenefits(Order order) {
        couponService.restoreForOrder(order.getId());
        cashService.giveBack(order.getMemberId(), order.getCashUsed(), com.yangpang.domain.coupon.CashLedger.Type.USE_CANCEL,
                order.getId(), "주문 " + order.getOrderNo() + " 결제 취소로 사용 취소");
    }

    private void restoreStock(Order order) {
        order.getItems().forEach(i -> vendorItemRepository.increaseStock(i.getVendorItemId(), i.getQuantity()));
        dealService.release(order.getItems());
        productSummaryService.refresh(order.getItems().stream().map(OrderItem::getProductId).collect(Collectors.toSet()));
    }

    private String nextOrderNo(Instant now) {
        String date = now.atZone(KST).format(ORDER_NO_DATE);
        return "Y" + date + String.format("%08d", orderRepository.nextOrderNoSequence());
    }
}
