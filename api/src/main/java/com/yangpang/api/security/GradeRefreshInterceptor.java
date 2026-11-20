package com.yangpang.api.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;

/**
 * 로그인 정보의 회원 등급·역할을 DB와 맞춘다 (판매자 승인도 로그인 중에 반영).
 * 양팡플러스 가입·종료(시뮬레이터가 기간 끝에 처리)는 로그인 중에도 일어나므로,
 * 요청마다 등급을 확인해 바뀌었으면 세션의 로그인 정보를 새로 고친다 → me.isPlus()가 항상 정확.
 */
@Component
@RequiredArgsConstructor
public class GradeRefreshInterceptor implements HandlerInterceptor {

    private final JdbcTemplate jdbc;
    private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof MemberPrincipal me)) return true;
        List<String[]> rows = jdbc.query("select grade, role from member where id = ?",
                (rs, i) -> new String[]{rs.getString(1), rs.getString(2)}, me.id());
        if (rows.isEmpty()) return true;
        String grade = rows.getFirst()[0], role = rows.getFirst()[1];
        if (grade.equals(me.grade()) && role.equals(me.role())) return true;

        MemberPrincipal updated = me.withGradeAndRole(grade, role);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(updated, null, updated.getAuthorities()));
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
        return true;
    }
}
