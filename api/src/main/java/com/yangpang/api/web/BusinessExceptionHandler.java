package com.yangpang.api.web;

import com.yangpang.domain.common.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.RedirectView;
import org.springframework.web.servlet.support.RequestContextUtils;

/**
 * 업무 규칙 위반(품절, 재고 부족 등) 처리.
 * - htmx 요청: 토스트로 메시지 표시
 * - 일반 요청: 이전 화면으로 돌아가 토스트 표시
 */
@ControllerAdvice
public class BusinessExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ModelAndView handle(BusinessException e, HttpServletRequest request, HttpServletResponse response) {
        if (Htmx.isHtmx(request)) {
            Htmx.retarget(response, "#toast");
            ModelAndView mav = new ModelAndView("fragments/toast :: toast");
            mav.addObject("message", e.getMessage());
            return mav;
        }
        RequestContextUtils.getOutputFlashMap(request).put("flashMessage", e.getMessage());
        String back = com.yangpang.api.security.SafeRedirect.sameSitePath(refererPath(request));
        return new ModelAndView(new RedirectView(back == null ? "/" : back, true));
    }

    /** 이미지 검색 사진이 5MB를 넘을 때 */
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ModelAndView tooLarge(HttpServletRequest request) {
        RequestContextUtils.getOutputFlashMap(request).put("flashMessage", "사진은 5MB까지 올릴 수 있어요");
        return new ModelAndView(new RedirectView("/search/image", true));
    }

    private String refererPath(HttpServletRequest request) {
        String referer = request.getHeader("Referer");
        if (referer == null) return null;
        try {
            java.net.URI uri = java.net.URI.create(referer);
            return uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
