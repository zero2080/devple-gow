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

	/**
	 * 43 §13.10 / 38 §4: CDV 발판 바닥 = 윗면 평면 재질 (재질 엔트리 = 시트 표면 레코드, +0x18 bitfield0) 이 영웅 질의 제외 마스크에 안 걸리는 것
	 * [확실: 0x1E3298 → 0x139218]. PAND04AA 엘리베이터 바닥 hull 재질 {@code EntitiesPand04AA_SheetM} 은 이름에 ground 가 없지만 Ground —
	 * 도착점 WarpFrmCore 아래 정적 바닥 (−239) 이 아니라 엘리베이터에 서서 AnimLiftUp ("!0") 끝까지 실려 올라간다.
	 */
	@Test
	void pand04aaArrivalRidesElevator() throws Exception {
		LevelData d = level("PAND04AA");
		ScriptWorld sw = new ScriptWorld(d, new ScriptHost(), null);
		float[] p = sw.warpTarget("PAND00C").position();
		NavWorld w = new NavWorld(new LevelCollision(d.sheets));
		w.setPlatforms(Platforms.of(d, devple.gow.engine.game.SceneNav.animatorOwners(sw)));
		NavWorld.Floor f = w.floor(p[0], p[1] + 24, p[2], 400);
		assertNotNull(f);
		assertNotNull(f.platform(), "엘리베이터 바닥 " + f.y());
		assertEquals("goelevatorlift", f.platform().body().owner);
		assertEquals(p[1], f.y(), 3);
		assertTrue(f.has(devple.gow.tools.level.SurfaceAttr.GROUND));
		assertEquals(-239, w.statics().getFirst().floorAt(p[0], p[1], p[2], 400).y(), 1, "그 아래 정적 바닥");
		assertTrue(w.platforms().play("goelevatorlift", "!0", 0x8, 1, 0));
		org.joml.Vector3f r = new org.joml.Vector3f(p[0], f.y(), p[2]);
		for (float t = 0; t < 4; t += 1 / 60f) {
			w.platforms().update(1 / 60f);
			NavWorld.Floor q = w.floor(r.x, r.y + 4, r.z, 16);
			assertNotNull(q, "t=" + t + " " + r);
			assertNotNull(q.platform(), "t=" + t);
			Platforms.carry(r, q.platform());
		}
		assertEquals(45, r.y, 4, "엘리베이터 끝 (애니 3 초)");
	}

	/** CDV 재질 엔트리 = 시트 표면 레코드 배치: 불 함정 DamageMat 은 NoPushPullCollision (Ground 아님), 엘리베이터 SheetM 은 Ground. */
	@Test
	void cdvMaterialEntriesAreSurfaceRecords() throws Exception {
		devple.gow.tools.level.BallHull el = level("PAND04AA").hulls.get("CDV_ElevatorLift");
		int m = el.names.indexOf("EntitiesPand04AA_SheetM");
		assertTrue(m >= 0);
		assertEquals(devple.gow.tools.level.SurfaceAttr.GROUND.bit() | devple.gow.tools.level.SurfaceAttr.TAKE_DAMAGE.bit(), el.materialAttrs[m]);
		devple.gow.tools.level.BallHull fire = level("PAND02C").hulls.get("CDV_LeftFire");
		assertEquals(devple.gow.tools.level.SurfaceAttr.NO_PUSH_PULL_COLLISION.bit() | devple.gow.tools.level.SurfaceAttr.TAKE_DAMAGE.bit(), fire.materialAttrs[0]);
		assertFalse(Platforms.heroSolid(devple.gow.tools.level.SurfaceAttr.NO_PLAYER_COLLISION.bit()));
		assertTrue(Platforms.heroSolid(fire.materialAttrs[0]));
	}

	/**
	 * 43 §13.10: ATHN02A ← ATHN01F Warp. Start ShipTrans-Anim-WarpFrom1F 는 goshiptrans 관절 1 에 붙어 있지만 Warp_Jump 0x1B2E08 은 그 순간의
	 * 월드 행렬 (0x1A9CD0) 로 SetMatrix 만 한다 (부모 붙이기 없음). 영웅이 서는 곳은 정박한 배 goshipgroup (Animator 없는 정지 CDV GO) 의 갑판.
	 */
	@Test
	void athn02aArrivalStandsOnDockedShipDeck() throws Exception {
		LevelData d = level("ATHN02A");
		ScriptWorld sw = new ScriptWorld(d, new ScriptHost(), null);
		ScriptEntity st = sw.warpTarget("ATHN01F");
		assertEquals("ShipTrans-Anim-WarpFrom1F", st.name());
		assertEquals("goshiptrans", st.ownerName());
		float[] p = st.position();
		NavWorld w = new NavWorld(new LevelCollision(d.sheets));
		w.setPlatforms(Platforms.of(d, devple.gow.engine.game.SceneNav.animatorOwners(sw), java.util.Set.of()));
		Platforms.Body ship = w.platforms().body("goshipgroup");
		assertNotNull(ship, "정지 CDV GO 도 충돌");
		assertFalse(ship.scripted);
		NavWorld.Floor f = w.floor(p[0], p[1] + 24, p[2], 400);
		assertNotNull(f.platform());
		assertEquals("goshipgroup", f.platform().body().owner);
		assertEquals(p[1], f.y(), 2, "갑판 = 도착점 높이");
		assertEquals(-800, w.statics().getFirst().floorAt(p[0], p[1], p[2], 400).y(), 1, "정적 시트만이면 수로 바닥");
	}

	/**
	 * 30 §6.4: 배가 부두로 들어온다 — ANM_shiptrans 의 타입 10 채널 (관절 1 "Anim" Z, 0 → −480, 10.4 초) 과 GO 부모 링크 (goshipgroup 0x58 =
	 * goshiptrans 관절 1). 끝 자세에서 갑판·아테나 석상 Goto 지점·R2 볼륨이 −480 자리 = 고정 카메라 AthenaCam (z 3494) 앞.
	 */
	@Test
	void athn02aShipDocksWithShipTransAnimJoint() throws Exception {
		LevelData d = level("ATHN02A");
		ScriptWorld sw = new ScriptWorld(d, new ScriptHost(), null);
		Platforms ps = Platforms.of(d, devple.gow.engine.game.SceneNav.animatorOwners(sw), java.util.Set.of());
		Platforms.Body ship = ps.body("goshipgroup");
		float z0 = ship.joints()[0][14];
		assertTrue(ps.play("goshiptrans", "!0", 8, 1, 0));
		for (int i = 0; i < 11 * 60; i++) {
			ps.update(1 / 60f);
		}
		assertTrue(ship.follows());
		assertEquals(z0 - 480, ship.joints()[0][14], 0.5f, "goshipgroup 이 goshiptrans 관절 1 을 따라 −480");
		assertEquals(4259.07f - 480, ps.body("goshiptrans").joints()[1][14], 0.5f, "Anim 관절 Z = 바인드 + 채널 −480");
		sw.followOwner("goshipgroup", ship.joints());
		float[] spot = sw.find("shipGroup-topDeck-MimeSpot").position();
		assertEquals(3936 - 480, spot[2], 1, "Goto 지점도 배를 따라감");
		float[] cam = d.camera("AthenaCam").eye();
		assertTrue(spot[2] - cam[2] < 0 && spot[2] - cam[2] > -60, "AthenaCam (z 3494) 이 석상·Goto 지점 바로 뒤 — 정박 전에는 440 앞이었다");
	}
}
