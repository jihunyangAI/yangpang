package com.yangpang.seed.member;

import com.yangpang.seed.SeedContext;
import com.yangpang.seed.Seeder;
import com.yangpang.seed.data.PeopleDictionary;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 고정 테스트 계정 2개 + 가짜 회원 + 배송지.
 * - 이메일은 예약 도메인 example.com만 사용
 * - 비밀번호는 모두 seed.default-password (해시는 한 번만 계산해 재사용)
 */
@Component
@Order(50)
@RequiredArgsConstructor
public class MemberSeeder implements Seeder {

    public static final long NORMAL_TEST_MEMBER_ID = 1L;
    public static final long ADMIN_TEST_MEMBER_ID = 2L;
    public static final long PLUS_TEST_MEMBER_ID = 3L;
    public static final long BIZ_TEST_MEMBER_ID = 4L;
    public static final long SELLER_TEST_MEMBER_ID = 5L;

    /** 고정 테스트 계정 (기획서 7.3): id, 이메일, 이름, 역할, 등급. 판매자 계정은 판매자 생성기가 추가 */
    private record Fixed(long id, String email, String name, String role, String grade) {
    }

    private static final List<Fixed> FIXED = List.of(
            new Fixed(1, "normal@example.com", "김테스트", "USER", "NORMAL"),
            new Fixed(2, "admin@example.com", "양팡운영자", "ADMIN", "NORMAL"),
            new Fixed(3, "plus@example.com", "박플러스", "USER", "PLUS"),
            new Fixed(4, "biz@example.com", "이비즈", "USER", "BIZ"),
            new Fixed(5, "seller@example.com", "최판매", "SELLER", "NORMAL"));

    private final JdbcTemplate jdbc;

    @Override
    public String name() {
        return "회원/배송지";
    }

    @Override
    public void seed(SeedContext ctx) {
        String hash = PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(ctx.props.defaultPassword());
        Instant now = Instant.now();

        List<Object[]> members = new ArrayList<>();
        List<Object[]> addresses = new ArrayList<>();
        long addressId = 0;

        int total = ctx.scale.members + FIXED.size();
        for (long id = 1; id <= total; id++) {
            String email;
            String name;
            String role = "USER";
            String grade = "NORMAL";
            if (id <= FIXED.size()) {
                Fixed f = FIXED.get((int) id - 1);
                email = f.email();
                name = f.name();
                role = f.role();
                grade = f.grade();
            } else {
                email = String.format("user%04d@example.com", id - FIXED.size());
                name = FakePerson.name(ctx);
                // M3: 가짜 회원 25%는 양팡플러스, 5%는 비즈
                double r = ctx.random.nextDouble();
                grade = r < 0.25 ? "PLUS" : r < 0.30 ? "BIZ" : "NORMAL";
            }
            if ("PLUS".equals(grade)) ctx.plusMemberIds.add(id);
            String phone = FakePerson.phone((int) id);
            LocalDate birth = LocalDate.of(ctx.between(1965, 2005), ctx.between(1, 12), ctx.between(1, 28));
            Instant joinedAt = now.minus(Duration.ofDays(ctx.between(30, 1500)));

            members.add(new Object[]{id, email, hash, name, phone, Date.valueOf(birth), grade, role, "ACTIVE",
                    ctx.chance(0.4), Timestamp.from(joinedAt), Timestamp.from(joinedAt)});
            if (!"ADMIN".equals(role)) {
                ctx.memberIds.add(id);
            }

            int addressCount = id == NORMAL_TEST_MEMBER_ID ? 2 : ctx.between(1, 3);
            for (int n = 0; n < addressCount; n++) {
                addressId++;
                PeopleDictionary.District d = ctx.pick(PeopleDictionary.DISTRICTS);
                String zipcode = d.zipPrefix() + String.format("%03d", ctx.between(0, 999));
                String address1 = d.sido() + " " + d.sigungu() + " "
                        + ctx.pick(PeopleDictionary.ROAD_WORDS) + ctx.pick(PeopleDictionary.ROAD_SUFFIXES) + " " + ctx.between(1, 299);
                String address2 = ctx.pick(PeopleDictionary.ROAD_WORDS) + ctx.pick(PeopleDictionary.BUILDING_SUFFIXES)
                        + " " + ctx.between(101, 120) + "동 " + ctx.between(1, 25) + ctx.between(0, 1) + ctx.between(1, 4) + "호";
                String recipient = n == 0 ? name : FakePerson.name(ctx);
                String recipientPhone = n == 0 ? phone : FakePerson.phone(5000 + (int) addressId);

                String entranceType;
                String entranceNote = null;
                double r = ctx.random.nextDouble();
                if (r < 0.4) {
                    entranceType = "FREE";
                } else if (r < 0.8) {
                    entranceType = "PASSWORD";
                    entranceNote = "#" + ctx.between(1000, 9999) + "*";
                } else {
                    entranceType = "CALL";
                }
                String requestNote = ctx.pick(PeopleDictionary.REQUEST_NOTES);
                String label = n == 0 ? "집" : (n == 1 ? "회사" : "부모님댁");
                boolean isDefault = n == 0;

                addresses.add(new Object[]{addressId, id, label, recipient, recipientPhone, zipcode, address1, address2,
                        d.region(), entranceType, entranceNote, requestNote, isDefault, Timestamp.from(joinedAt)});
                if (isDefault) {
                    ctx.defaultAddresses.put(id, new SeedContext.AddressRef(recipient, recipientPhone, zipcode, address1,
                            address2, d.region(), entranceType, entranceNote, requestNote));
                }
            }
        }

        jdbc.batchUpdate("""
                insert into member(id, email, password_hash, name, phone, birth_date, grade, role, status,
                                   marketing_agreed, created_at, updated_at)
                values (?,?,?,?,?,?,?,?,?,?,?,?)""", members);
        jdbc.batchUpdate("""
                insert into address(id, member_id, label, recipient, phone, zipcode, address1, address2, region,
                                    entrance_type, entrance_note, request_note, is_default, created_at)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", addresses);
    }
}
