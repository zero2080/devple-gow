package devple.gow.engine.nav;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 과제 진행률 = 오브젝트 애니 재생 위치 (원작 0x215928 / 0x2118E8 / 0x215DF0, 38 문서 §3.1). */
class TaskProgressTest {

	private static List<TaskProgress.Event> run(TaskProgress t, boolean engaged, float seconds) {
		List<TaskProgress.Event> out = new ArrayList<>();
		for (float s = 0; s < seconds; s += 1 / 60f) {
			TaskProgress.Event e = t.update(engaged, 1 / 60f);
			if (e != null) {
				out.add(e);
			}
		}
		return out;
	}

	@Test
	void engagedPlaysAtSpeedOneUntilHundred() {
		TaskProgress t = new TaskProgress(206, 0.9f); // 쌍발차기 문 ANM_twinkickdoor "Default" 0.9 초
		List<TaskProgress.Event> ev = run(t, true, 0.5f);
		assertFalse(t.done);
		assertEquals(206, ev.getFirst().taskId());
		assertEquals(100, ev.getFirst().speed100(), "인자 2 = 재생 속도 1.0 × 100");
		int pct = ev.getLast().percent();
		assertTrue(pct >= 50 && pct <= 57, "0.5 / 0.9 초 ≈ 55 %: " + pct);
		ev = run(t, true, 0.5f);
		assertTrue(t.done);
		assertEquals(100, ev.getLast().percent());
		assertEquals(1, ev.stream().filter(e -> e.percent() == 100).count(), "100 은 완료 때 한 번 (그 전엔 99)");
		assertNull(t.update(true, 1 / 60f), "끝난 뒤엔 보내지 않음");
	}

	@Test
	void releasedRewindsAtMinusFour() {
		TaskProgress t = new TaskProgress(111, 3.667f); // PAND00C 엘리베이터 레버 3.667 초
		run(t, true, 2f);
		float at = t.time;
		List<TaskProgress.Event> ev = run(t, false, 0.25f);
		assertEquals(-400, ev.getFirst().speed100(), "속도가 바뀌면 % 가 같아도 보냄");
		assertEquals(at - 1f, t.time, 0.05f, "−4 × 0.25 초");
		run(t, false, 1f);
		assertEquals(0, t.time);
		assertEquals(0, t.speed, "0 에 닿으면 멈춤");
	}

	@Test
	void zeroLengthAnimationCompletesAtOnce() {
		TaskProgress t = new TaskProgress(1030, 0); // DEST03 뿔피리 ANM_horncsm1 "main" 길이 0
		TaskProgress.Event e = t.update(true, 1 / 60f);
		assertTrue(t.done);
		assertEquals(100, e.percent());
		assertEquals(1030, e.taskId());
	}

	@Test
	void ninetyNineUntilEnd() {
		TaskProgress t = new TaskProgress(1, 100f);
		t.time = 99.6f;
		TaskProgress.Event e = t.update(true, 0.001f);
		assertEquals(99, e.percent(), "round(99.6) = 100 이지만 t < len 이면 99");
	}
}
