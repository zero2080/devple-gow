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

	/** PushPull 레버 (0x2127B0): 끝에 닿으면 속도 0 으로 멈추고 0x2118E8(t, len, 0) → 1023 (100, id, 0). */
	@Test
	void leverStopsAtEndWithSpeedZero() {
		TaskProgress t = new TaskProgress(111, 0.5f);
		t.stopAtEnd = true;
		List<TaskProgress.Event> ev = run(t, true, 1f);
		assertTrue(t.done);
		assertEquals(new TaskProgress.Event(100, 111, 0), ev.getLast());
	}

	/** IO_TandF (0x214400): 누를 때마다 속도 += Acc (최대), 위치 += 속도 · dt / 4, TF Time 뒤 −Dec · dt, 떨어지면 내려옴. */
	@Test
	void liftImpulsePerPressAndDecayAfterTfTime() {
		TaskProgress t = new TaskProgress(200, 1);
		TaskProgress.LiftParams p = new TaskProgress.LiftParams(1, 20, 1.5f, 20, 0.25f);
		float dt = 1 / 60f;
		t.lift(true, true, 0, p, dt);
		assertEquals(1f, t.speed, 1e-6f);
		t.lift(true, true, 0, p, dt);
		assertEquals(1.5f, t.speed, 1e-6f, "최대 1.5");
		float pos = t.time;
		t.lift(true, false, 0.1f, p, dt);
		assertEquals(pos + 1.5f * dt / 4, t.time, 1e-6f, "위치 += 속도 · dt / 4");
		assertEquals(1.5f, t.speed, 1e-6f, "TF Time 안에서는 그대로");
		t.lift(true, false, 0.3f, p, dt);
		assertEquals(1.5f - 20 * dt, t.speed, 1e-5f, "TF Time 뒤 −Dec · dt");
		for (int i = 0; i < 60; i++) {
			t.lift(false, false, 0, p, dt);
		}
		assertEquals(0, t.time);
		assertEquals(0, t.speed);
		TaskProgress.Event last = null;
		for (int i = 0; i < 600 && !t.done; i++) {
			TaskProgress.Event e = t.lift(true, i % 6 == 0, (i % 6) * dt, p, dt);
			if (e != null) {
				last = e;
			}
		}
		assertTrue(t.done);
		assertEquals(100, last.percent());
		assertEquals(200, last.taskId());
	}
}
