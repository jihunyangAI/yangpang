package com.yangpang.seed.order;

import com.yangpang.domain.common.DeliveryType;
import com.yangpang.domain.delivery.ShippingFeeCalculator;
import com.yangpang.seed.SeedContext;
import com.yangpang.seed.Seeder;
import com.yangpang.seed.member.MemberSeeder;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 과거 주문 (최근 1년). 주문 시점에 따라 상태가 정해진다.
 * - 8일 이상 지난 주문: 구매확정 93% / 취소 7%
 * - 3~7일: 배송완료,  1~2일: 배송중,  당일: 결제완료 또는 상품준비중
 * 배송비 규칙은 기획서 8.2 (일반회원 양팡배송 최종 결제금액 19,800원 이상 무료).
 * 결제는 모두 모의 결제(mock_payment).
 */
@Component
@Order(60)
@RequiredArgsConstructor
public class OrderSeeder implements Seeder {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter ORDER_NO_DATE = DateTimeFormatter.ofPattern("yyMMdd");
    private static final List<String> PAY_METHODS = List.of("YANGPAY", "YANGPAY", "CARD", "CARD", "PHONE", "BANK");

    /** 상태가 지나가는 순서 */
    private static final List<String> FLOW = List.of("PAID", "PREPARING", "SHIPPED", "IN_TRANSIT", "DELIVERED", "CONFIRMED");

    private final JdbcTemplate jdbc;

    @Override
    public String name() {
        return "주문/주문상품/모의결제/주문이력";
    }

    @Override
    public void seed(SeedContext ctx) {
        List<SeedContext.Offer> buyable = ctx.offers.stream().filter(o -> o.stock() > 0).toList();
        Instant now = Instant.now();

        List<Object[]> orders = new ArrayList<>();
        List<Object[]> orderItems = new ArrayList<>();
        List<Object[]> payments = new ArrayList<>();
        List<Object[]> events = new ArrayList<>();
        long orderItemId = 0, eventId = 0;

        // 오래된 주문부터 만들어 id 순서와 시간 순서를 맞춘다
        List<Instant> times = new ArrayList<>();
        for (int i = 0; i < ctx.scale.orders; i++) {
            times.add(now.minus(Duration.ofMinutes(ctx.between(10, 365 * 24 * 60))));
        }
        // 화면 확인용: 테스트 계정의 최근 주문 몇 건은 진행 중 상태가 되도록 최근 시각을 섞는다
        for (int i = 0; i < 4; i++) {
            times.set(i, now.minus(Duration.ofHours(ctx.between(1, 24 * 6))));
        }
        List<Long> buyerOf = new ArrayList<>();
        for (int i = 0; i < ctx.scale.orders; i++) {
            buyerOf.add(i < 12 ? MemberSeeder.NORMAL_TEST_MEMBER_ID : ctx.pick(ctx.memberIds));
        }
        // (시각, 구매자) 쌍을 시간순으로 정렬
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < times.size(); i++) idx.add(i);
        idx.sort((a, b) -> times.get(a).compareTo(times.get(b)));

        long orderId = 0;
        for (int k : idx) {
            orderId++;
            Instant orderedAt = times.get(k);
            long memberId = buyerOf.get(k);
            SeedContext.AddressRef addr = ctx.defaultAddresses.get(memberId);

            // 주문 상품 1~4개 (같은 오퍼 중복 없이)
            List<SeedContext.Offer> picked = ctx.pickDistinct(buyable, ctx.between(1, 4));
            List<int[]> qty = new ArrayList<>();
            long productAmount = 0;
            for (SeedContext.Offer o : picked) {
                int q = o.salePrice() > 100_000 ? 1 : ctx.between(1, 3);
                qty.add(new int[]{q});
                productAmount += o.salePrice() * q;
            }
            long shippingFee = shippingFee(ctx, picked, qty);
            long paid = productAmount + shippingFee;

            long ageDays = Duration.between(orderedAt, now).toDays();
            String finalStatus = finalStatus(ctx, ageDays);

            LocalDate orderDate = orderedAt.atZone(KST).toLocalDate();
            String orderNo = "Y" + orderDate.format(ORDER_NO_DATE) + String.format("%06d", orderId);
            Instant paidAt = orderedAt.plusSeconds(ctx.between(5, 60));
            Instant deliveredAt = reached(finalStatus, "DELIVERED") ? orderedAt.plus(Duration.ofHours(ctx.between(14, 40))) : null;
            Instant confirmedAt = reached(finalStatus, "CONFIRMED") ? deliveredAt.plus(Duration.ofDays(ctx.between(1, 7))) : null;

            orders.add(new Object[]{orderId, orderNo, memberId, finalStatus,
                    addr.recipient(), addr.phone(), addr.zipcode(), addr.address1(), addr.address2(), addr.region(),
                    addr.entranceType(), addr.entranceNote(), addr.requestNote(),
                    productAmount, 0L, shippingFee, paid, Timestamp.from(orderedAt), Timestamp.from(paidAt)});

            payments.add(new Object[]{orderId, ctx.pick(PAY_METHODS), paid, "APPROVED", Timestamp.from(paidAt), Timestamp.from(orderedAt)});

            // 상태 이력 (주문 단위). 마지막 변경 시각 = 주문 상품의 status_changed_at
            List<Object[]> chain = eventChain(ctx, finalStatus, orderedAt, paidAt, deliveredAt, confirmedAt);
            Timestamp changedAt = (Timestamp) chain.getLast()[4];
            for (Object[] e : chain) {
                events.add(new Object[]{++eventId, orderId, e[0], e[1], e[2], e[3], e[4]});
            }

            for (int i = 0; i < picked.size(); i++) {
                SeedContext.Offer o = picked.get(i);
                int q = qty.get(i)[0];
                orderItemId++;
                LocalDate promised = orderDate.plusDays(promiseDays(o.deliveryType(), addr.region()));
                orderItems.add(new Object[]{orderItemId, orderId, o.id(), o.productId(), o.sellerId(),
                        o.productName(), o.optionText(), o.imageUrl(), o.deliveryType(),
                        o.salePrice(), q, 0L, o.salePrice() * q, finalStatus, Date.valueOf(promised),
                        deliveredAt == null ? null : Timestamp.from(deliveredAt),
                        confirmedAt == null ? null : Timestamp.from(confirmedAt),
                        changedAt,
                        deliveredAt == null ? null : "/images/delivery/" + orderItemId + ".svg"});
                if (confirmedAt != null) {
                    ctx.confirmedItems.add(new SeedContext.ConfirmedItem(orderItemId, o.productId(), memberId, o.optionText(), confirmedAt));
                }
            }

        }

        jdbc.batchUpdate("""
                insert into orders(id, order_no, member_id, status, recipient, recipient_phone, zipcode, address1, address2,
                                   region, entrance_type, entrance_note, request_note, total_product_amount,
                                   discount_amount, shipping_fee, paid_amount, ordered_at, paid_at)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", orders);
        jdbc.batchUpdate("""
                insert into order_item(id, order_id, vendor_item_id, product_id, seller_id, product_name, option_text,
                                       image_url, delivery_type, unit_price, quantity, discount_amount, line_amount,
                                       status, promised_date, delivered_at, confirmed_at, status_changed_at,
                                       delivery_photo_url)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", orderItems);
        jdbc.batchUpdate("""
                insert into mock_payment(order_id, method, amount, status, approved_at, created_at)
                values (?,?,?,?,?,?)""", payments);
        jdbc.batchUpdate("""
                insert into order_event(id, order_id, from_status, to_status, actor, note, created_at)
                values (?,?,?,?,?,?,?)""", events);
    }

    /** 앱과 같은 배송비 계산기 사용 (기획서 8.2) */
    private long shippingFee(SeedContext ctx, List<SeedContext.Offer> offers, List<int[]> qty) {
        List<ShippingFeeCalculator.Line> lines = new ArrayList<>();
        for (int i = 0; i < offers.size(); i++) {
            SeedContext.Offer o = offers.get(i);
            SeedContext.SellerRef s = ctx.sellers.stream().filter(x -> x.id() == o.sellerId()).findFirst().orElseThrow();
            lines.add(new ShippingFeeCalculator.Line(DeliveryType.valueOf(o.deliveryType()), s.id(), "seller-" + s.id(),
                    s.shippingFee(), s.freeThreshold(), o.salePrice() * qty.get(i)[0]));
        }
        return ShippingFeeCalculator.calculate(lines, false).totalFee();
    }

    private String finalStatus(SeedContext ctx, long ageDays) {
        if (ageDays >= 8) return ctx.chance(0.07) ? "CANCELED" : "CONFIRMED";
        if (ageDays >= 3) return "DELIVERED";
        if (ageDays >= 1) return "IN_TRANSIT";
        return ctx.chance(0.5) ? "PAID" : "PREPARING";
    }

    private boolean reached(String finalStatus, String target) {
        int f = FLOW.indexOf(finalStatus);
        return f >= 0 && f >= FLOW.indexOf(target);
    }

    private long promiseDays(String deliveryType, String region) {
        long days = "SELLER".equals(deliveryType) ? 2 : 1;
        if ("REMOTE".equals(region)) days += 2;
        return days;
    }

    /** [from, to, actor, note, createdAt] 목록 */
    private List<Object[]> eventChain(SeedContext ctx, String finalStatus, Instant orderedAt, Instant paidAt,
                                      Instant deliveredAt, Instant confirmedAt) {
        List<Object[]> chain = new ArrayList<>();
        chain.add(new Object[]{null, "PAID", "MEMBER", "모의 결제 승인", Timestamp.from(paidAt)});
        if ("CANCELED".equals(finalStatus)) {
            chain.add(new Object[]{"PAID", "CANCELED", "MEMBER", "고객 주문 취소",
                    Timestamp.from(paidAt.plus(Duration.ofMinutes(ctx.between(5, 300))))});
            return chain;
        }
        Instant t = paidAt;
        String prev = "PAID";
        for (String s : FLOW.subList(1, FLOW.indexOf(finalStatus) + 1)) {
            t = switch (s) {
                case "DELIVERED" -> deliveredAt;
                case "CONFIRMED" -> confirmedAt;
                default -> t.plus(Duration.ofMinutes(ctx.between(30, 240)));
            };
            String actor = ("PREPARING".equals(s) || "SHIPPED".equals(s)) ? "SELLER" : "SYSTEM";
            chain.add(new Object[]{prev, s, actor, null, Timestamp.from(t)});
            prev = s;
        }
        return chain;
    }
}
