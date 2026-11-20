package com.yangpang.domain.member;

import com.yangpang.domain.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 배송지 관리 (기획서 MY-04). 기본 배송지는 회원당 하나 */
@Service
@RequiredArgsConstructor
@Transactional
public class AddressService {

    static final int MAX_ADDRESSES = 10;

    private final AddressRepository addressRepository;
    private final MemberRepository memberRepository;

    public Address add(Long memberId, Address.Fields fields, boolean makeDefault) {
        List<Address> existing = list(memberId);
        if (existing.size() >= MAX_ADDRESSES) {
            throw new BusinessException("배송지는 " + MAX_ADDRESSES + "개까지 등록할 수 있습니다");
        }
        boolean isDefault = makeDefault || existing.isEmpty(); // 첫 배송지는 자동으로 기본
        if (isDefault) existing.forEach(a -> a.markDefault(false));
        Member member = memberRepository.getReferenceById(memberId);
        return addressRepository.save(Address.create(member, fields, isDefault));
    }

    public void update(Long memberId, Long addressId, Address.Fields fields, boolean makeDefault) {
        Address address = get(memberId, addressId);
        address.apply(fields);
        if (makeDefault) setDefault(memberId, addressId);
    }

    public void setDefault(Long memberId, Long addressId) {
        get(memberId, addressId);
        list(memberId).forEach(a -> a.markDefault(a.getId().equals(addressId)));
    }

    /** 기본 배송지를 지우면 가장 오래된 배송지가 기본이 된다 */
    public void delete(Long memberId, Long addressId) {
        Address address = get(memberId, addressId);
        boolean wasDefault = address.isDefaultAddress();
        addressRepository.delete(address);
        addressRepository.flush();
        if (wasDefault) {
            list(memberId).stream().findFirst().ifPresent(a -> a.markDefault(true));
        }
    }

    @Transactional(readOnly = true)
    public List<Address> list(Long memberId) {
        return addressRepository.findByMemberIdOrderByDefaultAddressDescIdAsc(memberId);
    }

    @Transactional(readOnly = true)
    public Address get(Long memberId, Long addressId) {
        return addressRepository.findById(addressId)
                .filter(a -> a.getMember().getId().equals(memberId))
                .orElseThrow(() -> new BusinessException("배송지를 찾을 수 없습니다"));
    }
}
