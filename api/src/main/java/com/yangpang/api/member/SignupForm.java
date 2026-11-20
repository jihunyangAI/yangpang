package com.yangpang.api.member;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** 회원가입 입력값 (기획서 M-01) */
@Getter
@Setter
public class SignupForm {

    @NotBlank(message = "이메일을 입력해 주세요")
    @Email(message = "이메일 형식이 올바르지 않습니다")
    @Size(max = 100)
    private String email;

    @NotBlank(message = "비밀번호를 입력해 주세요")
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[^A-Za-z\\d]).{8,20}$",
            message = "영문, 숫자, 특수문자를 모두 포함해 8~20자로 입력해 주세요")
    private String password;

    @NotBlank(message = "비밀번호를 한 번 더 입력해 주세요")
    private String passwordConfirm;

    @NotBlank(message = "이름을 입력해 주세요")
    @Size(min = 2, max = 20, message = "이름은 2~20자로 입력해 주세요")
    private String name;

    @NotBlank(message = "휴대폰 번호를 입력해 주세요")
    @Pattern(regexp = "^010-?\\d{4}-?\\d{4}$", message = "010으로 시작하는 휴대폰 번호를 입력해 주세요")
    private String phone;

    private String verificationCode;

    @AssertTrue(message = "[필수] 이용약관에 동의해 주세요")
    private boolean agreeTerms;

    @AssertTrue(message = "[필수] 개인정보 수집·이용에 동의해 주세요")
    private boolean agreePrivacy;

    @AssertTrue(message = "만 14세 이상만 가입할 수 있습니다")
    private boolean agreeAge;

    private boolean agreeMarketing;

    /** 친구초대 코드 (선택) */
    @jakarta.validation.constraints.Size(max = 12)
    private String inviteCode;

    /** 010-1234-5678 형태로 맞춘다 */
    public String normalizedPhone() {
        String digits = phone == null ? "" : phone.replaceAll("\\D", "");
        if (digits.length() != 11) return phone;
        return digits.substring(0, 3) + "-" + digits.substring(3, 7) + "-" + digits.substring(7);
    }

    public String normalizedEmail() {
        return email == null ? null : email.trim().toLowerCase();
    }
}
