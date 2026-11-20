package com.yangpang.api.web.coupon;

import com.yangpang.api.security.MemberPrincipal;
import com.yangpang.domain.catalog.CategoryPaths;
import com.yangpang.domain.catalog.Product;
import com.yangpang.domain.catalog.ProductRepository;
import com.yangpang.domain.coupon.CashLedgerRepository;
import com.yangpang.domain.coupon.CashService;
import com.yangpang.domain.coupon.Coupon;
import com.yangpang.domain.coupon.CouponRepository;
import com.yangpang.domain.coupon.CouponService;
import com.yangpang.domain.coupon.MemberCoupon;
import com.yangpang.domain.coupon.MemberCouponRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 쿠폰존·상품 쿠폰 받기·내 쿠폰함·양팡캐시 (기획서 P-06, MY-06, 5.12) */
@Controller
@RequiredArgsConstructor
public class CouponController {

    private final CouponRepository couponRepository;
    private final MemberCouponRepository memberCouponRepository;
    private final CouponService couponService;
    private final CashService cashService;
    private final CashLedgerRepository ledgerRepository;
    private final ProductRepository productRepository;
    private final CategoryPaths categoryPaths;
    private final Clock clock;

    @GetMapping("/coupons")
    @Transactional(readOnly = true)
    public String zone(@AuthenticationPrincipal MemberPrincipal me, Model model) {
        model.addAttribute("coupons", couponRepository.findByActiveTrueAndDownloadableTrueOrderByIdDesc().stream()
                .filter(c -> c.isIssuable(clock.instant())).toList());
        model.addAttribute("owned", owned(me));
        return "coupon/zone";
    }

    /** 쿠폰 받기 (htmx) → 버튼 자리를 "받음"으로 */
    @PostMapping("/coupons/{id}/download")
    public String download(@AuthenticationPrincipal MemberPrincipal me, @PathVariable Long id, Model model) {
        couponService.download(me.id(), id);
        model.addAttribute("couponId", id);
        return "coupon/zone :: downloaded";
    }

    /** 상품 상세의 "쿠폰 받기" 목록 (htmx 조각) */
    @GetMapping("/products/{id}/coupons")
    @Transactional(readOnly = true)
    public String productCoupons(@PathVariable Long id, @AuthenticationPrincipal MemberPrincipal me, Model model) {
        Product p = productRepository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        Set<Long> path = categoryPaths.all().getOrDefault(p.getCategory().getId(), Set.of());
        List<Coupon> applicable = couponRepository.findByActiveTrueAndDownloadableTrueOrderByIdDesc().stream()
                .filter(c -> c.isIssuable(clock.instant()))
                .filter(c -> c.toSpec(0).appliesTo(p.getId(), path))
                .toList();
        model.addAttribute("coupons", applicable);
        model.addAttribute("owned", owned(me));
        return "coupon/zone :: productCoupons";
    }

    @GetMapping("/my/coupons")
    @Transactional
    public String myCoupons(@AuthenticationPrincipal MemberPrincipal me, Model model) {
        couponService.usable(me.id()); // 기간 지난 쿠폰을 만료로 정리
        List<MemberCoupon> all = memberCouponRepository.findAllOfMember(me.id());
        model.addAttribute("available", all.stream().filter(m -> m.getStatus() == MemberCoupon.Status.AVAILABLE).toList());
        model.addAttribute("ended", all.stream().filter(m -> m.getStatus() != MemberCoupon.Status.AVAILABLE).toList());
        return "my/coupons";
    }

    @GetMapping("/my/cash")
    @Transactional(readOnly = true)
    public String myCash(@AuthenticationPrincipal MemberPrincipal me, Model model) {
        model.addAttribute("balance", cashService.balance(me.id()));
        model.addAttribute("expiringSoon", cashService.expiringSoon(me.id()));
        model.addAttribute("entries", ledgerRepository.findByMemberIdOrderByCreatedAtDescIdDesc(me.id(), PageRequest.of(0, 100)).getContent());
        return "my/cash";
    }

    private Set<Long> owned(MemberPrincipal me) {
        return me == null ? Set.of() : new HashSet<>(memberCouponRepository.ownedCouponIds(me.id()));
    }
}
