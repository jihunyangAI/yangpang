package com.yangpang.domain.review;

import com.yangpang.domain.common.BusinessException;
import com.yangpang.domain.order.OrderItem;
import com.yangpang.domain.order.OrderItemRepository;
import com.yangpang.domain.order.OrderProgressService;
import com.yangpang.domain.order.OrderStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;

/**
 * 리뷰 (기획서 5.11).
 * - 배송완료·구매확정 상품만, 주문 상품당 1개 (구매 인증 리뷰)
 * - 배송완료 상태에서 쓰면 자동으로 구매확정된다 (쿠팡과 같은 방식)
 * - 작성·수정·삭제 때마다 상품 별점 평균·리뷰 수를 다시 계산한다
 */
@Service
@RequiredArgsConstructor
@Transactional
public class ReviewService {

    public static final int MIN_CONTENT = 10;
    public static final int MAX_CONTENT = 2000;
    public static final int MAX_PHOTOS = 3;

    public record Input(int rating, String headline, String content, Map<String, String> survey, int photoCount) {
    }

    private final ReviewRepository reviewRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderProgressService progressService;
    private final com.yangpang.domain.coupon.CashService cashService;
    private final com.yangpang.domain.catalog.ProductSummaryService summaryService;
    private final Clock clock;

    public Review write(Long memberId, Long orderItemId, Input in) {
        OrderItem item = orderItemRepository.findMine(orderItemId, memberId)
                .orElseThrow(() -> new BusinessException("주문한 상품이 아니에요"));
        if (item.getStatus() != OrderStatus.DELIVERED && item.getStatus() != OrderStatus.CONFIRMED) {
            throw new BusinessException("배송이 완료된 상품만 리뷰를 쓸 수 있어요");
        }
        if (reviewRepository.findByOrderItemId(orderItemId).isPresent()) {
            throw new BusinessException("이미 리뷰를 쓴 상품이에요");
        }
        validate(in);
        Review review = reviewRepository.save(Review.create(item.getProductId(), item.getId(), memberId, in.rating(),
                trim(in.headline(), 60), in.content().trim(), SurveyJson.write(in.survey()), item.getOptionText(),
                in.photoCount(), clock.instant()));
        if (item.getStatus() == OrderStatus.DELIVERED) {
            progressService.advance(item.getId(), OrderStatus.DELIVERED, "MEMBER", "리뷰 작성으로 구매확정");
        }
        // 리뷰 적립 (사진 리뷰는 더 많이)
        cashService.earnOnce(memberId, in.photoCount() > 0 ? com.yangpang.domain.coupon.CashService.REVIEW_PHOTO
                        : com.yangpang.domain.coupon.CashService.REVIEW_TEXT,
                com.yangpang.domain.coupon.CashLedger.Type.EARN_REVIEW, item.getOrder().getId(),
                "리뷰 작성 적립 · " + item.getProductName());
        reviewRepository.flush();
        reviewRepository.recalculateProduct(item.getProductId());
        summaryService.changed(java.util.Set.of(item.getProductId()));
        return review;
    }

    public void edit(Long memberId, Long reviewId, Input in) {
        Review r = mine(memberId, reviewId);
        validate(in);
        r.edit(in.rating(), trim(in.headline(), 60), in.content().trim(), SurveyJson.write(in.survey()), in.photoCount(),
                clock.instant());
        reviewRepository.flush();
        reviewRepository.recalculateProduct(r.getProductId());
        summaryService.changed(java.util.Set.of(r.getProductId()));
    }

    public void delete(Long memberId, Long reviewId) {
        Review r = mine(memberId, reviewId);
        reviewRepository.delete(r);
        reviewRepository.flush();
        reviewRepository.recalculateProduct(r.getProductId());
        summaryService.changed(java.util.Set.of(r.getProductId()));
    }

    /** "도움이 돼요" (한 사람당 한 번, 내 리뷰에는 불가). 바뀐 개수를 돌려준다 */
    public int helpful(Long memberId, Long reviewId) {
        Review r = reviewRepository.findById(reviewId).orElseThrow(() -> new BusinessException("리뷰가 없어요"));
        if (r.getMemberId().equals(memberId)) throw new BusinessException("내 리뷰에는 누를 수 없어요");
        int added = reviewRepository.addHelpful(reviewId, memberId);
        if (added == 0) throw new BusinessException("이미 도움이 돼요를 눌렀어요");
        reviewRepository.incrementHelpful(reviewId);
        return r.getHelpfulCount() + 1;
    }

    /** 신고 5번이면 숨김 */
    public void report(Long memberId, Long reviewId) {
        Review r = reviewRepository.findById(reviewId).orElseThrow(() -> new BusinessException("리뷰가 없어요"));
        if (r.getMemberId().equals(memberId)) throw new BusinessException("내 리뷰는 신고할 수 없어요");
        reviewRepository.report(reviewId);
        reviewRepository.recalculateProduct(r.getProductId());
    }

    /** 판매자(운영자) 답글 */
    public void reply(Long reviewId, String text) {
        Review r = reviewRepository.findById(reviewId).orElseThrow(() -> new BusinessException("리뷰가 없어요"));
        if (text == null || text.isBlank()) throw new BusinessException("답글을 입력해 주세요");
        r.reply(trim(text, 1000), clock.instant());
    }

    private Review mine(Long memberId, Long reviewId) {
        return reviewRepository.findById(reviewId).filter(r -> r.getMemberId().equals(memberId))
                .orElseThrow(() -> new BusinessException("내 리뷰가 아니에요"));
    }

    private static void validate(Input in) {
        if (in.rating() < 1 || in.rating() > 5) throw new BusinessException("별점을 선택해 주세요");
        String c = in.content() == null ? "" : in.content().trim();
        if (c.length() < MIN_CONTENT) throw new BusinessException("리뷰 내용을 " + MIN_CONTENT + "자 이상 써 주세요");
        if (c.length() > MAX_CONTENT) throw new BusinessException("리뷰는 " + MAX_CONTENT + "자까지 쓸 수 있어요");
        if (in.photoCount() < 0 || in.photoCount() > MAX_PHOTOS) throw new BusinessException("사진은 " + MAX_PHOTOS + "장까지예요");
    }

    private static String trim(String s, int max) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
