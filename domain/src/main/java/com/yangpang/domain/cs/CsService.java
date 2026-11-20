package com.yangpang.domain.cs;

import com.yangpang.domain.common.BusinessException;
import com.yangpang.domain.notification.Notification;
import com.yangpang.domain.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 고객센터 (기획서 §20): 자주 묻는 질문(FAQ) · 1:1 문의. 답변은 운영자가 한다 (답변하면 회원에게 알림).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class CsService {

    public enum Category {
        ORDER("주문·결제"), DELIVERY("배송"), CLAIM("취소·반품·교환"), PLUS("양팡플러스·정기배송"), BENEFIT("쿠폰·캐시"),
        ACCOUNT("회원·계정"), SELLER("판매자"), ETC("기타");

        private final String label;

        Category(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public record Faq(Long id, Category category, String question, String answer, int sortOrder, int viewCount) {
    }

    public record Inquiry(Long id, Long memberId, String memberEmail, Category category, String orderNo, String title, String content,
                          String status, String answer, Instant answeredAt, Instant createdAt) {
        public boolean answered() {
            return "ANSWERED".equals(status);
        }
    }

    private final JdbcTemplate jdbc;
    private final NotificationService notificationService;
    private final Clock clock;

    // ------------------------------------------------------------------ FAQ

    /** 분류·검색어로 FAQ (검색어는 질문·답변에서 찾는다) */
    @Transactional(readOnly = true)
    public List<Faq> faqs(Category category, String query) {
        StringBuilder sql = new StringBuilder("select id, category, question, answer, sort_order, view_count from faq where true");
        List<Object> args = new ArrayList<>();
        if (category != null) {
            sql.append(" and category = ?");
            args.add(category.name());
        }
        if (query != null && !query.isBlank()) {
            for (String w : query.trim().toLowerCase().split("\\s+")) {
                sql.append(" and lower(question || ' ' || answer) like ?");
                args.add("%" + w.replace("%", "").replace("_", "") + "%");
            }
        }
        sql.append(" order by ").append(category == null && (query == null || query.isBlank()) ? "view_count desc, " : "")
                .append("category, sort_order, id");
        return jdbc.query(sql.toString(), (rs, n) -> new Faq(rs.getLong(1), Category.valueOf(rs.getString(2)), rs.getString(3),
                rs.getString(4), rs.getInt(5), rs.getInt(6)), args.toArray());
    }

    /** 펼쳐 본 수 (많이 본 질문 순서용) */
    public void viewed(Long faqId) {
        jdbc.update("update faq set view_count = view_count + 1 where id = ?", faqId);
    }

    public void saveFaq(Long id, Category category, String question, String answer, int sortOrder) {
        String q = text(question, 200, "질문을 입력해 주세요");
        String a = text(answer, 4000, "답변을 입력해 주세요");
        if (category == null) throw new BusinessException("분류를 골라 주세요");
        if (id == null) {
            jdbc.update("insert into faq(category, question, answer, sort_order) values (?, ?, ?, ?)", category.name(), q, a, sortOrder);
        } else if (jdbc.update("update faq set category = ?, question = ?, answer = ?, sort_order = ? where id = ?",
                category.name(), q, a, sortOrder, id) == 0) {
            throw new BusinessException("FAQ를 찾을 수 없어요");
        }
    }

    public void deleteFaq(Long id) {
        jdbc.update("delete from faq where id = ?", id);
    }

    // ------------------------------------------------------------------ 1:1 문의

    public Long ask(Long memberId, Category category, String orderNo, String title, String content) {
        if (category == null) throw new BusinessException("문의 유형을 골라 주세요");
        String t = text(title, 60, "제목을 입력해 주세요");
        String c = text(content, 2000, "내용을 입력해 주세요");
        if (c.length() < 10) throw new BusinessException("내용을 10자 이상 적어 주세요");
        Long orderId = null;
        if (orderNo != null && !orderNo.isBlank()) {
            orderId = jdbc.query("select id from orders where order_no = ? and member_id = ?",
                    rs -> rs.next() ? rs.getLong(1) : null, orderNo, memberId);
            if (orderId == null) throw new BusinessException("내 주문만 고를 수 있어요");
        }
        Integer waiting = jdbc.queryForObject("select count(*) from cs_inquiry where member_id = ? and status = 'WAITING'", Integer.class, memberId);
        if (waiting != null && waiting >= 5) throw new BusinessException("답변을 기다리는 문의가 5건 있어요. 답변을 받은 뒤 다시 문의해 주세요");
        return jdbc.queryForObject("""
                insert into cs_inquiry(member_id, category, order_id, title, content, created_at) values (?, ?, ?, ?, ?, ?) returning id""",
                Long.class, memberId, category.name(), orderId, t, c, Timestamp.from(clock.instant()));
    }

    @Transactional(readOnly = true)
    public List<Inquiry> mine(Long memberId) {
        return query("where q.member_id = ? order by q.created_at desc limit 50", memberId);
    }

    @Transactional(readOnly = true)
    public Optional<Inquiry> mine(Long memberId, Long id) {
        return query("where q.member_id = ? and q.id = ?", memberId, id).stream().findFirst();
    }

    /** 운영자: 상태별 목록 (답변 대기는 오래된 순) */
    @Transactional(readOnly = true)
    public List<Inquiry> forAdmin(String status) {
        return "ANSWERED".equals(status)
                ? query("where q.status = 'ANSWERED' order by q.answered_at desc limit 100")
                : query("where q.status = 'WAITING' order by q.created_at limit 100");
    }

    public void answer(Long id, String answer) {
        String a = text(answer, 2000, "답변을 입력해 주세요");
        if (a.length() < 5) throw new BusinessException("답변을 5자 이상 적어 주세요");
        Instant now = clock.instant();
        List<Object[]> done = jdbc.query("""
                update cs_inquiry set status = 'ANSWERED', answer = ?, answered_at = ? where id = ? and status = 'WAITING'
                returning member_id, title""", (rs, n) -> new Object[]{rs.getLong(1), rs.getString(2)}, a, Timestamp.from(now), id);
        if (done.isEmpty()) throw new BusinessException("이미 답변했거나 없는 문의예요");
        notificationService.notify((Long) done.getFirst()[0], Notification.Type.ORDER, "1:1 문의에 답변이 달렸어요",
                (String) done.getFirst()[1], "/cs/inquiries/" + id, null);
    }

    private List<Inquiry> query(String where, Object... args) {
        return jdbc.query("""
                select q.id, q.member_id, m.email, q.category, o.order_no, q.title, q.content, q.status, q.answer, q.answered_at, q.created_at
                from cs_inquiry q join member m on m.id = q.member_id left join orders o on o.id = q.order_id""" + " " + where,
                (rs, n) -> new Inquiry(rs.getLong(1), rs.getLong(2), rs.getString(3), Category.valueOf(rs.getString(4)), rs.getString(5),
                        rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9),
                        rs.getTimestamp(10) == null ? null : rs.getTimestamp(10).toInstant(), rs.getTimestamp(11).toInstant()), args);
    }

    private static String text(String s, int max, String message) {
        if (s == null || s.isBlank()) throw new BusinessException(message);
        String t = s.trim();
        if (t.length() > max) throw new BusinessException(max + "자까지 쓸 수 있어요");
        return t;
    }
}
