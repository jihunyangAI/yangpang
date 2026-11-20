package com.yangpang.domain.catalog.search;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

/** 검색어 기록 · 최근 검색어 · 인기 검색어 (기획서 S-03) */
@Service
@RequiredArgsConstructor
@Transactional
public class SearchLogService {

    /** 인기 검색어 한 줄. rising=최근 하루 검색이 그 전 6일 하루 평균의 2배 이상 */
    public record Popular(String keyword, long count, boolean rising) {
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;

    /** 검색 화면(읽기 전용 트랜잭션) 안에서 불려도 기록되도록 별도 트랜잭션 */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void log(Long memberId, String keyword, int resultCount) {
        String k = normalize(keyword);
        if (k == null) return;
        jdbc.update("insert into search_log(member_id, keyword, result_count, created_at) values (?, ?, ?, ?)",
                memberId, k, resultCount, Timestamp.from(clock.instant()));
    }

    @Transactional(readOnly = true)
    public List<String> recent(Long memberId, int limit) {
        return jdbc.queryForList("""
                select keyword from search_log
                where member_id = ? and not hidden
                group by keyword order by max(created_at) desc limit ?""", String.class, memberId, limit);
    }

    public void hide(Long memberId, String keyword) {
        jdbc.update("update search_log set hidden = true where member_id = ? and keyword = ?", memberId, normalize(keyword));
    }

    public void hideAll(Long memberId) {
        jdbc.update("update search_log set hidden = true where member_id = ?", memberId);
    }

    /** 최근 7일 인기 검색어 (결과가 있었던 검색만) */
    @Transactional(readOnly = true)
    public List<Popular> popular(int limit) {
        var now = clock.instant();
        return jdbc.query("""
                select keyword, count(*) total,
                       count(*) filter (where created_at >= ?) last_day,
                       count(*) filter (where created_at < ?) earlier
                from search_log
                where created_at >= ? and result_count > 0
                group by keyword order by total desc, keyword limit ?""",
                (rs, i) -> {
                    long lastDay = rs.getLong("last_day");
                    double earlierPerDay = rs.getLong("earlier") / 6.0;
                    return new Popular(rs.getString("keyword"), rs.getLong("total"),
                            lastDay >= 3 && lastDay >= earlierPerDay * 2);
                },
                Timestamp.from(now.minus(Duration.ofDays(1))), Timestamp.from(now.minus(Duration.ofDays(1))),
                Timestamp.from(now.minus(Duration.ofDays(7))), limit);
    }

    static String normalize(String keyword) {
        if (keyword == null) return null;
        String k = keyword.trim().replaceAll("\\s+", " ");
        if (k.isEmpty()) return null;
        return k.length() > 100 ? k.substring(0, 100) : k;
    }
}
