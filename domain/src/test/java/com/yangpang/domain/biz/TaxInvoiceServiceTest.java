package com.yangpang.domain.biz;

import com.yangpang.domain.common.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaxInvoiceServiceTest {

    @Test
    void 사업자번호_10자리는_하이픈을_넣어_받는다() {
        var info = TaxInvoiceService.validate(new TaxInvoiceService.BusinessInfo("1234567890", " 가짜상사 ", "김대표", "a@b.co"));
        assertThat(info.businessNo()).isEqualTo("123-45-67890");
        assertThat(info.companyName()).isEqualTo("가짜상사");
    }

    @Test
    void 형식이_틀리면_거절() {
        assertThatThrownBy(() -> TaxInvoiceService.validate(new TaxInvoiceService.BusinessInfo("12-345", "a", "b", "a@b.co")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> TaxInvoiceService.validate(new TaxInvoiceService.BusinessInfo("123-45-67890", "a", "b", "not-mail")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> TaxInvoiceService.validate(new TaxInvoiceService.BusinessInfo("123-45-67890", " ", "b", "a@b.co")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 공급가액과_부가세는_합계에서_역산() {
        var t = TaxInvoice.request(1L, 1L, new TaxInvoiceService.BusinessInfo("123-45-67890", "a", "b", "a@b.co"), 11_000, Instant.EPOCH);
        assertThat(t.supplyAmount()).isEqualTo(10_000);
        assertThat(t.vat()).isEqualTo(1_000);
    }
}
