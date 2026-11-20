package com.yangpang.domain.review;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 맞춤 평가 값(문자열 → 문자열)을 JSON 문자열로 저장·읽기.
 * 값은 정해진 선택지만 들어오므로 아주 단순한 형식만 다룬다.
 */
public final class SurveyJson {

    private SurveyJson() {
    }

    private static final Pattern PAIR = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    public static String write(Map<String, String> values) {
        if (values == null || values.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("{");
        values.forEach((k, v) -> {
            if (sb.length() > 1) sb.append(',');
            sb.append('"').append(escape(k)).append("\":\"").append(escape(v)).append('"');
        });
        return sb.append('}').toString();
    }

    public static Map<String, String> read(String json) {
        Map<String, String> result = new LinkedHashMap<>();
        if (json == null) return result;
        Matcher m = PAIR.matcher(json);
        while (m.find()) result.put(unescape(m.group(1)), unescape(m.group(2)));
        return result;
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String unescape(String s) {
        return s.replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
