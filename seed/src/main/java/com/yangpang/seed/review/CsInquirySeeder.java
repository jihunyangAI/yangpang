package com.yangpang.seed.review;

import com.yangpang.seed.SeedContext;
import com.yangpang.seed.Seeder;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 고객센터 1:1 문의 (기획서 §20). normal@example.com 2건(답변 완료 1·대기 1), 다른 회원 문의 10건(절반은 운영자 답변 대기).
 * FAQ는 V26 마이그레이션의 운영 콘텐츠라 여기서 만들지 않는다.
 */
@Component
@Order(104)
@RequiredArgsConstructor
public class CsInquirySeeder implements Seeder {

    private final JdbcTemplate jdbc;

    private static final String[][] SAMPLES = {
            {"DELIVERY", "배송지를 바꾸고 싶어요", "아직 출고 전이면 배송지를 회사 주소로 바꿀 수 있을까요?", "출고 전 주문은 주문 상세에서 취소 후 다시 주문해 주셔야 해요. 번거롭게 해 드려 죄송합니다."},
            {"CLAIM", "반품 회수가 언제 오나요?", "반품 신청한 지 하루가 지났는데 회수 기사님 연락이 없어요.", "반품 접수 후 보통 1~2일 안에 회수돼요. 회수 진행 상황은 취소·반품·교환 내역에서 볼 수 있어요."},
            {"ORDER", "가상계좌 입금 기한을 놓쳤어요", "입금 기한이 지나서 주문이 취소됐는데 같은 가격으로 다시 살 수 있나요?", "취소된 주문은 되살릴 수 없어 다시 주문해 주셔야 해요. 특가 상품은 가격이 달라질 수 있어요."},
            {"BENEFIT", "쿠폰이 주문서에 안 보여요", "쿠폰존에서 받은 쿠폰이 주문서에서 선택이 안 돼요.", "쿠폰마다 최소 주문 금액과 적용 카테고리가 있어요. 조건에 맞는 상품을 담으면 자동으로 골라져요."},
            {"PLUS", "양팡플러스 해지하면 바로 끝나나요?", "해지 신청하면 남은 기간 혜택은 어떻게 되나요?", "해지해도 이번 결제 기간이 끝날 때까지는 혜택을 그대로 쓰실 수 있어요."},
            {"ACCOUNT", "휴대폰 번호를 바꿨어요", "번호가 바뀌었는데 회원정보에서 바꿀 수 있나요?", null},
            {"DELIVERY", "도착 보장일이 지났어요", "내일 도착이라고 했는데 아직 배송중으로 나와요.", null},
            {"ETC", "영수증 발급이 되나요?", "회사 제출용 구매 영수증이 필요해요.", null},
    };

    @Override
    public String name() {
        return "고객센터 1:1 문의";
    }

    @Override
    public void seed(SeedContext ctx) {
        Instant now = Instant.now();
        List<Map<String, Object>> members = jdbc.queryForList("""
                select id, email from member where role = 'USER' order by (email = 'normal@example.com') desc, id limit 11""");
        for (int k = 0; k < members.size(); k++) {
            Long memberId = ((Number) members.get(k).get("id")).longValue();
            boolean normal = "normal@example.com".equals(members.get(k).get("email"));
            int count = normal ? 2 : 1;
            for (int j = 0; j < count; j++) {
                String[] s = normal ? SAMPLES[j == 0 ? 0 : 5] : SAMPLES[ctx.random.nextInt(SAMPLES.length)];
                boolean answer = s[3] != null && (normal ? j == 0 : ctx.random.nextBoolean());
                Instant created = now.minus(Duration.ofHours(ctx.between(2, 24 * 20)));
                jdbc.update("""
                        insert into cs_inquiry(member_id, category, title, content, status, answer, answered_at, created_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?)""", memberId, s[0], s[1], s[2], answer ? "ANSWERED" : "WAITING",
                        answer ? s[3] : null, answer ? Timestamp.from(created.plus(Duration.ofHours(ctx.between(1, 20)))) : null,
                        Timestamp.from(created));
            }
        }
    }
}
