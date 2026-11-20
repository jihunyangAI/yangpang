package com.yangpang.seed.member;

import com.yangpang.seed.SeedContext;
import com.yangpang.seed.data.PeopleDictionary;

/** 가짜 이름·전화번호 생성 */
public final class FakePerson {

    private FakePerson() {
    }

    /** 성 + 음절 2개 (예: 김서윤) */
    public static String name(SeedContext ctx) {
        return ctx.pick(PeopleDictionary.SURNAMES)
                + ctx.pick(PeopleDictionary.GIVEN_SYLLABLES)
                + ctx.pick(PeopleDictionary.GIVEN_SYLLABLES);
    }

    /** 010-0000-XXXX: 실제로 개통되지 않는 대역 */
    public static String phone(int serial) {
        return String.format("010-0000-%04d", serial % 10_000);
    }
}
