package devple.gow.engine.nav;

import devple.gow.engine.input.PadButton;
import devple.gow.engine.level.LevelCollision;
import devple.gow.tools.level.SurfaceAttr;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ClimbGuide 윗면은 바닥이 아니다: 원작 지상·공중 이동 0x1EA5F0 은 벽에 붙어 있지 않으면 호출 마스크에 0x20000 (ClimbGuide) 을 넣고 (0x1EA6A0 – 0x1EA6B8)
 * 그 마스크로 바닥 질의 0x1E3298 을 부른다 (0x1EA8D4) [확실]. 예전 엔진의 정적 바닥은 GeneralGuide 만 빼서, ATHN05A 덩굴 통로 (영상 6575–6651) 에서
 * 뒤로 점프한 크라토스가 맞은편 덩굴 벽을 잡지 못하고 그 벽 아래 16 폭 ClimbGuide 띠 윗면 (y 205 / 175) 에 서 버렸다 (43 문서 §19).
 */
class ClimbGuideFloorTest {

	@Test
	void climbGuideTopsAreNotStaticFloors() throws Exception {
		assertNotEquals(0, NavWorld.FLOOR_EXCLUDE_STATIC & SurfaceAttr.CLIMB_GUIDE.bit());
		NavWorld w = new NavWorld(new LevelCollision(NavDataTest.level("ATHN05A").sheets));
		// x = 606 덩굴 벽 (법선 −x, y 205–454, z 2960–3040) 아래 ClimbGuide 띠 윗면 y 205 (x 590–606)
		NavWorld.Floor f = w.floor(598, 215, 3000, 40);
		assertTrue(f == null || f.y() < 200, "띠 윗면 205 는 바닥이 아님: " + (f == null ? null : f.y()));
		// x = 450 벽 가운데 띠 (y 175, x 450–466)
		NavWorld.Floor g = w.floor(458, 185, 3000, 40);
		assertTrue(g == null || g.y() < 170, "띠 윗면 175 는 바닥이 아님: " + (g == null ? null : g.y()));
	}

	/** 덩굴 통로: x = 450 벽에서 뒤로 점프 (스틱 뒤 + ×) → 맞은편 x = 606 덩굴 벽에 붙는다 (예전: y 205 띠 위에 착지). */
	@Test
	void backJumpAcrossTheVineShaftCatchesTheOppositeWall() throws Exception {
		NavWorld w = new NavWorld(new LevelCollision(NavDataTest.level("ATHN05A").sheets));
		HeroNavigatorTest.Body b = new HeroNavigatorTest.Body();
		HeroNavigator nav = new HeroNavigator(b, w, null);
		HeroNavigatorTest.Pad in = new HeroNavigatorTest.Pad();
		b.grounded = false;
		b.yaw = (float) (Math.PI / 2); // 정면 −x (x = 450 벽, 법선 +x)
		b.pos.set(459, 264, 3007);
		HeroNavigatorTest.tick(nav, b, w, in);
		assertEquals(NavState.WALL_CLIMB, nav.state(), "x = 450 벽에 붙음 " + b.pos);
		in.ly = -1;
		in.down.add(PadButton.CROSS);
		HeroNavigatorTest.tick(nav, b, w, in);
		in.ly = 0;
		assertEquals(NavState.AIR, nav.state(), "뒤로 점프");
		int i = 0;
		for (; i < 120 && nav.state() == NavState.AIR; i++) {
			HeroNavigatorTest.tick(nav, b, w, in);
		}
		assertEquals(NavState.WALL_CLIMB, nav.state(), "맞은편 벽을 잡음 (ClimbGuide 띠 위 착지 아님) " + b.pos);
		assertTrue(b.pos.x > 580, "x = 606 벽 " + b.pos);
	}
}
