package com.yangpang.api.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** htmx 요청·응답 헤더 도우미 */
public final class Htmx {

    private Htmx() {
    }

    public static boolean isHtmx(HttpServletRequest request) {
        return request.getHeader("HX-Request") != null;
    }

    /** 헤더 장바구니 배지 갱신 이벤트 (app.js가 받음) */
    public static void triggerCartUpdated(HttpServletResponse response, long count) {
        response.setHeader("HX-Trigger", "{\"cartUpdated\":{\"count\":" + count + "}}");
    }

    /** 응답을 지정한 곳(예: #toast)에 넣도록 바꾼다 */
    public static void retarget(HttpServletResponse response, String selector) {
        response.setHeader("HX-Retarget", selector);
        response.setHeader("HX-Reswap", "innerHTML");
    }
}
