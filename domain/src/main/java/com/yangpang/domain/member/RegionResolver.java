package com.yangpang.domain.member;

import com.yangpang.domain.common.Region;

import java.util.List;

/** 주소 → 배송 권역 (도착일 계산용, 단순 규칙) */
public final class RegionResolver {

    private RegionResolver() {
    }

    private static final List<String> METRO = List.of("서울", "경기", "인천");
    private static final List<String> REMOTE = List.of("제주", "울릉", "백령", "연평", "흑산", "독도");

    public static Region resolve(String address1) {
        if (address1 == null) return Region.LOCAL;
        String a = address1.trim();
        if (REMOTE.stream().anyMatch(a::contains)) return Region.REMOTE;
        if (METRO.stream().anyMatch(a::startsWith)) return Region.METRO;
        return Region.LOCAL;
    }
}
