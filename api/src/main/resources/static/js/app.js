// Yangpang 공통 스크립트 (바닐라 JS). 서버와 주고받는 화면 갱신은 htmx가 담당한다.
(function () {
  "use strict";

  // ---- 메인 배너 캐러셀: 5초마다 다음 슬라이드, 점 표시 ----
  function initCarousel(root) {
    var track = root.querySelector("[data-carousel-track]");
    var dotsBox = root.querySelector("[data-carousel-dots]");
    if (!track || root.dataset.ready) return;
    root.dataset.ready = "1";
    var slides = track.children;
    var dots = [];

    for (var i = 0; i < slides.length; i++) {
      var dot = document.createElement("button");
      dot.type = "button";
      dot.setAttribute("aria-label", (i + 1) + "번째 배너");
      dot.addEventListener("click", go.bind(null, i));
      dotsBox && dotsBox.appendChild(dot);
      dots.push(dot);
    }

    function current() { return Math.round(track.scrollLeft / track.clientWidth); }
    function go(index) { track.scrollTo({ left: index * track.clientWidth, behavior: "smooth" }); }
    function paint() {
      var c = current();
      dots.forEach(function (d, i) {
        d.className = "h-1.5 rounded-full transition-all " + (i === c ? "w-4 bg-white" : "w-1.5 bg-white/50");
      });
    }

    track.addEventListener("scroll", function () { window.requestAnimationFrame(paint); });
    paint();
    var timer = setInterval(function () { go((current() + 1) % slides.length); }, 5000);
    root.addEventListener("pointerenter", function () { clearInterval(timer); });
  }

  function init(scope) {
    scope.querySelectorAll("[data-carousel]").forEach(initCarousel);
  }

  document.addEventListener("DOMContentLoaded", function () {
    init(document);
    // 서버가 처음부터 넣어 둔 토스트(예: 가입 환영)도 잠시 뒤 지운다
    var box = document.getElementById("toast");
    if (box && box.children.length) scheduleToastHide();
  });

  // ---- htmx 요청마다 CSRF 토큰 헤더 추가 ----
  document.addEventListener("htmx:configRequest", function (e) {
    var token = document.querySelector('meta[name="csrf-token"]');
    var header = document.querySelector('meta[name="csrf-header"]');
    if (token && header && e.detail.verb !== "get") {
      e.detail.headers[header.content] = token.content;
    }
  });

  // ---- 약관 "모두 동의" ----
  document.addEventListener("change", function (e) {
    var group = e.target.closest("[data-agree-all-group]");
    if (!group) return;
    var all = group.querySelector("[data-agree-all]");
    var items = group.querySelectorAll("input[type=checkbox]:not([data-agree-all])");
    if (e.target === all) {
      items.forEach(function (c) { c.checked = all.checked; });
    } else {
      all.checked = Array.prototype.every.call(items, function (c) { return c.checked; });
    }
  });
  document.addEventListener("htmx:load", function (e) { init(e.target); });

  // ---- 검색 드롭다운: 바깥을 누르거나 Esc를 누르면 닫기 ----
  function closeSuggest() {
    ["search-suggest", "search-suggest-m"].forEach(function (id) {
      var box = document.getElementById(id);
      if (box) box.innerHTML = "";
    });
  }
  document.addEventListener("keydown", function (e) {
    if (e.key === "Escape") closeSuggest();
  });

  // ---- 클릭 위임: 갤러리 썸네일, 수량 버튼, 링크 복사 ----
  document.addEventListener("click", function (e) {
    if (!e.target.closest("[data-search-form]")) closeSuggest();

    var thumb = e.target.closest("[data-gallery-thumb]");
    if (thumb) {
      var gallery = thumb.closest("[data-gallery]");
      gallery.querySelector("[data-gallery-main]").src = thumb.dataset.galleryThumb;
      gallery.querySelectorAll("[data-gallery-thumb]").forEach(function (t) {
        t.classList.toggle("border-brand-600", t === thumb);
        t.classList.toggle("border-transparent", t !== thumb);
      });
      return;
    }

    var step = e.target.closest("[data-qty-minus],[data-qty-plus]");
    if (step) {
      var input = step.closest("[data-qty]").querySelector("input");
      var max = parseInt(input.max || "99", 10);
      var next = (parseInt(input.value, 10) || 1) + (step.hasAttribute("data-qty-plus") ? 1 : -1);
      input.value = Math.min(Math.max(next, 1), max);
      input.dispatchEvent(new Event("change", { bubbles: true }));
      return;
    }

    // "모두 사용" 같은 버튼: 대상 입력칸에 값을 넣고 change 이벤트 발생 (htmx 재계산)
    var fill = e.target.closest("[data-fill-target]");
    if (fill) {
      var target = document.querySelector(fill.dataset.fillTarget);
      if (target) {
        target.value = fill.dataset.fillValue;
        target.dispatchEvent(new Event("change", { bubbles: true }));
      }
      return;
    }

    var copyText = e.target.closest("[data-copy-text]");
    if (copyText) {
      navigator.clipboard && navigator.clipboard.writeText(copyText.dataset.copyText);
      showToast("링크를 복사했어요");
      return;
    }

    if (e.target.closest("[data-copy-url]")) {
      navigator.clipboard && navigator.clipboard.writeText(location.href);
      showToast("링크를 복사했어요");
    }
  });

  // ---- 토스트 ----
  var toastTimer;
  function showToast(message) {
    var box = document.getElementById("toast");
    if (!box) return;
    box.innerHTML = "";
    var el = document.createElement("div");
    el.className = "pointer-events-auto rounded-full bg-gray-900/90 px-5 py-3 text-sm text-white shadow-lg";
    el.textContent = message;
    box.appendChild(el);
    scheduleToastHide();
  }

  function scheduleToastHide() {
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () {
      var box = document.getElementById("toast");
      if (box) box.innerHTML = "";
    }, 2500);
  }

  // 서버가 #toast에 조각을 넣으면 잠시 뒤 지운다
  document.addEventListener("htmx:afterSwap", function (e) {
    if (e.detail.target && e.detail.target.id === "toast") scheduleToastHide();
  });

  // 서버가 HX-Trigger: {"cartUpdated": {"count": n}} 를 보내면 헤더 배지 갱신
  document.addEventListener("cartUpdated", function (e) {
    var count = e.detail && typeof e.detail.count === "number" ? e.detail.count : 0;
    document.querySelectorAll("[data-cart-count]").forEach(function (badge) {
      badge.textContent = count;
      badge.classList.toggle("hidden", count === 0);
    });
  });

  // 남은 시간 카운트다운: data-countdown="남은 밀리초" (화면을 그린 시점 기준)
  function startCountdowns(root) {
    (root || document).querySelectorAll("[data-countdown]").forEach(function (el) {
      if (el.dataset.countdownStarted) return;
      el.dataset.countdownStarted = "1";
      var end = Date.now() + Number(el.dataset.countdown || 0);
      function tick() {
        var left = Math.max(0, Math.floor((end - Date.now()) / 1000));
        var d = Math.floor(left / 86400), h = Math.floor(left % 86400 / 3600), m = Math.floor(left % 3600 / 60), s = left % 60;
        el.textContent = (d > 0 ? d + "일 " : "") + String(h).padStart(2, "0") + ":" + String(m).padStart(2, "0") + ":" + String(s).padStart(2, "0");
        if (left > 0) setTimeout(tick, 1000);
      }
      tick();
    });
  }
  document.addEventListener("DOMContentLoaded", function () { startCountdowns(); });
  document.addEventListener("htmx:afterSwap", function (e) { startCountdowns(e.detail.target); });

  window.yangpang = { showToast: showToast };
})();

// ---- 음성 검색 (브라우저 내장 음성 인식, 한국어). 지원하는 브라우저에서만 마이크 버튼이 보인다. 음성은 서버로 보내지 않는다 ----
(function () {
  var Recognition = window.SpeechRecognition || window.webkitSpeechRecognition;
  if (!Recognition) return;
  document.querySelectorAll("[data-voice-search]").forEach(function (btn) {
    btn.hidden = false;
    btn.addEventListener("click", function () {
      var form = btn.closest("form");
      var input = form && form.querySelector('input[name="q"]');
      if (!input) return;
      var rec = new Recognition();
      rec.lang = "ko-KR";
      rec.interimResults = true;
      rec.maxAlternatives = 1;
      var before = input.placeholder;
      input.placeholder = "듣고 있어요... 찾을 상품을 말해 주세요";
      btn.classList.add("text-brand-600", "animate-pulse");
      rec.onresult = function (e) {
        var text = "";
        for (var i = 0; i < e.results.length; i++) text += e.results[i][0].transcript;
        input.value = text.trim();
        if (e.results[e.results.length - 1].isFinal && input.value) form.requestSubmit ? form.requestSubmit() : form.submit();
      };
      rec.onerror = function (e) {
        var box = document.getElementById("toast");
        if (box) {
          box.innerHTML = "";
          var el = document.createElement("div");
          el.className = "pointer-events-auto rounded-full bg-gray-900/90 px-5 py-3 text-sm text-white shadow-lg";
          el.textContent = e.error === "not-allowed" ? "마이크 사용을 허용해야 음성으로 검색할 수 있어요" : "음성을 알아듣지 못했어요. 다시 말해 주세요";
          box.appendChild(el);
          setTimeout(function () { box.innerHTML = ""; }, 3000);
        }
      };
      rec.onend = function () {
        input.placeholder = before;
        btn.classList.remove("text-brand-600", "animate-pulse");
      };
      rec.start();
    });
  });
})();
