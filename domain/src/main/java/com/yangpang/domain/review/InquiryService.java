package com.yangpang.domain.review;

import com.yangpang.domain.catalog.ProductRepository;
import com.yangpang.domain.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/** 상품문의 (기획서 5.11). 답변은 그 상품을 파는 판매자(셀러센터) 또는 운영자 */
@Service
@RequiredArgsConstructor
@Transactional
public class InquiryService {

    private final ProductInquiryRepository inquiryRepository;
    private final ProductRepository productRepository;
    private final Clock clock;

    public ProductInquiry ask(Long memberId, Long productId, ProductInquiry.Type type, String content, boolean secret) {
        if (!productRepository.existsById(productId)) throw new BusinessException("상품이 없어요");
        String c = content == null ? "" : content.trim();
        if (c.length() < 5) throw new BusinessException("문의 내용을 5자 이상 써 주세요");
        if (c.length() > 1000) throw new BusinessException("문의는 1000자까지 쓸 수 있어요");
        return inquiryRepository.save(ProductInquiry.create(productId, memberId, type == null ? ProductInquiry.Type.PRODUCT : type,
                c, secret, clock.instant()));
    }

    /** 운영자 답변 */
    public void answer(Long inquiryId, String text) {
        inquiryRepository.findById(inquiryId).orElseThrow(() -> new BusinessException("문의가 없어요"))
                .answer(clean(text), "ADMIN", null, clock.instant());
    }

    /** 판매자 답변: 그 상품에 판매 오퍼가 있는 판매자만. 이미 답변된 문의는 고칠 수 없음 */
    public void answerAsSeller(Long sellerId, Long inquiryId, String text) {
        ProductInquiry q = inquiryRepository.findById(inquiryId).orElseThrow(() -> new BusinessException("문의가 없어요"));
        if (!inquiryRepository.sellerSells(sellerId, q.getProductId())) throw new BusinessException("내가 파는 상품의 문의가 아니에요");
        if (q.isAnswered()) throw new BusinessException("이미 답변한 문의예요");
        q.answer(clean(text), "SELLER", sellerId, clock.instant());
    }

    private static String clean(String text) {
        if (text == null || text.isBlank()) throw new BusinessException("답변을 입력해 주세요");
        String t = text.trim();
        return t.length() > 2000 ? t.substring(0, 2000) : t;
    }

    public void delete(Long memberId, Long inquiryId) {
        ProductInquiry q = inquiryRepository.findById(inquiryId).filter(x -> x.getMemberId().equals(memberId))
                .orElseThrow(() -> new BusinessException("내 문의가 아니에요"));
        if (q.isAnswered()) throw new BusinessException("답변이 달린 문의는 지울 수 없어요");
        inquiryRepository.delete(q);
    }
}
