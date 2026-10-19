package devple.gow.engine.nav;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 매단 줄 질점-스프링 (원작 0x217A40 / 0x217478, 38 문서 §2.3). */
class RopeSimTest {

	static final float DT = 1 / 60f;

	@Test
	void restingRopeStaysStraightAndSleeps() {
		RopeSim r = new RopeSim(new Vector3f(0, 320, 0), 160, 9);
		for (int i = 0; i < 120; i++) {
			r.update(DT);
		}
		Vector3f end = r.pointAt(r.length(), new Vector3f());
		assertEquals(0, end.x, 1e-3f);
		assertEquals(160, end.y, 1e-3f, "잠든 줄은 처음 자세 그대로 (곧게 아래로 10 m)");
		assertEquals(5, r.pointAt(5, new Vector3f()).distance(0, 320, 0) / 16, 1e-4f);
	}

	@Test
	void heroWeightRampsAndSags() {
		RopeSim r = new RopeSim(new Vector3f(0, 320, 0), 160, 9);
		r.attach(9.75f);
		r.update(DT);
		assertEquals(RopeSim.WEIGHT_HANG * 0.5f * DT, r.weight(), 1e-4f, "무게 = 최대 × 0.5 /초 (0x217C48)");
		for (int i = 0; i < 300; i++) {
			r.setForce(new Vector3f());
			r.update(DT);
		}
		assertEquals(RopeSim.WEIGHT_HANG, r.weight(), 1e-3f);
		Vector3f hand = r.pointAt(r.dist(), new Vector3f());
		float sag = 320 - 9.75f * 16 - hand.y;
		// 매단 점에서 9.75 m: 100 kg × 9.82 를 칸 스프링 10000 N/m 와 매단 점 스프링 5000 (쉼 1.1 배) 이 받친다 → 몇 유닛 처짐
		assertTrue(sag > 2 && sag < 30, "처짐 " + sag);
		assertEquals(0, hand.x, 0.01f);
	}

	/**
	 * 매달린 무게 100 kg 진자. 감쇠 Global Damping 0.003 은 하위 단계 (60 Hz × 8) 마다 속도에 곱해지므로 (0x21772C)
	 * 감쇠율 γ = −ln(0.997) × 480 ≈ 1.44 /초 — 감쇠 진동 주기 2π / √(g/L − γ²/4).
	 */
	@Test
	void swingsLikeAPendulumAfterAPush() {
		float lenM = 8;
		RopeSim r = new RopeSim(new Vector3f(0, 400, 0), lenM * 16, 9);
		r.attach(lenM - 0.25f);
		for (int i = 0; i < 180; i++) {
			r.setForce(new Vector3f());
			r.update(DT);
		}
		// 흔들기 창 0.25 초: 힘 = 500 × 거리 × 2 × 남은 시간 (0x1C6BE8)
		float timer = 0.25f;
		while (timer > 0) {
			r.setForce(new Vector3f(1, 0, 0).mul(HeroNavigator.SWING_FORCE * r.dist() * 2 * timer));
			r.update(DT);
			timer -= DT;
		}
		float prev = 0, maxX = 0;
		int firstCross = -1, secondCross = -1;
		for (int i = 0; i < 900 && secondCross < 0; i++) {
			r.setForce(new Vector3f());
			r.update(DT);
			float x = r.pointAt(r.dist(), new Vector3f()).x;
			maxX = Math.max(maxX, Math.abs(x));
			if (i > 0 && prev > 0 && x <= 0) {
				if (firstCross < 0) {
					firstCross = i;
				} else {
					secondCross = i;
				}
			}
			prev = x;
		}
		assertTrue(maxX > 16, "흔들림 " + maxX);
		assertTrue(secondCross > 0, "두 번 지나감");
		float period = (secondCross - firstCross) * DT;
		double gamma = -Math.log(1 - RopeSim.GLOBAL_DAMPING) * RopeSim.SUBSTEPS * 60;
		float expect = (float) (2 * Math.PI / Math.sqrt(RopeSim.GRAVITY / (lenM - 0.25f) - gamma * gamma / 4));
		assertEquals(expect, period, expect * 0.15f, "주기");
	}

	/**
	 * 양끝 고정 줄 (수평 줄·짚라인, 0x216FC8 / 0x218488 / 0x217748): 길이 = |끝 − 매단 점|, 처음 곧은 선, 끝 노드는 끝점에 고정,
	 * 매달림 최대 무게 10 (0x216D84) → 가운데 매달리면 처지고, 놓으면 (무게가 줄어) 다시 곧아진다.
	 */
	@Test
	void fixedRopeHoldsBothEndsAndSagsUnderHero() {
		Vector3f top = new Vector3f(0, 320, 0), end = new Vector3f(320, 320, 0);
		RopeSim r = RopeSim.fixed(top, end, 9);
		assertTrue(r.isFixed());
		assertEquals(20f, r.length(), 1e-4f);
		assertEquals(RopeSim.WEIGHT_FIXED, r.maxWeight(), 0);
		assertEquals(160, r.pointAt(10, new Vector3f()).x, 1e-3f, "처음 곧은 선");
		assertEquals(320, r.pointAt(10, new Vector3f()).y, 1e-3f);
		r.attach(10);
		for (int i = 0; i < 300; i++) {
			r.setForce(new Vector3f());
			r.update(DT);
		}
		assertEquals(RopeSim.WEIGHT_FIXED, r.weight(), 1e-3f);
		Vector3f mid = r.pointAt(10, new Vector3f());
		float sag = 320 - mid.y;
		assertTrue(sag > 0.5f && sag < 40, "가운데 처짐 " + sag);
		Vector3f e = r.pointAt(r.length(), new Vector3f());
		assertEquals(320, e.x, 1e-3f, "끝 노드 고정");
		assertEquals(320, e.y, 1e-3f);
		r.detach();
		for (int i = 0; i < 600; i++) {
			r.update(DT);
		}
		assertTrue(320 - r.pointAt(10, new Vector3f()).y < sag * 0.5f, "놓으면 덜 처짐");
	}
}
