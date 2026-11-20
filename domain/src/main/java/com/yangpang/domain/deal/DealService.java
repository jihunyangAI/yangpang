package com.yangpang.domain.deal;

import com.yangpang.domain.common.BusinessException;
import com.yangpang.domain.order.OrderItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 오늘의 특가 (기획서 5.6).
 * - 매일 07:00(한국 시간)부터 다음 날 07:00까지. 시뮬레이터가 07:00이 지나면 새 특가 20개를 고른다
 * - 수량 한정: 주문 생성 트랜잭션 안에서 reserve(조건부 UPDATE)로 확보 → 실패하면 주문 전체 취소
 * - 1회 주문 최대 수량, 1인 최대 수량
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class DealService {

    public static final ZoneId KST = ZoneId.of("Asia/Seoul");
    public static final LocalTime ROTATE_AT = LocalTime.of(7, 0);
    static final int DAILY_COUNT = 20;

    private final DealRepository dealRepository;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    /** 지금 시각이 속한 특가 기간의 시작 (오늘 07:00, 07:00 전이면 어제 07:00) */
    public static Instant windowStart(Instant now) {
        ZonedDateTime t = now.atZone(KST);
        LocalDate day = t.toLocalTime().isBefore(ROTATE_AT) ? t.toLocalDate().minusDays(1) : t.toLocalDate();
        return day.atTime(ROTATE_AT).atZone(KST).toInstant();
    }

    @Transactional(readOnly = true)
    public List<Deal> active() {
        return dealRepository.findActive(clock.instant());
    }

    /** 판매 오퍼별 진행 중 특가 */
    @Transactional(readOnly = true)
    public Map<Long, Deal> activeFor(Collection<Long> vendorItemIds) {
        if (vendorItemIds.isEmpty()) return Map.of();
        return dealRepository.findActiveFor(vendorItemIds, clock.instant()).stream()
                .collect(Collectors.toMap(Deal::getVendorItemId, Function.identity(), (a, b) -> a.getDealPrice() <= b.getDealPrice() ? a : b));
    }

    /**
     * 주문 한 줄의 특가 수량 확보. 주문 생성 트랜잭션 안에서 호출해야 한다.
     * 실패하면 BusinessException → 주문 전체(재고 차감 포함) 롤백.
     */
    public void reserve(Deal deal, long memberId, int quantity, String productName) {
        if (quantity > deal.getMaxPerOrder()) {
            throw new BusinessException("'" + productName + "' 특가는 한 번에 " + deal.getMaxPerOrder() + "개까지 살 수 있어요");
        }
        if (dealRepository.boughtBy(deal.getId(), memberId) + quantity > deal.getMaxPerMember()) {
            throw new BusinessException("'" + productName + "' 특가는 1인당 " + deal.getMaxPerMember() + "개까지예요");
        }
        if (dealRepository.reserve(deal.getId(), quantity, clock.instant()) == 0) {
            throw new BusinessException("'" + productName + "' 특가 수량이 모두 팔렸어요. 장바구니에서 일반가로 다시 확인해 주세요");
        }
    }

    /** 결제 실패·입금 전 취소·주문 취소 시 특가 수량 되돌리기 */
    public void release(Collection<OrderItem> items) {
        for (OrderItem i : items) {
            if (i.getDealId() != null) dealRepository.release(i.getDealId(), i.getQuantity());
        }
    }

    /** 운영자 등록 특가 (지금부터 오늘 기간 끝까지) */
    public Deal createManual(Long vendorItemId, long dealPrice, int quantity, int maxPerOrder, int maxPerMember) {
        Long sale = jdbc.query("select sale_price from vendor_item where id = ? and status = 'ON_SALE'",
                rs -> rs.next() ? rs.getLong(1) : null, vendorItemId);
        if (sale == null) throw new BusinessException("판매 중인 오퍼가 없어요");
        if (dealPrice <= 0 || dealPrice >= sale) throw new BusinessException("특가는 현재 판매가(" + sale + "원)보다 낮아야 해요");
        if (quantity < 1 || maxPerOrder < 1 || maxPerMember < 1) throw new BusinessException("수량을 확인해 주세요");
        Instant now = clock.instant();
        return dealRepository.save(Deal.create(vendorItemId, dealPrice, quantity, maxPerOrder, maxPerMember,
                now, windowStart(now).plus(java.time.Duration.ofDays(1)), "ADMIN"));
    }

    /**
     * 시뮬레이터: 07:00이 지났는데 이번 기간의 자동 특가가 없으면 새로 고른다.
     * 고르는 기준: 판매 중·재고 30개 이상인 양팡 물류 위너 오퍼, 별점 4.0 이상. 기간 시작 시각을 씨앗으로 써서 같은 날은 같은 결과
     * @param force 이미 있어도 지금 다시 고른다 (운영자 "지금 교체")
     */
    public int rotateIfNeeded(boolean force) {
        Instant now = clock.instant();
        Instant start = windowStart(now);
        if (!force && dealRepository.existsBySourceAndStartsAt("AUTO", start)) return 0;
        if (force) {
            // 진행 중인 자동 특가는 지금 끝낸다 (이미 팔린 주문은 그대로)
            jdbc.update("update deal set ends_at = ? where source = 'AUTO' and starts_at <= ? and ends_at > ?",
                    java.sql.Timestamp.from(now), java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));
        }
        Instant end = start.plus(java.time.Duration.ofDays(1));
        Instant from = force ? now : start;
        List<long[]> candidates = jdbc.query("""
                select vi.id, vi.sale_price, vi.stock from vendor_item vi
                join item i on i.id = vi.item_id join product p on p.id = i.product_id
                where vi.is_winner and vi.status = 'ON_SALE' and p.status = 'ON_SALE' and vi.stock >= 30
                  and vi.delivery_type in ('YANGPANG', 'FRESH') and vi.sale_price >= 3000 and p.rating_avg >= 4.0
                order by vi.id""", (rs, n) -> new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3)});
        if (candidates.isEmpty()) return 0;
        Random random = new Random(from.toEpochMilli());
        java.util.Collections.shuffle(candidates, random);
        int made = 0;
        for (long[] c : candidates.subList(0, Math.min(DAILY_COUNT, candidates.size()))) {
            double rate = 0.15 + random.nextInt(21) / 100.0;               // 15~35% 할인
            long price = Math.max(100, Math.round(c[1] * (1 - rate) / 10.0) * 10);
            int qty = (int) Math.min(c[2] / 2, new int[]{10, 20, 30, 50, 100}[random.nextInt(5)]);
            dealRepository.save(Deal.create(c[0], price, Math.max(qty, 5), 2, 2, from, end, "AUTO"));
            made++;
        }
        log.info("[특가] {} ~ {} 오늘의 특가 {}개", from, end, made);
        return made;
    }
}
