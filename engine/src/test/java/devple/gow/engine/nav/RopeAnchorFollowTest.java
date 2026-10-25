package devple.gow.engine.nav;

import devple.gow.tools.anim.Animation;
import devple.gow.tools.anim.AnimationSet;
import devple.gow.tools.anim.Mat4;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.level.LevelLayout;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 매단 줄의 매단 점은 소유 GO 관절 (+0x48) 을 매 프레임 따른다 (0x217A40) — PAND01A rope6 은 레버 뒤 ANM_rope6 (9.67 초) 로 줄 전체가 656 내려와
 * 위층 (바닥 y 146) 에서 뛰어 잡고 구멍 바닥 PAND01D 로 내려간다 (영상 4845–4852, 43 §34).
 */
class RopeAnchorFollowTest {

	@Test
	void pand01aRope6FollowsItsLoweringAnimation() throws Exception {
		LevelData lv = NavDataTest.level("PAND01A");
		NavRope r = NavRope.of(lv).stream().filter(x -> x.owner().equals("gorope6")).findFirst().orElseThrow();
		LevelLayout.Placement pl = lv.layout.placements().stream().filter(p -> p.instance().name().equals("gorope6")).findFirst().orElseThrow();
		assertEquals(933, r.top().y, 1);
		assertEquals(256, r.end().y, 1, "배치 자세: 끝이 위층 바닥 (146) 위 110");
		// 위층 가장자리에서 뛴 영웅의 잡기 점 (발 정점 ≈ 181 + 키 35.2, 0x1C5C20 반경 32)
		Vector3f hand = new Vector3f(-513, 181 + 35.2f, -1008);
		assertTrue(r.distance(hand) > 32, "배치 자리 줄은 위층에서 닿지 않는다");

		Animation a = AnimationSet.load(lv.wad, "ANM_rope6").all().getFirst();
		var sk = pl.object().skeleton();
		float[] j0 = Mat4.mul(a.sample(sk, a.duration, false).world(sk)[0], pl.instance().matrix());
		assertEquals(277, j0[13], 2, "ANM_rope6 끝: 뿌리 y 933 → 277");

		RopeSim sim = RopeSim.of(r);
		assertEquals(256, sim.node(sim.nodes() - 1, new Vector3f()).y, 1);
		assertTrue(r.moveTop(j0[12], j0[13], j0[14]));
		assertFalse(r.moveTop(j0[12], j0[13], j0[14]), "같은 자리면 그대로");
		sim.moveAnchor(r.top(), true);
		assertEquals(-400, r.end().y, 2, "선분 끝 = 구멍 바닥 (PAND01D)");
		assertTrue(r.distance(hand) < 32, "내려온 줄은 위층에서 뛰어 잡힌다");
		assertEquals(-400, sim.node(sim.nodes() - 1, new Vector3f()).y, 2, "노드째 옮김 (한 프레임 큰 이동)");
		assertEquals(277, sim.node(0, new Vector3f()).y, 1);
	}

	/** 조금씩 옮기면 (애니 재생 중) 매단 점만 옮기고 깨운다 — 나머지 노드는 스프링·중력으로 따라온다 (0x217A40 → 0x217478). */
	@Test
	void smallAnchorMoveWakesTheRope() {
		RopeSim sim = new RopeSim(new Vector3f(0, 320, 0), 160, 5);
		for (int i = 0; i < 60; i++) {
			sim.update(1 / 60f);
		}
		assertTrue(sim.sleeping());
		sim.moveAnchor(new Vector3f(0, 310, 0), false);
		assertFalse(sim.sleeping());
		for (int i = 0; i < 240; i++) {
			sim.moveAnchor(new Vector3f(0, 310 - i * 0.5f, 0), false);
			sim.update(1 / 60f);
		}
		assertEquals(190.5f, sim.node(0, new Vector3f()).y, 0.6f);
		assertTrue(sim.node(4, new Vector3f()).y < 60, "끝 노드도 따라 내려옴: " + sim.node(4, new Vector3f()).y);
	}
}
