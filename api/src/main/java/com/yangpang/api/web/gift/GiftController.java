package com.yangpang.api.web.gift;

import com.yangpang.domain.common.BusinessException;
import com.yangpang.domain.common.EntranceType;
import com.yangpang.domain.gift.GiftService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 선물 받기 (M4 ④). 받는 분이 (가짜) 문자로 받은 링크를 열어 배송지를 입력한다. 로그인 없이 열린다 (토큰이 열쇠).
 * 받는 분 화면에는 가격을 보여 주지 않는다.
 */
@Controller
@RequiredArgsConstructor
public class GiftController {

    private final GiftService giftService;

    @GetMapping("/gift/{token}")
    public String view(@PathVariable String token, Model model) {
        model.addAttribute("v", giftService.view(token).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
        model.addAttribute("entranceTypes", EntranceType.values());
        return "gift/view";
    }

    @PostMapping("/gift/{token}")
    public String accept(@PathVariable String token, @RequestParam String zipcode, @RequestParam String address1,
                         @RequestParam(required = false) String address2,
                         @RequestParam(defaultValue = "FREE") EntranceType entranceType,
                         @RequestParam(required = false) String entranceNote,
                         @RequestParam(required = false) String requestNote, RedirectAttributes redirect) {
        if (giftService.view(token).isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        try {
            giftService.accept(token, new GiftService.Destination(zipcode, address1, address2, entranceType, entranceNote, requestNote));
            redirect.addFlashAttribute("flashMessage", "배송지를 받았어요. 곧 출고할게요");
        } catch (BusinessException e) {
            redirect.addFlashAttribute("flashMessage", e.getMessage());
        }
        return "redirect:/gift/" + token;
    }
}
