package com.yangpang.api.ai;

import com.anthropic.core.JsonValue;
import com.anthropic.models.beta.messages.BetaTool;
import com.yangpang.domain.common.DeliveryType;
import com.yangpang.domain.delivery.ArrivalEstimator;
import com.yangpang.domain.policy.Policy;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 챗봇 도구 (M4 ⑧): 양팡 데이터를 읽기만 한다 (주문·결제·변경은 하지 않고 화면으로 안내).
 * 내 주문은 로그인한 회원 것만. 도구 결과는 짧은 텍스트로 돌려준다.
 */
@Component
@RequiredArgsConstructor
public class ChatTools {

    private final JdbcTemplate jdbc;
    private final ArrivalEstimator arrivalEstimator;
    private final ReviewSummaryService summaryService;
    private final com.yangpang.domain.policy.PolicyService policyService;

    /** 도구 정의 (요청마다 같은 순서·내용이어야 캐시가 맞는다) */
    public List<BetaTool> definitions() {
        return List.of(
                tool("search_products", "양팡에서 판매 중인 상품을 이름·브랜드·카테고리 단어로 찾는다. 결과는 최대 6개, 양팡 랭킹 순.",
                        Map.of("query", Map.of("type", "string", "description", "찾을 단어 (예: 즉석밥, 요가매트, 블랙 백팩)"),
                                "max_price", Map.of("type", "integer", "description", "최대 가격(원). 없으면 생략")),
                        List.of("query")),
                tool("get_product", "상품 하나의 가격·회원가·배송·도착 예정·별점·옵션·AI 리뷰 요약(있으면)을 본다.",
                        Map.of("product_id", Map.of("type", "integer", "description", "상품 번호")), List.of("product_id")),
                tool("my_recent_orders", "로그인한 회원의 최근 주문 5건과 배송 상태를 본다. 로그인하지 않았으면 안내 문구가 나온다.",
                        Map.of(), List.of()),
                tool("shop_policy", "양팡 이용 규칙을 본다.",
                        Map.of("topic", Map.of("type", "string", "enum", POLICY_TOPICS,
                                "description", "shipping 배송비·도착, returns 취소·반품, plus 양팡플러스, subscription 정기배송, gift 선물하기, global 양팡직구, biz 비즈 회원, cash 양팡캐시")),
                        List.of("topic")));
    }

    private static BetaTool tool(String name, String description, Map<String, Object> props, List<String> required) {
        var props2 = BetaTool.InputSchema.Properties.builder();
        new java.util.TreeMap<>(props).forEach((k, v) -> props2.putAdditionalProperty(k, JsonValue.from(v))); // 순서 고정 (캐시)
        return BetaTool.builder().name(name).description(description)
                .inputSchema(BetaTool.InputSchema.builder().properties(props2.build()).required(required).build())
                .build();
    }

    /** 도구 실행. 잘못된 입력이면 IllegalArgumentException (is_error 결과로 돌려준다) */
    public String run(String name, Map<?, ?> input, Long memberId) {
        return switch (name) {
            case "search_products" -> search(str(input, "query"), input.get("max_price") == null ? null : num(input, "max_price"));
            case "get_product" -> product(num(input, "product_id"));
            case "my_recent_orders" -> orders(memberId);
            case "shop_policy" -> policy(str(input, "topic"));
            default -> throw new IllegalArgumentException("알 수 없는 도구: " + name);
        };
    }

    String search(String query, Long maxPrice) {
        String[] words = Arrays.stream(query.trim().toLowerCase().split("\\s+")).filter(w -> !w.isBlank()).limit(4).toArray(String[]::new);
        if (words.length == 0) throw new IllegalArgumentException("query가 비어 있어요");
        StringBuilder where = new StringBuilder();
        Object[] args = new Object[words.length + (maxPrice == null ? 0 : 1)];
        for (int i = 0; i < words.length; i++) {
            where.append(" and lower(p.name || ' ' || coalesce(b.name, '') || ' ' || c.name) like ?");
            args[i] = "%" + words[i].replace("%", "").replace("_", "") + "%";
        }
        if (maxPrice != null) {
            where.append(" and p.rep_sale_price <= ?");
            args[words.length] = maxPrice;
        }
        List<String> rows = jdbc.query("""
                select p.id, p.name, p.rep_sale_price, p.rep_plus_price, p.rating_avg, p.review_count, p.rep_delivery_type, p.sold_out
                from product p join category c on c.id = p.category_id left join brand b on b.id = p.brand_id
                where p.status = 'ON_SALE' and not p.adult""" + where + " order by p.sold_out, p.ranking_score desc nulls last limit 6",
                (rs, n) -> "- [" + rs.getLong(1) + "] " + rs.getString(2) + " · " + won(rs.getLong(3))
                        + (rs.getObject(4) == null ? "" : " (플러스 회원가 " + won(rs.getLong(4)) + ")")
                        + " · ★" + rs.getBigDecimal(5) + " (" + rs.getInt(6) + "개)"
                        + " · " + deliveryLabel(rs.getString(7)) + (rs.getBoolean(8) ? " · 품절" : "")
                        + " · /products/" + rs.getLong(1), args);
        return rows.isEmpty() ? "'" + query + "'에 맞는 상품이 없어요. 더 짧은 단어로 다시 찾아보세요." : String.join("\n", rows);
    }

    String product(long id) {
        List<String> found = jdbc.query("""
                select p.name, coalesce(b.name, '-'), c.name, p.rep_sale_price, p.rep_list_price, p.rep_plus_price, p.rating_avg,
                       p.review_count, p.rep_delivery_type, p.sold_out, p.option1_name,
                       (select string_agg(distinct i.option1_value, ', ') from item i where i.product_id = p.id)
                from product p join category c on c.id = p.category_id left join brand b on b.id = p.brand_id
                where p.id = ? and p.status = 'ON_SALE' and not p.adult""", (rs, n) -> {
            String type = rs.getString(9);
            StringBuilder sb = new StringBuilder();
            sb.append("[").append(id).append("] ").append(rs.getString(1)).append("\n")
                    .append("브랜드 ").append(rs.getString(2)).append(" · 카테고리 ").append(rs.getString(3)).append("\n")
                    .append("판매가 ").append(won(rs.getLong(4))).append(" (정가 ").append(won(rs.getLong(5))).append(")");
            if (rs.getObject(6) != null) sb.append(" · 양팡플러스 회원가 ").append(won(rs.getLong(6)));
            sb.append("\n별점 ★").append(rs.getBigDecimal(7)).append(" (리뷰 ").append(rs.getInt(8)).append("개)\n");
            if (type != null) sb.append(deliveryLabel(type)).append(" · ").append(arrivalEstimator.label(DeliveryType.valueOf(type))).append("\n");
            if (rs.getBoolean(10)) sb.append("현재 품절\n");
            if (rs.getString(11) != null && rs.getString(12) != null) sb.append(rs.getString(11)).append(" 옵션: ").append(rs.getString(12)).append("\n");
            sb.append("상품 페이지: /products/").append(id);
            return sb.toString();
        }, id);
        if (found.isEmpty()) return "판매 중인 상품 " + id + "번이 없어요.";
        String summary = summaryService.cached(id).map(s -> "\nAI 리뷰 요약: " + s.summary()
                + (s.pros().isEmpty() ? "" : " / 장점: " + String.join(", ", s.pros()))
                + (s.cons().isEmpty() ? "" : " / 아쉬운 점: " + String.join(", ", s.cons()))).orElse("");
        return found.getFirst() + summary;
    }

    String orders(Long memberId) {
        if (memberId == null) return "로그인하지 않았어요. 주문 조회는 로그인 후 마이양팡 > 주문목록(/my/orders)에서 할 수 있다고 안내하세요.";
        List<String> rows = jdbc.query("""
                select o.order_no, o.status, o.ordered_at, o.paid_amount,
                       (select min(oi.product_name) from order_item oi where oi.order_id = o.id),
                       (select count(*) from order_item oi where oi.order_id = o.id),
                       (select min(oi.promised_date) from order_item oi where oi.order_id = o.id)
                from orders o where o.member_id = ? order by o.ordered_at desc limit 5""",
                (rs, n) -> "- " + rs.getString(1) + " · " + statusLabel(rs.getString(2)) + " · " + rs.getTimestamp(3).toLocalDateTime().toLocalDate()
                        + " 주문 · " + rs.getString(5) + (rs.getInt(6) > 1 ? " 외 " + (rs.getInt(6) - 1) + "건" : "")
                        + " · " + won(rs.getLong(4)) + (rs.getDate(7) == null ? "" : " · 도착 예정 " + rs.getDate(7))
                        + " · /my/orders/" + rs.getString(1), memberId);
        return rows.isEmpty() ? "주문 내역이 없어요." : String.join("\n", rows);
    }

    static final List<String> POLICY_TOPICS = List.of("shipping", "returns", "plus", "subscription", "gift", "global", "biz", "cash");

    /** 이용 규칙 (관리자 설정 값으로 만든다, 모두 연구용 모의 규칙) */
    String policy(String topic) {
        java.util.function.LongFunction<String> won = v -> String.format("%,d원", v);
        var p = policyService;
        return switch (topic) {
            case "shipping" -> "양팡배송·프레시·판매자양팡은 한 묶음으로 " + won.apply(p.get(Policy.SHIPPING_FREE_THRESHOLD)) + " 이상 무료, 미만 "
                    + won.apply(p.get(Policy.SHIPPING_FEE)) + " (양팡플러스 회원은 항상 무료). 자정 전 주문 시 대부분 내일 도착, 프레시는 새벽 도착. "
                    + "판매자배송은 판매자 기준 배송비. 양팡직구는 따로 묶고 같은 기준(미만 " + won.apply(p.get(Policy.GLOBAL_SHIPPING_FEE)) + "). 도서산간은 2일 더.";
            case "returns" -> "출고 전에는 마이양팡 주문 상세에서 바로 취소·전액 환불. 배송완료 후 " + p.get(Policy.RETURN_WINDOW_DAYS)
                    + "일 안에 반품·교환 신청(양팡플러스 회원의 양팡 물류 상품은 " + p.get(Policy.PLUS_RETURN_WINDOW_DAYS) + "일). 단순 변심은 반품비(양팡배송 "
                    + won.apply(p.get(Policy.SHIPPING_FEE)) + ", 플러스 회원 무료, 판매자배송은 판매자 배송비, 양팡직구는 해외 반송비 "
                    + won.apply(p.get(Policy.GLOBAL_RETURN_FEE)) + "), 상품 불량·오배송 등 판매자 귀책은 무료. 신선식품은 반품 불가. 신청: /my/claims";
            case "plus" -> "양팡플러스: 월 " + won.apply(p.get(Policy.PLUS_MONTHLY_FEE)) + "(모의 결제). 양팡배송 무료, 회원가, "
                    + p.get(Policy.PLUS_RETURN_WINDOW_DAYS) + "일 무료 반품. 가입·해지: /plus, /my/plus";
            case "subscription" -> "정기배송: 식품·생활용품·뷰티·유아동·반려동물·건강식품 상품 상세에서 1·2·4주 주기로 신청, 상품 금액 "
                    + p.get(Policy.SUBSCRIPTION_DISCOUNT_PERCENT) + "% 할인, 배송 예정일 이틀 전에 자동 주문·결제(모의). 주기·수량 변경, 건너뛰기, 해지는 /my/subscriptions";
            case "gift" -> "선물하기: 주문서에서 '선물하기'를 고르고 받는 분 이름·휴대폰만 입력. 받는 분이 링크에서 배송지를 넣으면 출고, "
                    + p.get(Policy.GIFT_ACCEPT_DAYS) + "일 안에 안 넣으면 자동 취소·환불. 받는 분 주소는 보내는 분에게 보이지 않음";
            case "global" -> "양팡직구: 해외 배송, 약 4일 도착. 주문할 때 개인통관고유부호(P로 시작하는 13자리) 필요. 단순 변심 반품은 해외 반송비 "
                    + won.apply(p.get(Policy.GLOBAL_RETURN_FEE)) + ". 직구 모음: /global";
            case "biz" -> "비즈(사업자) 회원: 일부 상품에 사업자 회원가 자동 적용, 결제한 주문은 주문 상세에서 세금계산서 요청(모의 발행). 내역: /my/biz";
            case "cash" -> "양팡캐시: 양페이 결제 상품을 구매확정하면 " + p.get(Policy.PURCHASE_CASH_PERCENT) + "% 적립, 주문서에서 현금처럼 사용. "
                    + "배송이 도착 보장일보다 늦으면 " + won.apply(p.get(Policy.DELAY_COMPENSATION_CASH)) + " 보상 적립. 내역: /my/cash";
            default -> "그런 주제는 없어요. 가능한 주제: " + String.join(", ", POLICY_TOPICS);
        };
    }

    private static String deliveryLabel(String type) {
        if (type == null) return "-";
        try {
            return DeliveryType.valueOf(type).label();
        } catch (IllegalArgumentException e) {
            return type;
        }
    }

    private static String statusLabel(String s) {
        try {
            return com.yangpang.domain.order.OrderStatus.valueOf(s).label();
        } catch (IllegalArgumentException e) {
            return s;
        }
    }

    private static String won(long v) {
        return String.format("%,d원", v);
    }

    private static String str(Map<?, ?> in, String key) {
        Object v = in.get(key);
        if (v == null || v.toString().isBlank()) throw new IllegalArgumentException(key + "가 필요해요");
        return v.toString();
    }

    private static long num(Map<?, ?> in, String key) {
        Object v = in.get(key);
        try {
            return v instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(v).replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + "는 숫자여야 해요");
        }
    }
}
