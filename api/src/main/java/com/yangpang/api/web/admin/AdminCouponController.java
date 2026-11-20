package com.yangpang.api.web.admin;

import com.yangpang.api.web.layout.CategoryMenu;
import com.yangpang.domain.common.BusinessException;
import com.yangpang.domain.coupon.Coupon;
import com.yangpang.domain.coupon.CouponRepository;
import com.yangpang.domain.coupon.CouponSpec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Clock;

/** 관리자 쿠폰 관리 (기획서 5.16): 목록, 만들기, 배포 중지 */
@Controller
@RequestMapping("/admin/coupons")
@RequiredArgsConstructor
public class AdminCouponController {

    private final CouponRepository couponRepository;
    private final CategoryMenu categoryMenu;
    private final Clock clock;

    @GetMapping
    public String list(Model model) {
        model.addAttribute("coupons", couponRepository.findAllByOrderByIdDesc());
        model.addAttribute("categories", categoryMenu.roots());
        return "admin/coupons";
    }

    @PostMapping
    @Transactional
    public String create(@RequestParam String name, @RequestParam CouponSpec.Kind kind,
                         @RequestParam CouponSpec.DiscountType discountType, @RequestParam long discountValue,
                         @RequestParam(required = false) Long maxDiscount, @RequestParam(defaultValue = "0") long minOrderAmount,
                         @RequestParam(required = false) Long targetCategoryId, @RequestParam(defaultValue = "30") int validDays,
                         @RequestParam(required = false) Integer totalQuantity,
                         @RequestParam(defaultValue = "false") boolean signup, RedirectAttributes redirect) {
        if (name.isBlank()) throw new BusinessException("쿠폰 이름을 입력해 주세요");
        if (discountValue <= 0) throw new BusinessException("할인 값을 입력해 주세요");
        if (discountType == CouponSpec.DiscountType.RATE && discountValue > 90) throw new BusinessException("정률 할인은 90%까지예요");
        if (validDays < 1 || validDays > 365) throw new BusinessException("사용 기간은 1~365일이에요");
        couponRepository.save(Coupon.create(name.trim(), kind, discountType, discountValue, maxDiscount, minOrderAmount,
                targetCategoryId, validDays, totalQuantity, !signup, signup ? "SIGNUP" : null, clock.instant()));
        redirect.addFlashAttribute("flashMessage", "쿠폰 '" + name.trim() + "'을 만들었어요");
        return "redirect:/admin/coupons";
    }

    @PostMapping("/{id}/deactivate")
    @Transactional
    public String deactivate(@PathVariable Long id, RedirectAttributes redirect) {
        couponRepository.findById(id).orElseThrow().deactivate();
        redirect.addFlashAttribute("flashMessage", "배포를 중지했어요 (이미 받은 쿠폰은 계속 쓸 수 있어요)");
        return "redirect:/admin/coupons";
    }
}
