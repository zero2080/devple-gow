package devple.gow.engine.input;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 카메라 컷 뒤 입력 기준 유지 (원작 0x1DCE80 / 0x1DD1E8, 30 문서 §9.1). */
class InputFrameTest {

	private static final float A = (float) Math.toRadians(30), B = (float) Math.toRadians(150);

	@Test
	void cutHoldsReferenceWhileStickSteady() {
		InputFrame f = new InputFrame();
		assertEquals(A, f.update(A, 0, 1), 1e-6f); // 보통: 지금 카메라
		f.selected(true); // 컷 (전환 ≤ 0.1초)
		assertEquals(InputFrame.HOLD_FRAMES, f.hold());
		for (int i = 0; i < 600; i++) { // 10 초 — 프레임으로 줄지 않는다
			assertEquals(A, f.update(B, 0.02f, 1), 1e-6f, "같은 쪽으로 밀고 있는 동안 컷 전 카메라 기준");
		}
		assertEquals(InputFrame.HOLD_FRAMES, f.hold());
		assertEquals(B, f.update(B, 0, 0), 1e-6f, "스틱을 놓으면 바로 지금 카메라");
		assertEquals(0, f.hold());
	}

	@Test
	void turningStickResyncsInSevenDegreeSteps() {
		InputFrame f = new InputFrame();
		f.update(A, 0, 1);
		f.selected(true);
		// 스틱을 20° 돌림 → 7° 단계 2 번: 기준 = slerp(1/60) 뒤 slerp(1/59), 남은 59 → 58
		float s = (float) Math.toRadians(20);
		float r = f.update(B, -(float) Math.sin(s), (float) Math.cos(s));
		float d = B - A, step1 = A + d / 60, step2 = step1 + (B - step1) / 59;
		assertEquals(step2, r, 1e-4f);
		assertEquals(58, f.hold());
		// 계속 그 각이면 매 프레임 2 단계씩 (기준각 +0x104 는 컷 순간 값 그대로) — 29 프레임 뒤 남은 0 → 다음 프레임 지금 카메라
		for (int i = 0; i < 29; i++) {
			f.update(B, -(float) Math.sin(s), (float) Math.cos(s));
		}
		assertEquals(0, f.hold());
		assertEquals(B, f.update(B, -(float) Math.sin(s), (float) Math.cos(s)), 1e-6f);
	}

	@Test
	void releaseFromGlobalIdleSuppressesTwoSelections() {
		InputFrame f = new InputFrame();
		f.update(A, 0, 1);
		f.selected(true);
		f.release(2); // 0x1BF080 → 0x1EF2F0(2): 유지 지움, 다음 두 선택은 컷이어도 유지 없음
		assertEquals(0, f.hold());
		f.selected(true);
		f.selected(true);
		assertEquals(0, f.hold());
		f.selected(true);
		assertEquals(InputFrame.HOLD_FRAMES, f.hold());
	}
}
