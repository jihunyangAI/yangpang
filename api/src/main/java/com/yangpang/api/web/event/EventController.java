package com.yangpang.api.web.event;

import com.yangpang.api.security.MemberPrincipal;
import com.yangpang.domain.event.AttendanceService;
import com.yangpang.domain.event.InviteService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/** 출석체크 · 친구초대 (기획서 §18) */
@Controller
@RequiredArgsConstructor
public class EventController {

    /** 달력 한 칸 (앞쪽 빈칸은 date가 null) */
    public record Cell(LocalDate date, Long reward, boolean today, boolean future) {
    }

    private final AttendanceService attendanceService;
    private final InviteService inviteService;

    @GetMapping("/attendance")
    public String attendance(@AuthenticationPrincipal MemberPrincipal me, Model model) {
        LocalDate today = attendanceService.today();
        var month = attendanceService.month(me == null ? null : me.id(), YearMonth.from(today));
        List<Cell> cells = new ArrayList<>();
        int lead = month.month().atDay(1).getDayOfWeek().getValue() % 7; // 일요일 시작
        for (int i = 0; i < lead; i++) cells.add(new Cell(null, null, false, false));
        for (int d = 1; d <= month.month().lengthOfMonth(); d++) {
            LocalDate date = month.month().atDay(d);
            cells.add(new Cell(date, month.days().get(date), date.equals(today), date.isAfter(today)));
        }
        model.addAttribute("m", month);
        model.addAttribute("cells", cells);
        model.addAttribute("streakDays", AttendanceService.STREAK_DAYS);
        model.addAttribute("pageTitle", "출석체크");
        return "event/attendance";
    }

    @PostMapping("/attendance")
    public String checkIn(@AuthenticationPrincipal MemberPrincipal me, RedirectAttributes redirect) {
        var r = attendanceService.checkIn(me.id());
        redirect.addFlashAttribute("flashMessage", String.format("출석 완료! %,d원 적립", r.reward())
                + (r.bonus() ? " (" + r.streak() + "일 연속 보너스 포함)" : " · " + r.streak() + "일 연속"));
        return "redirect:/attendance";
    }

    @GetMapping("/my/invite")
    public String invite(@AuthenticationPrincipal MemberPrincipal me, HttpServletRequest request, Model model) {
        String code = inviteService.codeFor(me.id());
        model.addAttribute("code", code);
        model.addAttribute("link", com.yangpang.api.web.partners.PartnersController.baseUrl(request) + "/signup?invite=" + code);
        model.addAttribute("invited", inviteService.invited(me.id()));
        return "my/invite";
    }
}
