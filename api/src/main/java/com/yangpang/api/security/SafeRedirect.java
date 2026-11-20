package com.yangpang.api.security;

/** 오픈 리다이렉트 방지: 같은 사이트 안의 경로("/..."로 시작, "//"·"/\" 아님)만 허용 */
public final class SafeRedirect {

    private SafeRedirect() {
    }

    /** 안전한 경로면 그대로, 아니면 null */
    public static String sameSitePath(String path) {
        if (path == null || path.isBlank()) return null;
        if (!path.startsWith("/") || path.startsWith("//") || path.startsWith("/\\")) return null;
        if (path.contains("\r") || path.contains("\n")) return null;
        if (path.startsWith("/login") || path.startsWith("/logout")) return null;
        return path;
    }
}
