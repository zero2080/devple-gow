package devple.gow.engine.nav;

import devple.gow.engine.game.SceneNav;
import devple.gow.engine.game.Tweaks;
import devple.gow.engine.level.LevelCollision;
import devple.gow.engine.script.ScriptActor;
import devple.gow.engine.script.ScriptEntity;
import devple.gow.engine.script.ScriptHost;
import devple.gow.engine.script.ScriptListener;
import devple.gow.engine.script.ScriptWorld;
import devple.gow.engine.input.PadButton;
import devple.gow.tools.level.CollisionSheet;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.level.RopeDef;
import devple.gow.tools.level.SheetSurface;
import devple.gow.tools.level.SurfaceAttr;
import devple.gow.tools.wad.WadFile;
import org.joml.Vector3f;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 원작 데이터 기반: 표면 속성, 줄, R1 오브젝트, 발판, Marker (38 문서). */
class NavDataTest {

	static Path rom() {
		Path rom = Path.of(System.getProperty("gow.rom", "../gow-rom"));
		Assumptions.assumeTrue(Files.exists(rom.resolve("ATHN01D.WAD")), "gow-rom 없음");
		return rom;
	}

	static LevelData level(String name) throws Exception {
		return new LevelData(WadFile.load(rom().resolve(name + ".WAD")));
	}

	@Test
	void sheetSurfaceAttributesDecode() throws Exception {
		LevelData lv = level("ATHN01D");
		CollisionSheet s = lv.sheets.getFirst();
		assertEquals(20, s.surfaces.size());
		SheetSurface climb = s.surfaces.stream().filter(x -> x.name().equals("visuals01D_Climbable")).findFirst().orElseThrow();
		assertTrue(climb.has(SurfaceAttr.CLIMBABLE) && climb.has(SurfaceAttr.NO_BACK_JUMPS) && climb.has(SurfaceAttr.TAKE_DAMAGE));
		assertFalse(climb.has(SurfaceAttr.GROUND));
		assertEquals(73, climb.sheetFx());
		SheetSurface wood = s.surfaces.getFirst();
		assertEquals("visuals01D_groundWood", wood.name());
		assertEquals(SurfaceAttr.GROUND.bit() | SurfaceAttr.TAKE_DAMAGE.bit(), wood.attrs());
		assertEquals(30, wood.sheetFx());
		assertEquals(8, wood.partType());
		SheetSurface ai = s.surfaces.stream().filter(x -> x.name().equals("visuals01D_noPlayerNoAI")).findFirst().orElseThrow();
		assertTrue(ai.has(SurfaceAttr.NO_PLAYER_COLLISION) && ai.has(SurfaceAttr.NO_AI_COLLISION) && ai.has(SurfaceAttr.NO_PLAYER_USE)
				&& ai.has(SurfaceAttr.NO_AI_USE));
		// 면 → 속성: 돛대 그물 (Climbable 317 면), 사다리 1, 좁은 길 23
		LevelCollision col = new LevelCollision(lv.sheets);
		long climbable = col.triangles().stream().filter(t -> t.has(SurfaceAttr.CLIMBABLE)).count();
		assertTrue(climbable > 300, "Climbable 삼각형 " + climbable);
		assertTrue(col.triangles().stream().anyMatch(t -> t.has(SurfaceAttr.LADDER)));
		assertTrue(col.triangles().stream().anyMatch(t -> t.has(SurfaceAttr.NARROW) && t.floor()));
	}

	@Test
	void everySheetHasGroundAndKnownSchema() throws Exception {
		int sheets = 0, polys = 0, bad = 0;
		try (var ds = Files.newDirectoryStream(rom(), "*.WAD")) {
			for (Path p : ds) {
				if (p.getFileName().toString().startsWith("R_")) {
					continue;
				}
				for (CollisionSheet s : new LevelData(WadFile.load(p)).sheets) {
					sheets++;
					assertFalse(s.surfaces.isEmpty(), p.toString());
					assertTrue(s.surfaces.stream().anyMatch(x -> x.has(SurfaceAttr.GROUND)), p + " Ground 없음");
					for (CollisionSheet.Poly poly : s.polys) {
						polys++;
						if (s.surface(poly.surface()) == null) {
							bad++; // PAND03D 사각형 10 개 (111, 127 … 271: 16 간격) — 범위 밖, Ground 로 취급
						}
					}
				}
			}
		}
		assertTrue(sheets > 100, "시트 " + sheets);
		assertTrue(bad < polys / 1000, "범위 밖 표면 번호 " + bad + " / " + polys);
	}

	@Test
	void ropesDecode() throws Exception {
		LevelData d = level("ATHN01D");
		RopeDef r = d.ropes.get("gorope3");
		assertNotNull(r);
		assertEquals(RopeDef.Type.ZIPLINE, r.type());
		assertEquals(3, r.template()); // TWK Rope/003 "ZipLine"
		NavRope zip = NavRope.of(d).getFirst();
		assertEquals(1201, zip.top().y, 1);
		assertEquals(-2656, zip.end().x, 1);
		assertEquals(788, zip.end().y, 1);
		// 짚라인 끝 아래 = ATHN01E 갑판 (시작 지점 옆)
		LevelCollision e = new LevelCollision(level("ATHN01E").sheets);
		float g = e.groundHeight(zip.end().x, zip.end().y + 64, zip.end().z, 200);
		assertFalse(Float.isNaN(g), "짚라인 끝 아래 ATHN01E 바닥");
		// 돛대 꼭대기 (줄 시작) 근처에 ATHN01D 바닥
		LevelCollision dc = new LevelCollision(d.sheets);
		assertFalse(Float.isNaN(dc.groundHeight(zip.top().x, zip.top().y, zip.top().z, 200)), "돛대 꼭대기 발판");
		LevelData b = level("ATHN02B");
		assertEquals(RopeDef.Type.TRAVERSE, b.ropes.get("gorope8").type());
	}

	/** IO_TandF 들어 올리는 문 (ATHN01C goliftdoor1, TAndF 050 "HydraLiftDoor": Acc 1, Dec 20, TF Time 0.25) — 연타 중 오르고 멈추면 빨리 내려온다. */
	@Test
	void liftDoorTask() throws Exception {
		LevelData d = level("ATHN01C");
		Tweaks tw = new Tweaks();
		tw.load(d.wad);
		Interactables.Item lift = Interactables.of(d, tw).byOwner("goliftdoor1");
		assertNotNull(lift);
		assertEquals(Interactables.Kind.LIFT, lift.kind);
		assertEquals(200, lift.id);
		assertEquals(1f, lift.engagedSpeed, 1e-3f);
		assertEquals(-20f, lift.rewindSpeed, 1e-3f);
		assertEquals("liftdoor2", lift.heroAnim);
		assertTrue(lift.length > 0.1f, "오브젝트 애니 길이 " + lift.length);
		TaskProgress tp = lift.task();
		for (int i = 0; i < 30; i++) {
			tp.update(true, 1 / 60f);
		}
		float up = tp.time;
		tp.update(false, 1 / 60f);
		assertEquals(Math.max(0, up - 20f / 60f), tp.time, 1e-3f, "놓으면 Dec 20 으로 내려옴");
	}

	@Test
	void interactablesFromBehaviors() throws Exception {
		LevelData d = level("ATHN01D");
		Tweaks tw = new Tweaks();
		tw.load(d.wad);
		Interactables it = Interactables.of(d, tw);
		Interactables.Item door = it.byOwner("gotwinkickdoor");
		assertNotNull(door);
		assertEquals(Interactables.Kind.CSM, door.kind);
		assertEquals(44, door.template);
		assertEquals(206, door.id);
		assertEquals("doorKick", door.heroAnim);
		assertEquals("HydraKickDoor", door.label);
		Interactables.Item cap = it.byOwner("gonewcaptainunlock");
		assertEquals(202, cap.id);
		assertEquals("captainsDoorOpen", cap.heroAnim);
		LevelData p = level("PAND05B");
		Tweaks pt = new Tweaks();
		pt.load(p.wad);
		Interactables pi = Interactables.of(p, pt);
		Interactables.Item box = pi.byOwner("gopboxpand05b");
		assertEquals(Interactables.Kind.PUSH, box.kind);
		assertEquals(503, box.id);
		assertEquals("Pandora's Box", box.label);
		assertEquals(Interactables.Kind.LEVER, pi.byOwner("godoorcrank").kind);
	}

	/** 과제 진행률의 분모 = 오브젝트 애니 길이 (CSM TWK #fea292e6 act, 없으면 가장 긴 act). 레버도 1023 을 보낸다 (PushPull id). */
	@Test
	void taskLengthsFromObjectAnimations() throws Exception {
		LevelData d = level("ATHN01D");
		Tweaks tw = new Tweaks();
		tw.load(d.wad);
		assertEquals(0.9f, Interactables.of(d, tw).byOwner("gotwinkickdoor").length, 0.01f, "ANM_twinkickdoor \"Default\"");
		LevelData c = level("PAND00C");
		Tweaks ct = new Tweaks();
		ct.load(c.wad);
		Interactables.Item lever = Interactables.of(c, ct).byOwner("goelevatorlever");
		assertEquals(Interactables.Kind.LEVER, lever.kind);
		assertEquals(111, lever.id, "ElevatorLever-LeverProgress: 1023 인자 1 == 111 ∧ 인자 0 == 100");
		assertEquals(3.667f, lever.length, 0.01f);
		LevelData h = level("DEST03");
		Tweaks ht = new Tweaks();
		ht.load(h.wad);
		Interactables.Item horn = Interactables.of(h, ht).byOwner("gohorncsm1");
		assertEquals(1030, horn.id);
		assertEquals(0f, horn.length, "길이 0 → 붙자마자 100 %");
	}

	@Test
	void boxMarkerIsObjectGroup511AndTriggersHadesWarp() throws Exception {
		LevelData p = level("PAND05B");
		List<String> movies = new ArrayList<>();
		ScriptWorld w = new ScriptWorld(p, new ScriptHost(), new ScriptListener() {
			@Override
			public void movie(String name, int op, int arg) {
				movies.add(name);
			}
		});
		w.start();
		List<ScriptActor> m = w.markers("gopboxpand05b");
		assertEquals(1, m.size());
		assertEquals(511, m.getFirst().group);
		// 상자를 semoMovieHaveBox 볼륨으로
		ScriptEntity sensor = w.entities().stream().filter(e -> e.name().endsWith("semo08marker511")).findFirst().orElseThrow();
		float[] c = sensor.volume.hull().hulls.getFirst().center();
		float[] wc = new float[3];
		devple.gow.tools.anim.Mat4.transformPoint(sensor.volume.world(), c[0], c[1], c[2], wc);
		float[] from = m.getFirst().pos.clone();
		w.moveOwnerBy("gopboxpand05b", wc[0] - from[0], wc[1] - from[1], wc[2] - from[2]);
		for (int i = 0; i < 90; i++) {
			w.tick(1 / 30f, 0, -1e6f, 0);
		}
		assertTrue(movies.contains("Semo08"), movies.toString());
	}

	@Test
	void elevatorPlatformMovesAndCarries() throws Exception {
		LevelData d = level("ATHN05D");
		ScriptWorld w = new ScriptWorld(d, new ScriptHost(), null);
		Platforms ps = Platforms.of(d, SceneNav.animatorOwners(w));
		Platforms.Body el = ps.body("gowarpelevator5d");
		assertNotNull(el);
		// 바인드 자세: 발판 윗면 ≈ 871
		Platforms.Support top = ps.floor(-944, 900, 1096, 100);
		assertNotNull(top);
		assertEquals(871, top.y(), 3);
		assertTrue(ps.play("gowarpelevator5d", "!0"));
		Vector3f rider = new Vector3f(-944, top.y(), 1096);
		for (float t = 0; t < 7.5f; t += 1 / 30f) {
			ps.update(1 / 30f);
			Platforms.Support s = ps.floor(rider.x, rider.y + 4, rider.z, 16);
			Platforms.carry(rider, s);
		}
		assertFalse(el.playing());
		assertEquals(677, rider.y, 4); // 애니 끝 = Warp 도착점 WarpFrom4A 높이
		Platforms.Support bottom = ps.floor(-944, 700, 1096, 100);
		assertEquals(677, bottom.y(), 4);
	}

	@Test
	void arrivalOnElevatorSettlesPose() throws Exception {
		LevelData d = level("ATHN05D");
		ScriptWorld w = new ScriptWorld(d, new ScriptHost(), null);
		Platforms ps = Platforms.of(d, SceneNav.animatorOwners(w));
		Platforms.Body b = ps.settleUnder(new Vector3f(-944, 677, 1098), 24);
		assertNotNull(b);
		assertEquals("gowarpelevator5d", b.owner);
		assertNotNull(ps.floor(-944, 690, 1098, 40));
	}

	/** ATHN01D 돛대 그물 (Climbable) 을 실제 충돌로 끝까지 오르면 돛대 꼭대기 발판 → 짚라인으로 ATHN01E 갑판 (38 문서 §6). */
	@Test
	void athn01dMastNetClimbThenZiplineToHydraDeck() throws Exception {
		LevelData d = level("ATHN01D");
		LevelCollision dc = new LevelCollision(d.sheets);
		LevelCollision ec = new LevelCollision(level("ATHN01E").sheets);
		NavWorld w = new NavWorld(dc, ec);
		w.ropes().addAll(NavRope.of(d));
		HeroNavigatorTest.Body b = new HeroNavigatorTest.Body();
		HeroNavigator nav = new HeroNavigator(b, w, null);
		HeroNavigatorTest.Pad in = new HeroNavigatorTest.Pad();
		// 1) 뒤쪽 그물 (z = -1896, 법선 -z, y 180..620): 바깥 공중에서 +z 쪽을 보고 붙어 오른다
		b.grounded = false;
		b.yaw = (float) Math.PI;
		b.pos.set(-970, 300, -1915);
		HeroNavigatorTest.tick(nav, b, w, in);
		assertEquals(NavState.WALL_CLIMB, nav.state(), "그물에 붙음 " + b.pos);
		for (int i = 0; i < 1200 && nav.state() == NavState.WALL_CLIMB; i++) {
			in.ly = 1;
			HeroNavigatorTest.tick(nav, b, w, in);
		}
		assertTrue(b.pos.y > 560, "그물 오르기 " + b.pos);
		// 2) 돛대 앞쪽 Climbable 띠 (z ≈ -1840, y 880..1100) → 꼭대기 발판 1120 에 올라섬
		nav = new HeroNavigator(b, w, null);
		b.grounded = false;
		b.yaw = 0;
		b.pos.set(-995, 950, -1822);
		in.ly = 0;
		HeroNavigatorTest.tick(nav, b, w, in);
		assertEquals(NavState.WALL_CLIMB, nav.state(), "돛대 띠에 붙음 " + b.pos);
		int i = 0;
		for (; i < 3000 && nav.state() == NavState.WALL_CLIMB; i++) {
			in.ly = 1;
			HeroNavigatorTest.tick(nav, b, w, in);
		}
		assertEquals(NavState.GROUND, nav.state(), "꼭대기 " + b.pos);
		assertEquals(1120, b.pos.y, 1, "돛대 꼭대기 발판 " + b.pos);
		// 짚라인: 돛대 꼭대기 발판 (줄 매단 점 바로 아래) 에서 점프 → 정점에서 손이 줄에 닿아 잡힘 → ATHN01E 갑판
		NavRope zip = w.ropes().getFirst();
		in.ly = 0;
		for (i = 0; i < 30; i++) {
			HeroNavigatorTest.tick(nav, b, w, in); // 올라선 뒤 재잡기 대기
		}
		b.pos.set(zip.top().x, 1120, zip.top().z);
		in.down.add(PadButton.CROSS);
		for (i = 0; i < 120 && nav.state() != NavState.ZIPLINE; i++) {
			HeroNavigatorTest.tick(nav, b, w, in);
		}
		assertEquals(NavState.ZIPLINE, nav.state(), "점프해서 짚라인 잡기 " + b.pos);
		for (i = 0; i < 3000 && !(nav.state() == NavState.AIR && b.grounded); i++) {
			HeroNavigatorTest.tick(nav, b, w, in);
		}
		assertTrue(b.grounded, "착지 " + b.pos);
		assertFalse(Float.isNaN(ec.groundHeight(b.pos.x, b.pos.y + 4, b.pos.z, 8)), "ATHN01E 바닥 위 " + b.pos);
		assertTrue(Float.isNaN(dc.groundHeight(b.pos.x, b.pos.y + 4, b.pos.z, 64)), "ATHN01D 바닥이 아님 → 게임 씬 WALK 전환");
	}
}
