package devple.gow.engine.nav;

import devple.gow.engine.level.LevelCollision;
import devple.gow.engine.script.ScriptEntity;
import devple.gow.engine.script.ScriptHost;
import devple.gow.engine.script.ScriptListener;
import devple.gow.engine.script.ScriptWorld;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.wad.WadFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ATRET01A ← HDES01E Warp (43 문서 §13.9): 도착 Start WarpFrmhdes05 는 무덤 줄 gorope1 한가운데이고 되돌아가는 볼륨
 * loadHDES01G (영역 1 LoadForWarp HDES01E, 영역 2 Warp) 안이다.
 */
class WarpArrivalTest {

	private static LevelData level(String name) throws Exception {
		Path wad = Path.of(System.getProperty("gow.rom", "../gow-rom"), name + ".WAD");
		Assumptions.assumeTrue(Files.exists(wad), "gow-rom 없음");
		return new LevelData(WadFile.load(wad));
	}

	/** 순간이동 프레임은 영웅 볼륨 선분을 건너뛴다 (0x1E0A18 → 0x1B3030 → 0x29D90C) → 도착점을 덮은 볼륨은 "안" 으로 시작, 발동 없음. */
	@Test
	void warpArrivalInsideVolumeDoesNotFireEntry() throws Exception {
		LevelData d = level("ATRET01A");
		for (boolean inside : new boolean[]{false, true}) {
			List<ScriptListener.LevelRequest> req = new ArrayList<>();
			ScriptWorld w = new ScriptWorld(d, new ScriptHost(), new ScriptListener() {
				@Override
				public void level(LevelRequest r) {
					req.add(r);
				}
			});
			ScriptEntity st = w.warpTarget("HDES01E");
			assertNotNull(st);
			assertEquals("WarpFrmhdes05", st.name());
			float[] p = st.position();
			w.start();
			w.placePlayer(p[0], p[1], p[2], inside);
			for (int i = 0; i < 10; i++) {
				w.tick(1 / 60f, p[0], p[1], p[2]);
			}
			if (inside) {
				assertTrue(req.isEmpty(), "Warp 도착: 되돌아가지 않음 " + req);
			} else {
				assertTrue(req.stream().anyMatch(r -> r.op() == ScriptListener.LevelOp.LOAD_FOR_WARP && r.wad().equals("HDES01E")),
						"(대조) 도착 때 진입으로 치면 HDES01E 를 다시 부른다 " + req);
			}
		}
	}

	/** 영웅 SetMatrix 0x1E79D0: 줄이면 놓기 0x1C4FF8(1) = 떨어짐, 다음 프레임 공중 줄 잡기 0x1C5C20 → 도착점 줄 gorope1 을 잡는다. */
	@Test
	void warpArrivalGrabsRopeAtStart() throws Exception {
		LevelData d = level("ATRET01A");
		NavWorld w = new NavWorld(new LevelCollision(d.sheets));
		w.ropes().addAll(NavRope.of(d));
		ScriptEntity st = new ScriptWorld(d, new ScriptHost(), null).warpTarget("HDES01E");
		float[] p = st.position();
		HeroNavigatorTest.Body b = new HeroNavigatorTest.Body();
		b.grounded = false;
		b.pos.set(p[0], p[1], p[2]);
		HeroNavigator nav = new HeroNavigator(b, w, null);
		nav.reset();
		nav.warpArrived();
		HeroNavigatorTest.tick(nav, b, w, new HeroNavigatorTest.Pad());
		assertEquals(NavState.ROPE, nav.state(), "도착 프레임에 줄 " + b.pos);
		assertEquals("gorope1", nav.rope().owner());
		assertEquals(p[1], b.pos.y, 40, "줄 한가운데 그대로 " + b.pos);
	}

	/**
	 * 43 §13.10: Warp_Jump 끝 0x1A9C18 이 도착 Start 의 대상을 발동한다 — PAND04AA WarpFrmCore → ElevatorLift-AnimLiftUp ("!0"). HDES01E rope3 /
	 * ATRET01A gorope1 은 SCR f3 짧은 사슬 줄 (사슬 관절 2) — 흔들기 힘이 없다.
	 */
	@Test
	void warpStartTargetsFireAndShortChainRopes() throws Exception {
		LevelData d = level("PAND04AA");
		List<String> anims = new ArrayList<>();
		ScriptWorld w = new ScriptWorld(d, new ScriptHost(), new ScriptListener() {
			@Override
			public void animate(ScriptEntity source, List<String> objects, String anim) {
				anims.add(String.join(",", objects) + ":" + anim);
			}
		});
		ScriptEntity st = w.warpTarget("PAND00C");
		assertEquals("WarpFrmCore", st.name());
		w.start();
		assertFalse(anims.contains("goelevatorlift:!0"));
		w.fireStart(st);
		assertTrue(anims.contains("goelevatorlift:!0"), "도착 Start 대상 → 엘리베이터 " + anims);
		for (String[] c : new String[][]{{"HDES01E", "gorope3"}, {"ATRET01A", "gorope1"}}) {
			NavRope r = NavRope.of(level(c[0])).stream().filter(x -> x.owner().equals(c[1])).findFirst().orElseThrow();
			assertTrue(r.shortChain(), c[1]);
			assertEquals(2, r.joints(), c[1]);
		}
	}
}
