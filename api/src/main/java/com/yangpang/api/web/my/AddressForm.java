package com.yangpang.api.web.my;

import com.yangpang.domain.common.EntranceType;
import com.yangpang.domain.member.Address;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AddressForm {

    @Size(max = 30)
    private String label;

    @NotBlank(message = "받는 분 이름을 입력해 주세요")
    @Size(max = 50)
    private String recipient;

    @NotBlank(message = "연락처를 입력해 주세요")
    @Pattern(regexp = "^01\\d-?\\d{3,4}-?\\d{4}$", message = "휴대폰 번호 형식이 올바르지 않습니다")
    private String phone;

    @NotBlank(message = "우편번호를 입력해 주세요")
    @Pattern(regexp = "^\\d{5}$", message = "우편번호는 숫자 5자리입니다")
    private String zipcode;

    @NotBlank(message = "주소를 입력해 주세요")
    @Size(max = 200)
    private String address1;

    @Size(max = 200)
    private String address2;

    private EntranceType entranceType = EntranceType.FREE;

    @Size(max = 100)
    private String entranceNote;

    @Size(max = 100)
    private String requestNote = "문 앞에 놓아주세요";

    private boolean makeDefault;

    public Address.Fields toFields() {
        String digits = phone.replaceAll("\\D", "");
        String p = digits.length() == 11
                ? digits.substring(0, 3) + "-" + digits.substring(3, 7) + "-" + digits.substring(7)
                : phone;
        return new Address.Fields(blankToNull(label), recipient.trim(), p, zipcode, address1.trim(),
                blankToNull(address2), entranceType, blankToNull(entranceNote), blankToNull(requestNote));
    }

    public static AddressForm from(Address a) {
        AddressForm f = new AddressForm();
        f.label = a.getLabel();
        f.recipient = a.getRecipient();
        f.phone = a.getPhone();
        f.zipcode = a.getZipcode();
        f.address1 = a.getAddress1();
        f.address2 = a.getAddress2();
        f.entranceType = a.getEntranceType();
        f.entranceNote = a.getEntranceNote();
        f.requestNote = a.getRequestNote();
        f.makeDefault = a.isDefaultAddress();
        return f;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
