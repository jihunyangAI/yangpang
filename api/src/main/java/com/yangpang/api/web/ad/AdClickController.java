package com.yangpang.api.web.ad;

import com.yangpang.api.security.MemberPrincipal;
import com.yangpang.domain.ad.AdService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** 검색 광고 클릭: 서명 확인 → 한 번만 모의 과금 → 상품으로 이동 */
@Controller
@RequiredArgsConstructor
public class AdClickController {

    private final AdService adService;

    @GetMapping("/ad/click")
    public String click(@RequestParam long k, @RequestParam int c, @RequestParam long t,
                        @RequestParam(required = false) String n, @RequestParam(required = false) String s,
                        @AuthenticationPrincipal MemberPrincipal me) {
        Long productId = adService.click(k, c, t, n, s, me == null ? null : me.id());
        return productId == null ? "redirect:/" : "redirect:/products/" + productId;
    }
}
