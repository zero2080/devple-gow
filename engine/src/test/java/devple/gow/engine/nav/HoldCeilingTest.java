package devple.gow.engine.nav;

import devple.gow.engine.level.LevelCollision;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.level.SurfaceAttr;
import devple.gow.tools.wad.WadFile;
import org.joml.Vector3f;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 천장 질의 0x1E36B0 · 한쪽 면 바닥/턱 (38 §22). ATHN01B 선창 (바닥 ≈ −140) 위 ATHN01A 갑판 = 윗면 0 (법선 +1) + 밑면 −64 (법선 −1).
 * 예전엔 2단 점프한 머리가 밑면을 지나고, 밑면을 바닥·턱으로 봐서 갑판 슬래브 안 (−64) 에 서거나 매달린 뒤 갑판 위로 올라갔다 (사용자 보고).
 */
class HoldCeilingTest {

	static final float DT = 1 / 60f;
	static final long EX = NavWorld.ceilingExclude(NavWorld.HERO_BODY_EXCLUDE);

	/** 아래를 보는 천장 사각형 (y, 법선 −1) 과 위를 보는 바닥 사각형 (y, 법선 +1). */
	static List<LevelCollision.Tri> slab(float y, boolean up) {
		Vector3f a = new Vector3f(-100, y, -100), b = new Vector3f(100, y, -100), c = new Vector3f(100, y, 100), d = new Vector3f(-100, y, 100);
		return up ? List.of(LevelCollision.Tri.of(a, c, b), LevelCollision.Tri.of(a, d, c))
				: List.of(LevelCollision.Tri.of(a, b, c), LevelCollision.Tri.of(a, c, d));
	}

	@Test
	void ceilingClampsHeadNotVelocity() {
		List<LevelCollision.Tri> t = new java.util.ArrayList<>(slab(0, true));
		t.addAll(slab(50, false));
		NavWorld w = new NavWorld(LevelCollision.of(t));
		assertTrue(slab(50, false).getFirst().n().y < -0.99f);
		// 바닥은 위를 보는 면만: 천장 위 (y 60) 에서 내려다봐도 천장 밑면 (50) 은 바닥이 아니다
		assertEquals(0, w.groundHeight(0, 60, 0, 100), 1e-3f);
		// 머리 (발 + 35.2) 가 천장 50 을 넘은 이동 → 발 = 50 − 35.2
		Vector3f foot = new Vector3f(0, 20, 0);
		assertTrue(w.ceiling(foot, 15, 0, HeroNavigator.HANG, HeroNavigator.FOOT, HeroNavigator.BODY, HeroNavigator.BODY, EX));
		assertEquals(50 - HeroNavigator.HANG, foot.y, 1e-3f);
		// 천장까지 여유가 있으면 그대로
		Vector3f low = new Vector3f(0, 5, 0);
		assertFalse(w.ceiling(low, 0, 0, HeroNavigator.HANG, HeroNavigator.FOOT, HeroNavigator.BODY, HeroNavigator.BODY, EX));
		assertEquals(5, low.y, 0);
		// 위를 보는 면은 천장이 아니다 (바닥 0 밑에서 올라와도)
		NavWorld floorOnly = new NavWorld(LevelCollision.of(slab(0, true)));
		Vector3f under = new Vector3f(0, -30, 0);
		assertFalse(floorOnly.ceiling(under, -40, 0, HeroNavigator.HANG, HeroNavigator.FOOT, HeroNavigator.BODY, HeroNavigator.BODY, EX));
		// GeneralGuide 천장은 지나간다 (0x1010042)
		Vector3f a = new Vector3f(-100, 50, -100), b = new Vector3f(100, 50, -100), c = new Vector3f(100, 50, 100);
		NavWorld guide = new NavWorld(LevelCollision.of(List.of(LevelCollision.Tri.of(a, b, c, SurfaceAttr.GENERAL_GUIDE))));
		Vector3f g = new Vector3f(10, 20, -10);
		assertFalse(guide.ceiling(g, 15, 0, HeroNavigator.HANG, HeroNavigator.FOOT, HeroNavigator.BODY, HeroNavigator.BODY, EX));
	}

	@Test
	void athn01bHoldDoubleJumpStaysUnderDeck() throws Exception {
		Path rom = Path.of(System.getProperty("gow.rom", "../gow-rom"));
		Assumptions.assumeTrue(Files.exists(rom.resolve("ATHN01B.WAD")) && Files.exists(rom.resolve("ATHN01A.WAD")), "gow-rom 없음");
		NavWorld w = new NavWorld(new LevelCollision(new LevelData(WadFile.load(rom.resolve("ATHN01B.WAD"))).sheets));
		w.setOther(new LevelCollision(new LevelData(WadFile.load(rom.resolve("ATHN01A.WAD"))).sheets));
		float x = 1607, z = 1692; // 선창 승강구 옆 (재현: --arrive ATHN01A@1620,-130,1686 → 2단 점프)
		float floor = w.groundHeight(x, -120, z, 100);
		assertEquals(-140.2f, floor, 0.5f);
		// 갑판 밑면 −64 (법선 −1) 는 바닥이 아니다: 슬래브 안 (−50) 에서 내려다봐도 선창 바닥
		assertEquals(floor, w.groundHeight(x, -50, z, 200), 0.5f);
		// 위에서 내려오는 턱 윗면 선분은 밑면을 지나간다 (한쪽 면), 양면 선분은 맞는다
		Vector3f s = new Vector3f(x, -60, z), e = new Vector3f(x, -68, z);
		assertNotNull(w.segment(s, e, NavWorld.LEDGE_PROBE_EXCLUDE));
		assertNull(w.frontSegment(s, e, NavWorld.LEDGE_PROBE_EXCLUDE));
		// 2단 점프 (244, 정점 앞에서 다시 244 — HeroController.jump / GameScene.physics 와 같은 적분)
		Vector3f p = new Vector3f(x, floor, z);
		float vy = HeroNavigator.JUMP, top = p.y;
		boolean second = false, landed = false;
		for (int i = 0; i < 240 && !landed; i++) {
			if (!second && vy < 80) {
				vy = HeroNavigator.DOUBLE_JUMP;
				second = true;
			}
			float prev = p.y;
			vy = Math.max(vy - HeroNavigator.GRAVITY * DT, -HeroNavigator.FALL_MAX);
			p.y += vy * DT;
			if (vy > 0) {
				w.ceiling(p, prev, 0, HeroNavigator.HANG, HeroNavigator.FOOT, HeroNavigator.BODY, HeroNavigator.BODY, EX);
			}
			top = Math.max(top, p.y);
			float g = w.groundHeight(p.x, Math.max(p.y, prev) + 14, p.z, 4000);
			if (vy <= 0 && !Float.isNaN(g) && p.y <= g + 2) {
				p.y = g;
				landed = true;
			}
		}
		assertTrue(second);
		assertTrue(landed, "착지하지 않음: " + p.y);
		assertEquals(-64 - HeroNavigator.HANG, top, 0.5f, "머리가 갑판 밑면 −64 에서 멈춤");
		assertEquals(floor, p.y, 0.5f, "선창 바닥으로 돌아옴 (갑판 0 / 밑면 −64 아님)");
	}
}
