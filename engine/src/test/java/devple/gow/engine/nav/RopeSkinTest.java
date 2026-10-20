package devple.gow.engine.nav;

import devple.gow.tools.level.LevelData;
import devple.gow.tools.level.LevelLayout;
import devple.gow.tools.level.RopeDef;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 줄 시뮬레이션 노드 → 줄 모델 관절 (0x216FC8 사슬, 0x217E5C 관절 행렬, 47 문서 §5). */
class RopeSkinTest {

	@Test
	void verticalNodesGiveIdentityRotation() {
		Vector3f[] p = {new Vector3f(0, 100, 0), new Vector3f(0, 90, 0), new Vector3f(0, 80, 0)};
		float[][] j = RopeSkin.joints(p, false);
		for (int i = 0; i < 3; i++) {
			assertArrayEquals(new float[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 100 - 10 * i, 0, 1}, j[i], 1e-6f, "관절 " + i);
		}
	}

	@Test
	void swungNodesFollowCentralDifference() {
		// 매단 점에서 +x 쪽으로 45° 기운 줄: Y = 위쪽 줄 방향, X = Y × z, Z = X × Y
		Vector3f[] p = {new Vector3f(0, 0, 0), new Vector3f(10, -10, 0), new Vector3f(20, -20, 0), new Vector3f(25, -30, 0)};
		float[][] j = RopeSkin.joints(p, false);
		float s = (float) Math.sqrt(0.5);
		assertEquals(-s, j[1][4], 1e-6f);
		assertEquals(s, j[1][5], 1e-6f);
		// X = Y × (0,0,1) = (Yy, −Yx, 0)
		assertEquals(s, j[1][0], 1e-6f);
		assertEquals(s, j[1][1], 1e-6f);
		assertEquals(0, j[1][2], 1e-6f);
		// Z = X × Y = (0, 0, 1)
		assertEquals(1, j[1][10], 1e-6f);
		// 끝 노드 = 한쪽 차분 p[n−2] − p[n−1]
		Vector3f y = new Vector3f(-5, 10, 0).normalize();
		assertEquals(y.x, j[3][4], 1e-6f);
		assertEquals(y.y, j[3][5], 1e-6f);
		assertEquals(25, j[3][12], 1e-6f);
		// 양끝 고정 줄 (수평): ref = 위 → X = Y × 위, Z = X × Y = 위
		float[][] h = RopeSkin.joints(new Vector3f[]{new Vector3f(0, 0, 0), new Vector3f(-10, 0, 0)}, true);
		assertArrayEquals(new float[]{0, 0, 1}, new float[]{h[0][0], h[0][1], h[0][2]}, 1e-6f);
		assertArrayEquals(new float[]{1, 0, 0}, new float[]{h[0][4], h[0][5], h[0][6]}, 1e-6f);
		assertArrayEquals(new float[]{0, 1, 0}, new float[]{h[0][8], h[0][9], h[0][10]}, 1e-6f);
	}

	/** ATHN03C gorope12: 관절 0 (rope12, 매단 점) 의 자식 중 플래그 0x20 사슬 41 개 = 노드 수, 매단 점에서 가까운 순. */
	@Test
	void athn03cRopeChainAndNodes() throws Exception {
		LevelData lv = NavDataTest.level("ATHN03C");
		LevelLayout.Placement pl = lv.layout.placements().stream().filter(p -> p.instance().name().equals("gorope12")).findFirst().orElseThrow();
		float[][] jw = pl.jointWorld();
		int[] chain = RopeSkin.chain(pl.object().skeleton(), jw, 0);
		assertEquals(41, chain.length);
		assertEquals("joint164", pl.object().skeleton().joints.get(chain[0]).name());
		assertEquals("joint204", pl.object().skeleton().joints.get(chain[40]).name());
		for (int i = 1; i < chain.length; i++) {
			assertTrue(jw[chain[i]][13] < jw[chain[i - 1]][13], "아래로 이어짐");
		}
		NavRope r = NavDataTest.byOwner(NavRope.of(lv), "gorope12");
		assertEquals(41, r.joints(), "노드 수 = 사슬 관절 수 (+0x54)");
		// 잠든 곧은 줄 → 관절 = 바인드와 같은 위치 (4 유닛 간격, 노드 0 = 매단 점)
		RopeSim sim = new RopeSim(r.top(), r.simLength(), r.joints());
		float[][] posed = RopeSkin.pose(jw, chain, sim.nodePositions(), false);
		for (int i = 0; i < chain.length; i++) {
			assertEquals(jw[chain[i]][13], posed[chain[i]][13], 0.5f, "노드 " + i);
			assertEquals(jw[chain[i]][12], posed[chain[i]][12], 1e-3f);
		}
		assertSame(jw[0], posed[0], "사슬 밖 관절은 그대로");
		// 매달려 흔들면 노드가 옆으로 → 아래쪽 관절이 따라 움직인다
		sim.attach(8f);
		for (int k = 0; k < 60; k++) {
			sim.setForce(new Vector3f(400, 0, 0));
			sim.update(1 / 60f);
		}
		posed = RopeSkin.pose(jw, chain, sim.nodePositions(), false);
		assertTrue(posed[chain[40]][12] - jw[chain[40]][12] > 5, "끝 관절 x 이동 " + (posed[chain[40]][12] - jw[chain[40]][12]));
		assertTrue(Math.abs(posed[chain[40]][4]) > 0.01f, "관절 Y 축이 기울어짐");
	}

	/** 전 줄 레벨: 사슬이 2 개 이상 (노드 = 사슬), 양끝 고정 줄도 같은 규칙. */
	@Test
	void allRopeLevelsHaveChains() throws Exception {
		for (String lvName : new String[]{"ATHN03C", "ATHN05C", "PAND04E", "ATHN06C", "ATHN01E"}) {
			LevelData lv = NavDataTest.level(lvName);
			for (NavRope r : NavRope.of(lv)) {
				LevelLayout.Placement pl = lv.layout.placements().stream().filter(p -> p.instance().name().equals(r.owner())).findFirst().orElseThrow();
				int[] chain = RopeSkin.chain(pl.object().skeleton(), pl.jointWorld(), 0);
				assertTrue(chain.length >= 2, lvName + " " + r.owner() + " " + r.type());
				assertEquals(chain.length, r.joints());
				if (r.type() != RopeDef.Type.HANG) {
					RopeSim f = RopeSim.fixed(r.top(), r.end(), r.joints());
					float[][] posed = RopeSkin.pose(pl.jointWorld(), chain, f.nodePositions(), true);
					assertEquals(r.end().x, posed[chain[chain.length - 1]][12], 1e-2f, "끝 노드 = 끝점");
				}
			}
		}
	}
}
