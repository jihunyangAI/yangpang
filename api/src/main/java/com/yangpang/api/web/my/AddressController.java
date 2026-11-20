package com.yangpang.api.web.my;

import com.yangpang.api.security.MemberPrincipal;
import com.yangpang.api.security.SafeRedirect;
import com.yangpang.domain.common.EntranceType;
import com.yangpang.domain.member.AddressService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** 배송지 관리 (기획서 MY-04) */
@Controller
@RequestMapping("/my/addresses")
@RequiredArgsConstructor
public class AddressController {

    private final AddressService addressService;

    @ModelAttribute("entranceTypes")
    EntranceType[] entranceTypes() {
        return EntranceType.values();
    }

    @GetMapping
    public String list(@AuthenticationPrincipal MemberPrincipal me, Model model) {
        model.addAttribute("addresses", addressService.list(me.id()));
        return "my/addresses";
    }

    @GetMapping("/new")
    public String newForm(@RequestParam(name = "continue", required = false) String continueUrl, Model model) {
        model.addAttribute("form", new AddressForm());
        model.addAttribute("continueUrl", SafeRedirect.sameSitePath(continueUrl));
        return "my/address-form";
    }

    @PostMapping("/new")
    public String create(@AuthenticationPrincipal MemberPrincipal me,
                         @Valid @ModelAttribute("form") AddressForm form, BindingResult binding,
                         @RequestParam(name = "continue", required = false) String continueUrl,
                         Model model, RedirectAttributes redirect) {
        String back = SafeRedirect.sameSitePath(continueUrl);
        if (binding.hasErrors()) {
            model.addAttribute("continueUrl", back);
            return "my/address-form";
        }
        addressService.add(me.id(), form.toFields(), form.isMakeDefault());
        redirect.addFlashAttribute("flashMessage", "배송지를 추가했어요");
        return "redirect:" + (back == null ? "/my/addresses" : back);
    }

    @GetMapping("/{id}/edit")
    public String editForm(@AuthenticationPrincipal MemberPrincipal me, @PathVariable Long id, Model model) {
        model.addAttribute("form", AddressForm.from(addressService.get(me.id(), id)));
        model.addAttribute("addressId", id);
        return "my/address-form";
    }

    @PostMapping("/{id}/edit")
    public String update(@AuthenticationPrincipal MemberPrincipal me, @PathVariable Long id,
                         @Valid @ModelAttribute("form") AddressForm form, BindingResult binding,
                         Model model, RedirectAttributes redirect) {
        if (binding.hasErrors()) {
            model.addAttribute("addressId", id);
            return "my/address-form";
        }
        addressService.update(me.id(), id, form.toFields(), form.isMakeDefault());
        redirect.addFlashAttribute("flashMessage", "배송지를 수정했어요");
        return "redirect:/my/addresses";
    }

    @PostMapping("/{id}/default")
    public String makeDefault(@AuthenticationPrincipal MemberPrincipal me, @PathVariable Long id) {
        addressService.setDefault(me.id(), id);
        return "redirect:/my/addresses";
    }

    @PostMapping("/{id}/delete")
    public String delete(@AuthenticationPrincipal MemberPrincipal me, @PathVariable Long id, RedirectAttributes redirect) {
        addressService.delete(me.id(), id);
        redirect.addFlashAttribute("flashMessage", "배송지를 삭제했어요");
        return "redirect:/my/addresses";
    }
}
