package com.yangpang.seed.member;

import com.yangpang.seed.SeedContext;
import com.yangpang.seed.Seeder;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 알림함·찜 (M5 알림): 주문 이력으로 지난 알림(주문 완료·출고·배송완료)을 만든다. 3일보다 오래된 것은 읽음.
 * 찜은 회원마다 0~8개. 테스트 계정 일부 찜은 "찜할 때 가격"을 지금보다 높게 두어,
 * 앱을 켜면 시뮬레이터가 가격 인하 알림을 바로 만든다 (기능 확인용).
 */
@Component
@Order(97)
@RequiredArgsConstructor
public class NotificationSeeder implements Seeder {

    private final JdbcTemplate jdbc;

    @Override
    public String name() {
        return "찜·알림";
    }

    @Override
    public void seed(SeedContext ctx) {
        // 찜: 회원마다 판매 중인 인기 상품 중 무작위 0~8개
        for (Long memberId : jdbc.queryForList("select id from member where role <> 'ADMIN'", Long.class)) {
            int n = memberId <= 5 ? 6 : ctx.between(0, 8);
            if (n == 0) continue;
            jdbc.update("""
                    insert into wishlist(member_id, product_id, created_at, notified_price, was_sold_out)
                    select ?, id, now() - (random() * interval '60 days'), rep_sale_price, sold_out
                    from (select id, rep_sale_price, sold_out from product where status = 'ON_SALE' and rep_sale_price is not null
                          order by md5(id::text || ?) limit ?) x
                    on conflict do nothing""", memberId, memberId.toString(), n);
        }
        // 테스트 계정(일반·플러스): 찜 3개는 찜할 때보다 10% 싸진 것처럼
        jdbc.update("""
                update wishlist w set notified_price = round(p.rep_sale_price * 1.1 / 10) * 10
                from product p where p.id = w.product_id and w.member_id in (1, 3) and not p.sold_out
                  and w.product_id in (select product_id from wishlist where member_id = w.member_id order by product_id limit 3)""");

        // 지난 주문 알림 (가짜 회원 전체)
        jdbc.update("""
                insert into notification(member_id, type, title, body, link, image_url, created_at, read_at)
                select o.member_id, 'ORDER', '주문이 완료되었어요',
                       (select oi.product_name from order_item oi where oi.order_id = o.id order by oi.id limit 1)
                         || case when (select count(*) from order_item oi where oi.order_id = o.id) > 1
                                 then ' 외 ' || ((select count(*) from order_item oi where oi.order_id = o.id) - 1) || '건' else '' end
                         || ' · ' || to_char(o.paid_amount, 'FM999,999,999') || '원 결제',
                       '/my/orders/' || o.order_no,
                       (select oi.image_url from order_item oi where oi.order_id = o.id order by oi.id limit 1),
                       o.paid_at, case when o.paid_at < now() - interval '3 days' then o.paid_at + interval '1 hour' end
                from orders o where o.paid_at is not null""");
        jdbc.update("""
                insert into notification(member_id, type, title, body, link, image_url, created_at, read_at)
                select o.member_id, 'DELIVERY', '배송이 완료되었어요', oi.product_name || ' · 문 앞 사진을 확인해 보세요',
                       '/my/orders/' || o.order_no, oi.image_url, oi.delivered_at,
                       case when oi.delivered_at < now() - interval '3 days' then oi.delivered_at + interval '2 hours' end
                from order_item oi join orders o on o.id = oi.order_id where oi.delivered_at is not null""");
        // 양팡캐시 소멸 확인용 (일반 테스트 계정): 10일 뒤 소멸될 500원, 이미 기간이 지난 300원
        jdbc.update("""
                insert into cash_ledger(id, member_id, amount, entry_type, description, expires_at, created_at)
                select m.base + x.n, 1, x.amount, 'EARN_EVENT', x.descr, now() + x.exp, now() - x.age
                from (select coalesce(max(id), 0) as base from cash_ledger) m,
                     (values (1, 500, '출석 이벤트 적립', interval '10 days', interval '355 days'),
                             (2, 300, '첫 리뷰 이벤트 적립', interval '-5 days', interval '370 days')) x(n, amount, descr, exp, age)""");

        // 혜택 알림 하나 (가입 축하)
        jdbc.update("""
                insert into notification(member_id, type, title, body, link, created_at, read_at)
                select id, 'BENEFIT', '가입 축하 쿠폰이 도착했어요', '3,000원 쿠폰을 쿠폰함에서 확인하세요', '/my/coupons', created_at, created_at + interval '1 day'
                from member where role <> 'ADMIN'""");
    }
}
