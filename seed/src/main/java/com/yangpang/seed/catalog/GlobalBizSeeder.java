package com.yangpang.seed.catalog;

import com.yangpang.seed.SeedContext;
import com.yangpang.seed.Seeder;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 양팡직구·비즈 (M4 ⑤).
 * 직구: 뷰티·헬스/건강식품·가전디지털·완구/취미에서 양팡배송 대표 오퍼를 가진 상품 약 30%를 양팡직구(해외 배송)로 바꾼다.
 * 양팡설치: 책상·의자 상품의 양팡 물류 오퍼를 방문 설치 배송으로.
 * 비즈: 문구/오피스·생활용품·주방용품·식품·가전디지털 새 상품 오퍼 약 35%에 사업자 회원가(판매가의 약 92%, 10원 단위).
 * biz@example.com: 최근 결제 주문에 세금계산서 요청(발행 완료 1건, 요청 1건).
 */
@Component
@Order(100)
@RequiredArgsConstructor
public class GlobalBizSeeder implements Seeder {

    private final JdbcTemplate jdbc;

    @Override
    public String name() {
        return "양팡직구·비즈";
    }

    @Override
    public void seed(SeedContext ctx) {
        jdbc.execute("select setseed(" + (ctx.props.randomSeed() % 991) / 1000.0 + ")");

        // ---- 직구 ----
        jdbc.update("""
                create temporary table tmp_global on commit drop as
                select p.id as product_id from product p
                join category_tree t on t.id = p.category_id join category tc on tc.id = t.top_id
                where tc.name in ('뷰티', '헬스/건강식품', '가전디지털', '완구/취미') and p.status = 'ON_SALE'
                  and p.rep_delivery_type = 'YANGPANG' and random() < 0.3""");
        jdbc.update("""
                update vendor_item vi set delivery_type = 'GLOBAL'
                from item i where i.id = vi.item_id and i.product_id in (select product_id from tmp_global)
                  and vi.delivery_type = 'YANGPANG' and vi.item_condition = 'NEW'""");
        jdbc.update("update product set rep_delivery_type = 'GLOBAL' where id in (select product_id from tmp_global)");
        // 직구 상품은 정기배송·특가 대상에서 빼지 않는다 (정기배송은 저장된 통관부호를 쓴다)

        // ---- 양팡설치: 책상·의자 (조립·방문 설치) — 양팡 물류 오퍼를 설치 배송으로 ----
        jdbc.update("""
                create temporary table tmp_install on commit drop as
                select p.id as product_id from product p join category c on c.id = p.category_id
                where c.name in ('책상', '의자') and p.status = 'ON_SALE' and p.rep_delivery_type in ('YANGPANG', 'FULFILLMENT')""");
        jdbc.update("""
                update vendor_item vi set delivery_type = 'INSTALL'
                from item i where i.id = vi.item_id and i.product_id in (select product_id from tmp_install)
                  and vi.delivery_type in ('YANGPANG', 'FULFILLMENT') and vi.item_condition = 'NEW'""");
        jdbc.update("update product set rep_delivery_type = 'INSTALL' where id in (select product_id from tmp_install)");

        // ---- 비즈 사업자 회원가 ----
        jdbc.update("""
                update vendor_item vi set biz_price = greatest(10, floor(vi.sale_price * 0.92 / 10) * 10)
                from item i join product p on p.id = i.product_id join category_tree t on t.id = p.category_id
                join category tc on tc.id = t.top_id
                where i.id = vi.item_id and vi.item_condition = 'NEW' and vi.sale_price >= 1000
                  and tc.name in ('문구/오피스', '생활용품', '주방용품', '식품', '가전디지털') and random() < 0.35""");

        // ---- biz@ 세금계산서 요청 ----
        jdbc.update("""
                insert into tax_invoice(order_id, member_id, business_no, company_name, ceo_name, email, amount, status, invoice_no, requested_at, issued_at)
                select o.id, o.member_id, '123-45-67890', '양팡테스트상사(가짜)', '김비즈', 'tax@example.com',
                       o.paid_amount + o.cash_used - o.refunded_amount,
                       case when x.rn = 1 then 'REQUESTED' else 'ISSUED' end,
                       case when x.rn = 1 then null else to_char(o.ordered_at + interval '1 day', 'YYYYMMDD') || '-YP-' || lpad(o.id::text, 7, '0') end,
                       o.ordered_at + interval '1 day',
                       case when x.rn = 1 then null else o.ordered_at + interval '1 day 1 hour' end
                from orders o join member m on m.id = o.member_id
                join lateral (select count(*) + 1 as rn from orders o2 where o2.member_id = o.member_id and o2.id > o.id
                              and o2.status not in ('PENDING_PAYMENT', 'PAYMENT_FAILED', 'CANCELED')) x on true
                where m.email = 'biz@example.com' and o.status not in ('PENDING_PAYMENT', 'PAYMENT_FAILED', 'CANCELED')
                  and o.paid_amount + o.cash_used - o.refunded_amount > 0 and x.rn <= 2""");
    }
}
