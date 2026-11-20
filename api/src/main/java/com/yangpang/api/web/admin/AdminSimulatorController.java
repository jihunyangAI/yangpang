package com.yangpang.api.web.admin;

import com.yangpang.domain.order.OrderRepository;
import com.yangpang.domain.order.OrderStatus;
import com.yangpang.domain.sim.SimClockService;
import com.yangpang.simulator.DeliverySimulator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** 시뮬레이터 제어판 (기획서 7.4, 5.16): 시계 배속·건너뛰기, 자동 실행, 즉시 실행 */
@Controller
@RequestMapping("/admin/simulator")
@RequiredArgsConstructor
public class AdminSimulatorController {

    private final SimClockService simClock;
    private final DeliverySimulator simulator;
    private final OrderRepository orderRepository;

    @GetMapping
    public String page(Model model) {
        model.addAttribute("now", simClock.now());
        model.addAttribute("speed", simClock.speed());
        model.addAttribute("autoRun", simClock.autoRun());
        Map<OrderStatus, Long> counts = new LinkedHashMap<>();
        for (OrderStatus s : OrderStatus.values()) counts.put(s, orderRepository.countByStatus(s));
        model.addAttribute("counts", counts);
        return "admin/simulator";
    }

    @PostMapping("/speed")
    public String speed(@RequestParam double speed, RedirectAttributes redirect) {
        simClock.setSpeed(speed);
        redirect.addFlashAttribute("flashMessage", "배속을 " + (speed == Math.floor(speed) ? String.valueOf((long) speed) : speed) + "x로 바꿨어요");
        return "redirect:/admin/simulator";
    }

    @PostMapping("/jump")
    public String jump(@RequestParam long hours, RedirectAttributes redirect) {
        simClock.jump(Duration.ofHours(hours));
        DeliverySimulator.Result r = simulator.tick();
        redirect.addFlashAttribute("flashMessage", hours + "시간 건너뛰고 실행: 처리 " + r.total() + "건");
        return "redirect:/admin/simulator";
    }

    @PostMapping("/reset")
    public String reset(RedirectAttributes redirect) {
        simClock.reset();
        redirect.addFlashAttribute("flashMessage", "실제 시각·배속 1x로 되돌렸어요");
        return "redirect:/admin/simulator";
    }

    @PostMapping("/auto")
    public String auto(@RequestParam boolean on, RedirectAttributes redirect) {
        simClock.setAutoRun(on);
        redirect.addFlashAttribute("flashMessage", on ? "자동 실행을 켰어요 (5초마다)" : "자동 실행을 껐어요");
        return "redirect:/admin/simulator";
    }

    @PostMapping("/tick")
    public String tick(RedirectAttributes redirect) {
        DeliverySimulator.Result r = simulator.tick();
        redirect.addFlashAttribute("flashMessage", "실행 완료: 미결제취소 " + r.expired() + ", 입금 " + r.deposited()
                + ", 입금기한취소 " + r.depositExpired() + ", 배송진행 " + r.advanced() + ", 반품·교환 " + r.claims());
        return "redirect:/admin/simulator";
    }
}
