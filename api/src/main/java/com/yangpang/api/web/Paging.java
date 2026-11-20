package com.yangpang.api.web;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.stream.IntStream;

/** 페이지 번호 목록 (현재 페이지 주변 최대 10개, 1부터 시작) */
public final class Paging {

    private Paging() {
    }

    public static List<Integer> pageNumbers(Page<?> page) {
        int total = Math.max(page.getTotalPages(), 1);
        int current = page.getNumber() + 1;
        int start = Math.max(1, Math.min(current - 4, total - 9));
        int end = Math.min(total, start + 9);
        return IntStream.rangeClosed(start, end).boxed().toList();
    }
}
