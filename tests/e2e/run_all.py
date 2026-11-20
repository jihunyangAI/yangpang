"""YangPang 화면·흐름 검사 전체 실행.

사용법 (프로젝트 루트에서):
    python tests/e2e/run_all.py            # 전체
    python tests/e2e/run_all.py m4 post    # 이름에 m4 또는 post가 들어간 것만

앱(http://localhost:8090)과 PostgreSQL(docker)이 떠 있어야 한다. 검사는 가짜 데이터를 바꾸므로(주문·회원 추가 등)
끝나면 `./gradlew :seed:bootRun`으로 가짜 데이터를 다시 만드는 것을 권장한다. 자세한 내용은 README.md.
"""
import os
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))

# 실행 순서: 화면 점검 → 단계별 검사 (뒤 단계일수록 앞 단계 데이터를 쓸 수 있다). 시계를 돌리는 검사는 맨 끝
SCRIPTS = [
    "smoke.py",
    "scenarios_1_4_5.py",
    "m3_exhibition.py", "m3_notification.py", "m3_scenario8_deal_concurrency.py",
    "m4_step1.py", "m4_step2.py", "m4_step3.py", "m4_step4.py", "m4_step5.py", "m4_step6.py", "m4_step7.py",
    ("m4_step8.py", "off"),
    "post_cs_account.py", "post_policy.py", "post_attendance_invite.py", "post_mock_email.py", "post_install_adult_voice.py",
    # 시뮬레이션 시계를 11일 앞으로 돌렸다 되돌린다 → 그사이 만든 정산 등이 남으므로 맨 마지막에
    "m3_carryover.py",
]


def main(filters):
    env = dict(os.environ, PYTHONIOENCODING="utf-8", PYTHONUTF8="1")
    total_ok = total_fail = 0
    failed = []
    for entry in SCRIPTS:
        name, args = (entry, []) if isinstance(entry, str) else (entry[0], list(entry[1:]))
        if filters and not any(f in name for f in filters):
            continue
        started = time.time()
        r = subprocess.run([sys.executable, os.path.join(HERE, name), *args], cwd=HERE, env=env,
                           capture_output=True, text=True, encoding="utf-8", errors="replace")
        out = r.stdout + r.stderr
        ok = sum(1 for line in out.splitlines() if line.startswith("OK"))
        fails = [line for line in out.splitlines() if line.startswith("FAIL") or "문제 " in line and not line.strip().endswith("문제 0")]
        crashed = r.returncode != 0
        total_ok += ok
        total_fail += len(fails) + (1 if crashed else 0)
        status = "실패" if fails or crashed else "통과"
        print(f"[{status}] {name} {' '.join(args)} · OK {ok}{' · FAIL ' + str(len(fails)) if fails else ''}"
              f"{' · 오류로 멈춤' if crashed else ''} · {time.time() - started:.0f}초")
        for line in fails:
            print("      " + line)
        if crashed:
            print("      " + "\n      ".join(out.strip().splitlines()[-4:]))
        if fails or crashed:
            failed.append(name)
    print(f"\n합계: OK {total_ok} · 실패 {total_fail}" + (f" · 실패한 검사: {', '.join(failed)}" if failed else ""))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
