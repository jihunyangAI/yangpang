package com.yangpang.api.web.product;

import com.yangpang.api.security.MemberPrincipal;
import com.yangpang.domain.member.ShoppingHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 상품 상세 (기획서 P-01~P-08).
 * 옵션 버튼은 htmx로 같은 주소(?item=)를 요청하고 구매 영역(#buy-box)만 바꿔 끼운다.
 * 로그인한 회원은 최근 본 상품에 기록한다.
 */
@Controller
@RequiredArgsConstructor
public class ProductDetailController {

    private final com.yangpang.domain.reco.UserEventService userEventService;
    private final ProductDetailService detailService;
    private final com.yangpang.domain.member.AdultVerificationService adultVerification;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final com.yangpang.domain.subscription.SubscriptionService subscriptionService;
    private final com.yangpang.domain.reco.RecommendationService recommendationService;
    private final ProductCardService productCardService;
    private final ShoppingHistoryService historyService;
    private final com.yangpang.api.web.seller.SellerQueries sellerQueries;
    private final com.yangpang.domain.catalog.RankingService rankingService;

    /** "다른 판매자 보기" (htmx 조각) */
    @GetMapping("/products/offers")
    public String otherOffers(@org.springframework.web.bind.annotation.RequestParam Long itemId, Model model) {
        model.addAttribute("offers", sellerQueries.otherOffers(itemId));
        return "product/other-offers :: offers";
    }

    @GetMapping("/products/{id}")
    public String detail(@PathVariable Long id, @RequestParam(required = false) Long item,
                         @AuthenticationPrincipal MemberPrincipal me, Model model) {
        // 19세 이상 상품은 성인 인증한 회원만 (그 밖에는 연령 확인 화면)
        if (Boolean.TRUE.equals(jdbc.queryForObject("select adult from product where id = ?", Boolean.class, id))
                && (me == null || !adultVerification.verified(me.id()))) {
            model.addAttribute("productId", id);
            model.addAttribute("loggedIn", me != null);
            return "product/adult-gate";
        }
        model.addAttribute("p", detailService.detail(id, item));
        model.addAttribute("subscribable", subscriptionService.subscribable(id));
        model.addAttribute("bizViewer", me != null && me.isBiz());
        model.addAttribute("coViewed", productCardService.cardsByIds(recommendationService.coViewed(id, 12)));
        rankingService.recordView(id); // 조회 수 (랭킹의 클릭·전환율 지표)
        if (me != null) {
            historyService.recordView(me.id(), id);
            userEventService.product(me.id(), com.yangpang.domain.reco.UserEventService.Type.VIEW, id);
            model.addAttribute("wished", historyService.isWished(me.id(), id));
        } else {
            model.addAttribute("wished", false);
        }
        return "product/detail";
    }
}
