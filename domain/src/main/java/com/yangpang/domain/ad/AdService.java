package com.yangpang.domain.ad;

import com.yangpang.domain.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 검색 광고 (M4 ③, 기획서 S-06·5.17). 연구용 모의 과금.
 * <ul>
 *   <li>판매자가 자기 상품에 키워드별 클릭당 입찰가(100~10,000원)와 하루 예산을 정한다</li>
 *   <li>검색어와 키워드가 정확히 같으면 후보. 광고 순위 = 입찰가 × 품질(0.5 + 양팡 랭킹 점수/100)</li>
 *   <li>클릭당 비용 = 바로 아래 순위를 이기는 데 필요한 금액 + 10원 (입찰가를 넘지 않음, 마지막 순위는 최소 100원)</li>
 *   <li>같은 노출에서 여러 번 눌러도 한 번만 과금. 하루 예산을 다 쓰면 그날은 노출 안 함</li>
 *   <li>클릭한 회원이 24시간 안에 그 상품을 사면 전환</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Transactional
public class AdService {

    public static final int MIN_BID = 100;
    public static final int MAX_BID = 10_000;
    public static final long MIN_BUDGET = 1_000;
    static final Duration CLICK_TOKEN_TTL = Duration.ofHours(1);
    static final Duration CONVERSION_WINDOW = Duration.ofHours(24);
    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 경매 참가자 */
    public record Bidder(Long keywordId, Long campaignId, Long productId, int bid, double quality) {
        double adRank() {
            return bid * quality;
        }
    }

    /** 낙찰: 클릭당 비용 포함 */
    public record Winner(Long keywordId, Long campaignId, Long productId, int bid, int cpc) {
    }

    public record Stat(long impressions, long clicks, long cost, long conversions, long sales) {
        public double ctr() {
            return impressions == 0 ? 0 : clicks * 100.0 / impressions;
        }

        /** 광고비 대비 매출 (%) */
        public double roas() {
            return cost == 0 ? 0 : sales * 100.0 / cost;
        }
    }

    public record KeywordRow(Long id, String keyword, int bid, Stat stat) {
    }

    public record CampaignRow(Long id, Long productId, String productName, long dailyBudget, String status, long spentToday,
                              Stat stat, List<KeywordRow> keywords) {
        public boolean active() {
            return "ACTIVE".equals(status);
        }
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Value("${yangpang.ad.secret:yangpang-dev-ad-secret}")
    private String secret;

    // =====================================================================
    // 노출
    // =====================================================================

    /** 검색어에 맞는 광고를 고른다 (최대 n개, 한 상품은 한 번) */
    @Transactional(readOnly = true)
    public List<Winner> match(String query, int n) {
        String k = normalize(query);
        if (k == null || n <= 0) return List.of();
        List<Bidder> bidders = jdbc.query("""
                select k.id, c.id, c.product_id, k.bid, p.ranking_score
                from ad_keyword k join ad_campaign c on c.id = k.campaign_id join product p on p.id = c.product_id
                where k.keyword = ? and c.status = 'ACTIVE' and p.status = 'ON_SALE' and not p.sold_out
                  and exists (select 1 from vendor_item vi join item i on i.id = vi.item_id
                               where vi.seller_id = c.seller_id and i.product_id = c.product_id
                                 and vi.status = 'ON_SALE' and vi.stock > 0 and vi.item_condition = 'NEW')
                  and coalesce((select sum(s.cost) from ad_stat_daily s where s.campaign_id = c.id and s.day = ?), 0) < c.daily_budget""",
                (rs, i) -> new Bidder(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getInt(4), quality(rs.getDouble(5))),
                k, Date.valueOf(today()));
        return auction(bidders, n);
    }

    /** 품질 점수: 랭킹 0점 → 0.5, 100점 → 1.5 */
    static double quality(double rankingScore) {
        return 0.5 + Math.max(0, Math.min(100, rankingScore)) / 100.0;
    }

    /** 일반화 2차 가격 경매. 같은 상품은 가장 높은 순위 하나만 */
    static List<Winner> auction(List<Bidder> bidders, int n) {
        List<Bidder> sorted = new ArrayList<>(bidders);
        sorted.sort(Comparator.comparingDouble(Bidder::adRank).reversed().thenComparing(Bidder::keywordId));
        List<Bidder> unique = new ArrayList<>();
        Set<Long> products = new HashSet<>();
        for (Bidder b : sorted) if (products.add(b.productId())) unique.add(b);
        List<Winner> out = new ArrayList<>();
        for (int i = 0; i < unique.size() && out.size() < n; i++) {
            Bidder b = unique.get(i);
            int cpc;
            if (i + 1 < unique.size()) {
                cpc = (int) Math.ceil(unique.get(i + 1).adRank() / b.quality()) + 10;
            } else {
                cpc = MIN_BID;
            }
            cpc = Math.max(MIN_BID, Math.min(b.bid(), cpc));
            out.add(new Winner(b.keywordId(), b.campaignId(), b.productId(), b.bid(), cpc));
        }
        return out;
    }

    /** 노출 기록 (읽기 전용 검색 화면에서도 기록되도록 별도 트랜잭션) */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordImpressions(List<Winner> winners) {
        Date day = Date.valueOf(today());
        for (Winner w : winners) {
            jdbc.update("""
                    insert into ad_stat_daily(campaign_id, keyword_id, day, impressions) values (?, ?, ?, 1)
                    on conflict (campaign_id, keyword_id, day) do update set impressions = ad_stat_daily.impressions + 1""",
                    w.campaignId(), w.keywordId(), day);
        }
    }

    /** 광고 클릭 주소 (키워드·비용·시각에 서명을 붙인다) */
    public String clickUrl(Winner w) {
        long ts = clock.instant().getEpochSecond();
        String nonce = Long.toString(java.util.concurrent.ThreadLocalRandom.current().nextLong(1L << 40), 36);
        return "/ad/click?k=" + w.keywordId() + "&c=" + w.cpc() + "&t=" + ts + "&n=" + nonce
                + "&s=" + sign(w.keywordId(), w.cpc(), ts, nonce);
    }

    /** 노출마다 다른 서명 (nonce 포함) → 같은 노출의 반복 클릭만 한 번으로 묶인다 */
    String sign(long keywordId, int cpc, long ts, String nonce) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] h = mac.doFinal((keywordId + ":" + cpc + ":" + ts + ":" + nonce).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(h).substring(0, 32);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // =====================================================================
    // 클릭 · 전환
    // =====================================================================

    /**
     * 클릭 처리. 서명이 맞고 1시간 안이면 한 번만 과금한다.
     * @return 이동할 상품 id (키워드가 없으면 null)
     */
    public Long click(long keywordId, int cpc, long ts, String nonce, String sig, Long memberId) {
        Map<String, Object> k = jdbc.query("""
                select k.campaign_id, c.product_id from ad_keyword k join ad_campaign c on c.id = k.campaign_id where k.id = ?""",
                rs -> rs.next() ? Map.<String, Object>of("campaign", rs.getLong(1), "product", rs.getLong(2)) : null, keywordId);
        if (k == null) return null;
        Long campaignId = (Long) k.get("campaign");
        Long productId = (Long) k.get("product");
        Instant now = clock.instant();
        boolean valid = sig != null && nonce != null && sig.equals(sign(keywordId, cpc, ts, nonce))
                && !Instant.ofEpochSecond(ts).isBefore(now.minus(CLICK_TOKEN_TTL)) && cpc >= MIN_BID && cpc <= MAX_BID;
        if (!valid) return productId; // 위조·만료 링크: 상품으로는 보내되 과금하지 않음
        int inserted = jdbc.update("""
                insert into ad_click(token, campaign_id, keyword_id, product_id, member_id, cost, created_at)
                values (?, ?, ?, ?, ?, ?, ?) on conflict (token) do nothing""",
                sig, campaignId, keywordId, productId, memberId, cpc, Timestamp.from(now));
        if (inserted > 0) {
            jdbc.update("""
                    insert into ad_stat_daily(campaign_id, keyword_id, day, clicks, cost) values (?, ?, ?, 1, ?)
                    on conflict (campaign_id, keyword_id, day) do update
                    set clicks = ad_stat_daily.clicks + 1, cost = ad_stat_daily.cost + excluded.cost""",
                    campaignId, keywordId, Date.valueOf(today()), cpc);
        }
        return productId;
    }

    /** 결제된 주문: 그 회원이 24시간 안에 광고를 눌렀던 상품이면 전환으로 (상품마다 가장 최근 클릭 하나) */
    public int attributeOrder(Long memberId, Long orderId, Map<Long, Long> amountByProduct) {
        if (memberId == null) return 0;
        Instant now = clock.instant();
        int n = 0;
        for (var e : amountByProduct.entrySet()) {
            Map<String, Object> c = jdbc.query("""
                    select id, campaign_id, keyword_id, created_at from ad_click
                    where member_id = ? and product_id = ? and order_id is null and created_at >= ? and created_at <= ?
                    order by created_at desc limit 1""",
                    rs -> rs.next() ? Map.<String, Object>of("id", rs.getLong(1), "campaign", rs.getLong(2), "keyword", rs.getLong(3),
                            "at", rs.getTimestamp(4).toInstant()) : null,
                    memberId, e.getKey(), Timestamp.from(now.minus(CONVERSION_WINDOW)), Timestamp.from(now));
            if (c == null) continue;
            jdbc.update("update ad_click set order_id = ? where id = ?", orderId, c.get("id"));
            jdbc.update("""
                    insert into ad_stat_daily(campaign_id, keyword_id, day, conversions, sales) values (?, ?, ?, 1, ?)
                    on conflict (campaign_id, keyword_id, day) do update
                    set conversions = ad_stat_daily.conversions + 1, sales = ad_stat_daily.sales + excluded.sales""",
                    c.get("campaign"), c.get("keyword"), Date.valueOf(((Instant) c.get("at")).atZone(KST).toLocalDate()), e.getValue());
            n++;
        }
        return n;
    }

    // =====================================================================
    // 셀러센터: 캠페인 관리
    // =====================================================================

    /**
     * 캠페인 만들기. 키워드는 한 줄에 "키워드 입찰가" (예: "캠핑 텐트 300"). 입찰가를 빼면 300원
     */
    public Long createCampaign(Long sellerId, Long productId, long dailyBudget, String keywordsText) {
        if (dailyBudget < MIN_BUDGET) throw new BusinessException("하루 예산은 1,000원 이상이어야 해요");
        Boolean sells = jdbc.queryForObject("""
                select exists(select 1 from vendor_item vi join item i on i.id = vi.item_id
                               where vi.seller_id = ? and i.product_id = ? and vi.item_condition = 'NEW')""", Boolean.class, sellerId, productId);
        if (!Boolean.TRUE.equals(sells)) throw new BusinessException("내가 파는 상품만 광고할 수 있어요");
        Map<String, Integer> keywords = parseKeywords(keywordsText);
        if (keywords.isEmpty()) throw new BusinessException("키워드를 하나 이상 입력해 주세요");
        checkRelevant(productId, keywords.keySet());
        Long id = jdbc.queryForObject("""
                insert into ad_campaign(seller_id, product_id, daily_budget, status, created_at) values (?, ?, ?, 'ACTIVE', ?) returning id""",
                Long.class, sellerId, productId, dailyBudget, Timestamp.from(clock.instant()));
        keywords.forEach((kw, bid) -> jdbc.update("insert into ad_keyword(campaign_id, keyword, bid) values (?, ?, ?)", id, kw, bid));
        return id;
    }

    /**
     * 관련 없는 키워드 막기: 키워드의 모든 단어가 상품명·브랜드·카테고리(대·중·소분류) 이름 어딘가에 들어 있어야 한다
     * (예: "즉석밥"으로 다이어리를 광고할 수 없음)
     */
    void checkRelevant(Long productId, java.util.Collection<String> keywords) {
        String text = jdbc.queryForObject("""
                select lower(concat_ws(' ', p.name, b.name, c1.name, c2.name, c3.name))
                from product p left join brand b on b.id = p.brand_id join category_tree t on t.id = p.category_id
                join category c1 on c1.id = t.id join category c2 on c2.id = t.mid_id join category c3 on c3.id = t.top_id
                where p.id = ?""", String.class, productId);
        for (String kw : keywords) {
            if (!relevant(kw, text)) {
                throw new BusinessException("'" + kw + "'은(는) 이 상품과 관련 없는 키워드예요. 상품명·브랜드·카테고리에 들어 있는 말로 정해 주세요");
            }
        }
    }

    static boolean relevant(String keyword, String productText) {
        if (productText == null) return false;
        String t = productText.toLowerCase();
        for (String w : keyword.toLowerCase().split(" ")) if (!w.isBlank() && !t.contains(w)) return false;
        return true;
    }

    /** "키워드 입찰가" 줄들을 읽는다. 마지막 숫자가 입찰가 */
    static Map<String, Integer> parseKeywords(String text) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (text == null) return out;
        for (String line : text.split("[\\n,]")) {
            String l = line.trim().replaceAll("\\s+", " ");
            if (l.isEmpty()) continue;
            int bid = 300;
            int sp = l.lastIndexOf(' ');
            if (sp > 0 && l.substring(sp + 1).replace(",", "").matches("\\d+")) {
                bid = Integer.parseInt(l.substring(sp + 1));
                l = l.substring(0, sp).trim();
            }
            String kw = normalize(l);
            if (kw == null) continue;
            if (kw.length() > 50) throw new BusinessException("키워드는 50자까지예요: " + kw);
            if (bid < MIN_BID || bid > MAX_BID) throw new BusinessException("입찰가는 100~10,000원이에요: " + kw + " " + bid);
            out.put(kw, bid);
        }
        if (out.size() > 20) throw new BusinessException("키워드는 캠페인당 20개까지예요");
        return out;
    }

    public void setStatus(Long sellerId, Long campaignId, boolean active) {
        int n = jdbc.update("update ad_campaign set status = ? where id = ? and seller_id = ?", active ? "ACTIVE" : "PAUSED", campaignId, sellerId);
        if (n == 0) throw new BusinessException("내 캠페인이 아니에요");
    }

    public void updateBudget(Long sellerId, Long campaignId, long dailyBudget) {
        if (dailyBudget < MIN_BUDGET) throw new BusinessException("하루 예산은 1,000원 이상이어야 해요");
        int n = jdbc.update("update ad_campaign set daily_budget = ? where id = ? and seller_id = ?", dailyBudget, campaignId, sellerId);
        if (n == 0) throw new BusinessException("내 캠페인이 아니에요");
    }

    public void updateBid(Long sellerId, Long keywordId, int bid) {
        if (bid < MIN_BID || bid > MAX_BID) throw new BusinessException("입찰가는 100~10,000원이에요");
        int n = jdbc.update("""
                update ad_keyword k set bid = ? from ad_campaign c where c.id = k.campaign_id and k.id = ? and c.seller_id = ?""",
                bid, keywordId, sellerId);
        if (n == 0) throw new BusinessException("내 키워드가 아니에요");
    }

    public void addKeywords(Long sellerId, Long campaignId, String text) {
        Integer own = jdbc.queryForObject("select count(*) from ad_campaign where id = ? and seller_id = ?", Integer.class, campaignId, sellerId);
        if (own == null || own == 0) throw new BusinessException("내 캠페인이 아니에요");
        Map<String, Integer> keywords = parseKeywords(text);
        Long productId = jdbc.queryForObject("select product_id from ad_campaign where id = ?", Long.class, campaignId);
        checkRelevant(productId, keywords.keySet());
        keywords.forEach((kw, bid) -> jdbc.update("""
                insert into ad_keyword(campaign_id, keyword, bid) values (?, ?, ?)
                on conflict (campaign_id, keyword) do update set bid = excluded.bid""", campaignId, kw, bid));
    }

    public void removeKeyword(Long sellerId, Long keywordId) {
        jdbc.update("delete from ad_keyword k using ad_campaign c where c.id = k.campaign_id and k.id = ? and c.seller_id = ?", keywordId, sellerId);
    }

    /** 내 캠페인과 최근 N일 성과 */
    @Transactional(readOnly = true)
    public List<CampaignRow> campaigns(Long sellerId, int days) {
        LocalDate from = today().minusDays(days - 1L);
        Date today = Date.valueOf(today());
        Map<Long, List<KeywordRow>> kws = new LinkedHashMap<>();
        jdbc.query("""
                select k.campaign_id, k.id, k.keyword, k.bid,
                       coalesce(sum(s.impressions),0), coalesce(sum(s.clicks),0), coalesce(sum(s.cost),0),
                       coalesce(sum(s.conversions),0), coalesce(sum(s.sales),0)
                from ad_keyword k join ad_campaign c on c.id = k.campaign_id
                left join ad_stat_daily s on s.keyword_id = k.id and s.day >= ?
                where c.seller_id = ? group by k.campaign_id, k.id, k.keyword, k.bid order by k.bid desc, k.id""",
                rs -> {
                    kws.computeIfAbsent(rs.getLong(1), x -> new ArrayList<>()).add(new KeywordRow(rs.getLong(2), rs.getString(3), rs.getInt(4),
                            new Stat(rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getLong(8), rs.getLong(9))));
                }, Date.valueOf(from), sellerId);
        return jdbc.query("""
                select c.id, c.product_id, p.name, c.daily_budget, c.status,
                       coalesce((select sum(cost) from ad_stat_daily where campaign_id = c.id and day = ?), 0)
                from ad_campaign c join product p on p.id = c.product_id where c.seller_id = ? order by c.status, c.id desc""",
                (rs, i) -> {
                    List<KeywordRow> list = kws.getOrDefault(rs.getLong(1), List.of());
                    Stat total = new Stat(list.stream().mapToLong(x -> x.stat().impressions()).sum(),
                            list.stream().mapToLong(x -> x.stat().clicks()).sum(), list.stream().mapToLong(x -> x.stat().cost()).sum(),
                            list.stream().mapToLong(x -> x.stat().conversions()).sum(), list.stream().mapToLong(x -> x.stat().sales()).sum());
                    return new CampaignRow(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getLong(4), rs.getString(5), rs.getLong(6),
                            total, list);
                }, today, sellerId);
    }

    LocalDate today() {
        return LocalDate.now(clock.withZone(KST));
    }

    public static String normalize(String keyword) {
        if (keyword == null) return null;
        String k = keyword.trim().replaceAll("\\s+", " ").toLowerCase();
        return k.isEmpty() ? null : (k.length() > 50 ? k.substring(0, 50) : k);
    }
}
