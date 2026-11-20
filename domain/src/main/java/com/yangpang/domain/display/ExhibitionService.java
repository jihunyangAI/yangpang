package com.yangpang.domain.display;

import com.yangpang.domain.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 기획전 등록·상품 구성 (기획서 5.6, 운영자 5.16) */
@Service
@RequiredArgsConstructor
@Transactional
public class ExhibitionService {

    private final ExhibitionRepository repository;
    private final JdbcTemplate jdbc;

    public Exhibition create(String slug, String title, String subtitle, Banner.Theme theme, Long couponId,
                             Instant startsAt, Instant endsAt, int sortOrder) {
        String s = slug == null ? "" : slug.trim().toLowerCase();
        if (!s.matches("^[a-z0-9][a-z0-9-]{1,58}$")) throw new BusinessException("주소 이름은 영문 소문자·숫자·하이픈 2~60자예요 (예: winter-camping)");
        if (repository.findBySlug(s).isPresent()) throw new BusinessException("이미 쓰는 주소 이름이에요");
        if (title == null || title.isBlank()) throw new BusinessException("제목을 입력해 주세요");
        if (!endsAt.isAfter(startsAt)) throw new BusinessException("종료일이 시작일보다 뒤여야 해요");
        return repository.save(Exhibition.create(s, title.trim(), blank(subtitle), theme, couponId, startsAt, endsAt, sortOrder));
    }

    /** 상품 번호 여러 개를 한 구역(section)에 추가. 없는 번호는 건너뛰고, 추가한 수를 돌려준다 */
    public int addProducts(Long exhibitionId, String section, List<Long> productIds) {
        if (section == null || section.isBlank()) throw new BusinessException("구역 이름을 입력해 주세요 (예: 텐트·타프)");
        repository.findById(exhibitionId).orElseThrow(() -> new BusinessException("기획전이 없어요"));
        Integer max = jdbc.queryForObject("select coalesce(max(sort_order), 0) from exhibition_product where exhibition_id = ?",
                Integer.class, exhibitionId);
        int sort = max == null ? 0 : max;
        int added = 0;
        for (Long pid : productIds) {
            added += jdbc.update("""
                    insert into exhibition_product(exhibition_id, product_id, section, sort_order)
                    select ?, id, ?, ? from product where id = ? on conflict do nothing""",
                    exhibitionId, section.trim(), ++sort, pid);
        }
        return added;
    }

    public void removeProduct(Long exhibitionId, Long productId) {
        jdbc.update("delete from exhibition_product where exhibition_id = ? and product_id = ?", exhibitionId, productId);
    }

    public void toggle(Long id) {
        repository.findById(id).orElseThrow(() -> new BusinessException("기획전이 없어요")).toggle();
    }

    /** 구역 이름 → 상품 번호 (구역은 처음 추가된 순서) */
    @Transactional(readOnly = true)
    public Map<String, List<Long>> sections(Long exhibitionId) {
        Map<String, List<Long>> result = new LinkedHashMap<>();
        jdbc.query("""
                select section, product_id from exhibition_product where exhibition_id = ?
                order by min(sort_order) over (partition by section), sort_order""",
                rs -> {
                    result.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getLong(2));
                }, exhibitionId);
        return result;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
