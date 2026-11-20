package com.yangpang.api.web;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** 템플릿에서 쓰는 날짜 형식 (한국 시간). 사용: ${@fmt.dateTime(order.orderedAt)} */
@Component("fmt")
public class Formats {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm", Locale.KOREAN);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy.MM.dd", Locale.KOREAN);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("M월 d일(E)", Locale.KOREAN);

    public String dateTime(Instant t) {
        return t == null ? "" : DATE_TIME.format(t.atZone(KST));
    }

    public String date(Instant t) {
        return t == null ? "" : DATE.format(t.atZone(KST));
    }

    /** 11월 21일(금) */
    public String day(LocalDate d) {
        return d == null ? "" : DAY.format(d);
    }

    public String day(Instant t) {
        return t == null ? "" : DAY.format(t.atZone(KST));
    }
}
