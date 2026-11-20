package com.yangpang.api.web.review;

import com.yangpang.api.web.layout.CategoryMenu;
import com.yangpang.domain.catalog.Product;
import com.yangpang.domain.catalog.ProductRepository;
import com.yangpang.domain.member.Member;
import com.yangpang.domain.member.MemberRepository;
import com.yangpang.domain.review.Review;
import com.yangpang.domain.review.ReviewRepository;
import com.yangpang.domain.review.ReviewSurvey;
import com.yangpang.domain.review.SurveyJson;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 상품 상세의 리뷰 영역 화면 데이터 (기획서 5.11, 레퍼런스 17장) */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReviewViewService {

    public static final int PAGE_SIZE = 5;

    public enum SortOption {
        BEST("베스트순", Sort.by(Sort.Order.desc("helpfulCount"), Sort.Order.desc("id"))),
        LATEST("최신순", Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))),
        HIGH("평점 높은순", Sort.by(Sort.Order.desc("rating"), Sort.Order.desc("id"))),
        LOW("평점 낮은순", Sort.by(Sort.Order.asc("rating"), Sort.Order.desc("id")));

        public final String label;
        public final Sort sort;

        SortOption(String label, Sort sort) {
            this.label = label;
            this.sort = sort;
        }
    }

    public record Summary(BigDecimal average, long total, List<Bar> bars, List<SurveyStat> surveys, List<PhotoThumb> photos) {
    }

    public record Bar(int stars, long count, int percent) {
    }

    /** 맞춤 평가 한 항목: 가장 많이 고른 답과 비율 */
    public record SurveyStat(String question, List<Choice> choices) {
    }

    public record Choice(String label, int percent, boolean top) {
    }

    public record PhotoThumb(Long reviewId, String url) {
    }

    public record Item(Long id, String reviewer, int rating, String headline, String content, Map<String, String> survey,
                       String optionText, List<String> photos, int helpfulCount, boolean sponsored, boolean verified,
                       String sellerReply, java.time.Instant createdAt, boolean mine) {
        public int ratingPercent() {
            return rating * 20;
        }
    }

    public record ListPage(List<Item> items, boolean hasNext, int page, long total) {
    }

    private final ReviewRepository reviewRepository;
    private final ProductRepository productRepository;
    private final MemberRepository memberRepository;
    private final CategoryMenu categoryMenu;

    public Summary summary(Long productId) {
        Product p = productRepository.findById(productId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        Map<Integer, Long> dist = new HashMap<>();
        for (Object[] row : reviewRepository.ratingDistribution(productId)) {
            dist.put(((Number) row[0]).intValue(), ((Number) row[1]).longValue());
        }
        long total = dist.values().stream().mapToLong(Long::longValue).sum();
        List<Bar> bars = new ArrayList<>();
        for (int s = 5; s >= 1; s--) {
            long c = dist.getOrDefault(s, 0L);
            bars.add(new Bar(s, c, total == 0 ? 0 : (int) Math.round(c * 100.0 / total)));
        }

        // 맞춤 평가 통계
        String top = categoryMenu.path(p.getCategory().getId()).stream().findFirst().map(CategoryMenu.Node::name).orElse("");
        List<Map<String, String>> answers = reviewRepository.surveys(productId).stream().map(SurveyJson::read).toList();
        List<SurveyStat> stats = new ArrayList<>();
        for (ReviewSurvey.Question q : ReviewSurvey.questionsFor(top)) {
            Map<String, Long> counts = answers.stream().map(a -> a.get(q.name())).filter(q.choices()::contains)
                    .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
            long n = counts.values().stream().mapToLong(Long::longValue).sum();
            if (n == 0) continue;
            String best = counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
            stats.add(new SurveyStat(q.name(), q.choices().stream()
                    .map(c -> new Choice(c, (int) Math.round(counts.getOrDefault(c, 0L) * 100.0 / n), c.equals(best)))
                    .toList()));
        }

        // 사진 리뷰 모아보기 (최근 8장)
        List<PhotoThumb> photos = new ArrayList<>();
        reviewRepository.search(productId, (short) 0, true, "", PageRequest.of(0, 8, SortOption.LATEST.sort))
                .forEach(r -> photos.add(new PhotoThumb(r.getId(), photoUrl(r.getId(), 1))));

        return new Summary(p.getRatingAvg(), total, bars, stats, photos);
    }

    public ListPage list(Long productId, SortOption sort, int rating, boolean photoOnly, String keyword, int page, Long viewerId) {
        String kw = keyword == null ? "" : keyword.trim().toLowerCase();
        Page<Review> result = reviewRepository.search(productId, (short) rating, photoOnly, kw,
                PageRequest.of(Math.max(0, page - 1), PAGE_SIZE, sort.sort));
        Map<Long, Member> members = memberRepository.findAllById(result.map(Review::getMemberId).toSet())
                .stream().collect(Collectors.toMap(Member::getId, Function.identity()));
        List<Item> items = result.map(r -> new Item(
                r.getId(),
                mask(members.get(r.getMemberId())),
                r.getRating(),
                r.getHeadline(),
                r.getContent(),
                SurveyJson.read(r.getSurvey()),
                r.getOptionText(),
                java.util.stream.IntStream.rangeClosed(1, r.getPhotoCount()).mapToObj(n -> photoUrl(r.getId(), n)).toList(),
                r.getHelpfulCount(),
                r.isSponsored(),
                r.getOrderItemId() != null,
                r.getSellerReply(),
                r.getCreatedAt(),
                viewerId != null && viewerId.equals(r.getMemberId()))).getContent();
        return new ListPage(items, result.hasNext(), page, result.getTotalElements());
    }

    static String photoUrl(Long reviewId, int n) {
        return "/images/reviews/" + reviewId + "/" + n + ".svg";
    }

    /** 김테스트 → 김*트 (개인정보 가리기) */
    static String mask(Member m) {
        if (m == null) return "탈퇴회원";
        String n = m.getName();
        if (n.length() <= 1) return n;
        if (n.length() == 2) return n.charAt(0) + "*";
        return n.charAt(0) + "*".repeat(n.length() - 2) + n.charAt(n.length() - 1);
    }
}
