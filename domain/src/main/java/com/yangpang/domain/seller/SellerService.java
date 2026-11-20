package com.yangpang.domain.seller;

import com.yangpang.domain.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** 판매자 입점 신청·승인과 판매자 계정 연결 (기획서 5.15) */
@Service
@RequiredArgsConstructor
@Transactional
public class SellerService {

    private final SellerRepository sellerRepository;
    private final JdbcTemplate jdbc;

    /** 입점 신청: 판매자(심사중) 생성 + 신청한 회원을 담당자로 연결 */
    public Seller apply(Long memberId, String name, String businessNo, String ceoName, String address, String csPhone,
                        long shippingFee, Long freeThreshold) {
        if (sellerOf(memberId).isPresent()) throw new BusinessException("이미 판매자 계정이 있거나 신청 중이에요");
        if (!businessNo.matches("^\\d{3}-\\d{2}-\\d{5}$")) throw new BusinessException("사업자등록번호 형식은 000-00-00000 이에요 (가짜 번호)");
        if (shippingFee < 0 || shippingFee > 20_000) throw new BusinessException("배송비는 0~20,000원이에요");
        Seller s = sellerRepository.save(Seller.apply(name.trim(), businessNo, ceoName.trim(), address.trim(), csPhone.trim(),
                shippingFee, freeThreshold));
        jdbc.update("insert into seller_member(seller_id, member_id) values (?, ?)", s.getId(), memberId);
        return s;
    }

    /** 운영자 승인 → 담당 회원에게 판매자 역할 부여 */
    public void approve(Long sellerId) {
        Seller s = sellerRepository.findById(sellerId).orElseThrow(() -> new BusinessException("판매자가 없어요"));
        s.approve();
        jdbc.update("""
                update member set role = 'SELLER', updated_at = now()
                where id in (select member_id from seller_member where seller_id = ?) and role = 'USER'""", sellerId);
    }

    public void reject(Long sellerId) {
        sellerRepository.findById(sellerId).orElseThrow(() -> new BusinessException("판매자가 없어요")).reject();
    }

    @Transactional(readOnly = true)
    public Optional<Seller> sellerOf(Long memberId) {
        List<Long> ids = jdbc.queryForList("select seller_id from seller_member where member_id = ?", Long.class, memberId);
        return ids.isEmpty() ? Optional.empty() : sellerRepository.findById(ids.getFirst());
    }

    /** 승인된 판매자의 id (셀러센터 접근용). 없거나 심사중이면 예외 */
    @Transactional(readOnly = true)
    public Seller activeSellerOf(Long memberId) {
        Seller s = sellerOf(memberId).orElseThrow(() -> new BusinessException("판매자 계정이 아니에요"));
        if (!s.isOperating()) throw new BusinessException("입점 심사 중이에요");
        return s;
    }

    /** 판매 정지 중이면 판매·광고 변경을 막는다 */
    public static void requireSelling(Seller s) {
        if (s.isSuspended()) throw new BusinessException("판매 정지 중이라 판매 정보를 바꿀 수 없어요. 셀러센터 홈에서 제재 사유를 확인해 주세요");
    }
}
