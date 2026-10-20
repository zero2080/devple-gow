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

	/** 내비게이션 수치 = 크라토스 TWK_Sold_000 필드, 중력 = /GlobGame/ Gravity (38 문서 §2.2). */
	@Test
	void heroNavigationTuningIsSold000() throws Exception {
		WadFile perm = WadFile.load(rom().resolve("R_PERMK.WAD"));
		NavTuning sold = NavTuning.fromWad(perm, 0);
		assertNotNull(sold, "R_PERMK TWK_Sold_000");
		for (int off : NavTuning.KRATOS.offsets()) {
			assertEquals(NavTuning.KRATOS.get(off), sold.get(off), 1e-3, String.format("Sold_000 +0x%X", off));
		}
		Tweaks tw = new Tweaks();
		tw.load(perm);
		assertEquals(NavTuning.GLOBAL_GRAVITY, tw.f("/GlobGame/", "Gravity", 0), 1e-4, "/GlobGame/ Gravity");
		// 유닛 환산: 점프 244 → 정점 ≈ 37 (키 35.2 바로 위), 줄 손 = 키 35.2, 짚라인 320, 벽 32/24, 사다리 32, 수영 120
		assertEquals(244f, HeroNavigator.JUMP, 0.01f);
		assertEquals(37.2f, HeroNavigator.JUMP * HeroNavigator.JUMP / (2 * HeroNavigator.GRAVITY), 0.1f);
		assertEquals(35.2f, HeroNavigator.ROPE_HAND, 1e-3);
		assertEquals(320f, HeroNavigator.ZIP_SPEED, 1e-3);
		assertEquals(32f, HeroNavigator.CLIMB_SPEED, 1e-3);
		assertEquals(24f, HeroNavigator.CLIMB_SIDE, 1e-3);
		assertEquals(32f, HeroNavigator.LADDER_SPEED, 1e-3);
		assertEquals(120f, HeroNavigator.SWIM_SPEED, 1e-3);
		// 적 Sold 도 같은 배치: 해골 -C Sold_020 은 수평 줄 2 m/s, 벽 상하 3.7
		NavTuning sk = NavTuning.fromWad(WadFile.load(rom().resolve("R_SKS.WAD")), 20);
		assertNotNull(sk, "R_SKS TWK_Sold_020");
		assertEquals(2f, sk.get(NavTuning.TRAVERSE), 1e-3);
		assertEquals(3.7f, sk.get(NavTuning.WALL_UP), 1e-2);
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

	/**
	 * 밀기 상자 무게 등급 = Push 템플릿 "Weight" 하위 16 비트 (float 0.5 의 비트에 얹힘), 상자 최대 속도 #95b8bfb6 (38 문서 §3.2).
	 * WallHangTimer (표면 +0x1C) 분포 — 매달리기 제한 시간 (0 = 무제한).
	 */
	@Test
	void pushWeightsAndWallHangTimers() throws Exception {
		LevelData p = level("PAND05B");
		Tweaks pt = new Tweaks();
		pt.load(p.wad);
		Interactables.Item box = Interactables.of(p, pt).byOwner("gopboxpand05b");
		assertEquals(2, box.weight, "Pandora's Box Weight 0x3F000002 → 2 (1.2 m/s)");
		assertEquals(5f, box.slideMax, 1e-4f);
		LevelData h = level("ATHN01D");
		Tweaks ht = new Tweaks();
		ht.load(h.wad);
		assertEquals("HydraPushBox", ht.s("/TweakTemplates/Push/030/", "Instance Name", null));
		assertEquals(0x3F000001, ht.raw("/TweakTemplates/Push/030/", "Weight").getAsInt(), "HydraPushBox 무게 1 (1.5 m/s)");
		java.util.Map<Float, Integer> timers = new java.util.TreeMap<>();
		try (var ds = Files.newDirectoryStream(rom(), "*.WAD")) {
			for (Path wp : ds) {
				if (wp.getFileName().toString().startsWith("R_")) {
					continue;
				}
				for (CollisionSheet s : new LevelData(WadFile.load(wp)).sheets) {
					for (var x : s.surfaces) {
						if (x != null) {
							timers.merge(x.wallHangTimer(), 1, Integer::sum);
						}
					}
				}
			}
		}
		// 전 레벨 표면 레코드의 WallHangTimer 는 모두 0 (스키마 기본 60 은 쓰이지 않음) → 0x1C772C 에서 FLT_MAX = 시간 제한 없음
		assertEquals(java.util.Set.of(0f), timers.keySet(), "WallHangTimer 분포 " + timers);
		assertTrue(timers.get(0f) > 1000);
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

	/**
	 * 붙음 유지 규칙의 데이터 (38 문서 §3.4): CSM 비트 #fb908478 (문 0 / 상자 1), ANICSM2, 크랭크 Push Type·Acc/Dec, TAndF 속도 키.
	 * IO_CSM 은 영웅 ANICSM1 길이 = 오브젝트 애니 길이라서 R1 한 번으로 끝까지 붙어 있으면 100 % 가 된다.
	 */
	@Test
	void attachRulesFromTemplates() throws Exception {
		LevelData d = level("ATHN01D");
		Tweaks tw = new Tweaks();
		tw.load(d.wad);
		Interactables it = Interactables.of(d, tw);
		Interactables.Item door = it.byOwner("gotwinkickdoor");
		Interactables.Item cap = it.byOwner("gonewcaptainunlock");
		assertFalse(door.hold, "쌍발차기 문 = R1 한 번");
		assertFalse(cap.hold);
		assertNull(door.finishAnim, "CSM 44 에 ANICSM2 없음");
		assertTrue(it.addChest("c", new Vector3f(), 42, 1, tw).hold, "CSM 42 HydraHealth = 누르고 있기");
		devple.gow.tools.anim.AnimationSet hero = devple.gow.tools.anim.AnimationSet.load(WadFile.load(rom().resolve("R_HERO0.WAD")), "ANM_hero");
		hero.attachAll(d.wad);
		assertEquals(door.length, hero.find("doorKick").duration, 0.01f, "영웅 doorKick = 문 애니 0.9 초");
		assertEquals(cap.length, hero.find("captainsDoorOpen").duration, 0.01f, "captainsDoorOpen 4.83 초");
		LevelData e = level("ATHN01E");
		Tweaks et = new Tweaks();
		et.load(e.wad);
		assertEquals("captainHangExit", Interactables.of(e, et).byOwner("goxfccaptaincsm").finishAnim);
		LevelData c = level("PAND02C");
		Tweaks ct = new Tweaks();
		ct.load(c.wad);
		Interactables pc = Interactables.of(c, ct);
		Interactables.Item crank = pc.byOwner("gocagetriggerhandle");
		assertEquals(Interactables.Kind.LEVER, crank.kind);
		assertTrue(crank.crank, "Push 13 Type 1 = 크랭크");
		assertEquals(3f, crank.acc, 1e-3f);
		assertEquals(1f, crank.dec, 1e-3f);
		assertEquals(1.5f, crank.maxSpeed, 1e-3f);
		assertEquals(20f, crank.rewindAcc, 1e-3f);
		assertEquals(15f, crank.maxBack, 1e-3f);
		assertFalse(pc.byOwner("golever").crank, "Push 12 newLeever Type 5 = 레버");
		LevelData u = level("PAND03A");
		Tweaks ut = new Tweaks();
		ut.load(u.wad);
		assertEquals("leverPullUW", Interactables.of(u, ut).byOwner("gouwlever").heroAnim, "Type 6 = 물속 레버");
		LevelData l = level("ATHN01C");
		Tweaks lt = new Tweaks();
		lt.load(l.wad);
		Interactables.Item lift = Interactables.of(l, lt).byOwner("goliftdoor1");
		assertEquals(1f, lift.acc, 1e-3f);
		assertEquals(20f, lift.dec, 1e-3f);
		assertEquals(0.25f, lift.tfTime, 1e-3f);
		assertEquals(1.5f, lift.maxSpeed, 1e-3f);
		assertEquals(20f, lift.maxBack, 1e-3f);
		assertNotNull(lift.startAnim, "ANITandF1");
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

	/**
	 * 블레이드 벽 질의의 CDV GO 몸체 (45 §3.8): ATHN05D 엘리베이터 (gowarpelevator5d) 바닥 hull 을 위에서 아래로 지나는 선분이 윗면 (677) 에 맞고,
	 * 엔트리는 GO 몸체 (정적 시트가 아님 → 첫 질의 bit 31 아님, 둘째 질의 0x80000000 이 같은 몸체를 맞혀 당김).
	 */
	@Test
	void bladeQueryHitsCdvBody() throws Exception {
		LevelData d = level("ATHN05D");
		ScriptWorld w = new ScriptWorld(d, new ScriptHost(), null);
		Platforms ps = Platforms.of(d, SceneNav.animatorOwners(w));
		assertNotNull(ps.settleUnder(new Vector3f(-944, 677, 1098), 24));
		assertEquals(677, ps.floor(-944, 690, 1098, 40).y(), 4);
		NavWorld nw = new NavWorld(LevelCollision.of(List.of()));
		nw.setPlatforms(ps);
		Vector3f from = new Vector3f(-944, 720, 1098), to = new Vector3f(-944, 600, 1098);
		NavWorld.Contact c = nw.bodyContact(from, to, devple.gow.engine.game.BladeChain.WALL_EXCLUDE);
		assertNotNull(c);
		assertEquals("gowarpelevator5d", c.owner());
		assertEquals(677, c.point().y, 4);
		assertTrue(Math.abs(c.normal().y) > 0.9f, "윗면 법선 " + c.normal());
		assertNull(nw.staticContact(from, to, devple.gow.engine.game.BladeChain.WALL_EXCLUDE));
		devple.gow.engine.game.BladeWalls bw = new devple.gow.engine.game.BladeWalls(() -> nw, List::of);
		devple.gow.engine.game.BladeChain.WallHit h = bw.cast(from, to, devple.gow.engine.game.BladeChain.WALL_EXCLUDE,
				devple.gow.engine.game.BladeChain.TYPES_WALL);
		assertNotNull(h);
		assertFalse(h.bit31(), "GO +0xF4 = 0 → 첫 질의는 불꽃만");
		devple.gow.engine.game.BladeChain.WallHit h2 = bw.cast(from, to, devple.gow.engine.game.BladeChain.WALL_EXCLUDE,
				devple.gow.engine.game.BladeChain.TYPES_WORLD);
		assertEquals(h.point().y, h2.point().y, 1e-4f, "둘째 질의가 같은 몸체 → 당김");
		assertNull(bw.cast(from, to, devple.gow.engine.game.BladeChain.WALL_EXCLUDE, devple.gow.engine.game.BladeChain.CAT_CREATURE));
		ps.setSolid("gowarpelevator5d", false);
		assertNull(nw.bodyContact(from, to, devple.gow.engine.game.BladeChain.WALL_EXCLUDE), "충돌 끈 GO 는 빠짐");
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
		b.pos.set(-970, 300, -1908); // 벽 질의 = 정면 14.4 (게임 몸 구 12 만큼 앞)
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
		b.pos.set(-995, 950, -1828);
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

	/** 물·벽 따라 걷기 애니 = R_HERO0 TWK /Animation/goHero/Navigation/ 표 (0x26–0x2E, 0x35–0x3A) [확실]. */
	@Test
	void waterAndWallWalkAnimsFromNavigationTable() throws Exception {
		Tweaks t = new Tweaks();
		t.load(WadFile.load(rom().resolve("R_HERO0.WAD")));
		NavAnims def = new NavAnims();
		for (String k : List.of("ANITread", "ANITreadSwim", "ANIDiveDashC", "ANIDiveDash", "ANIDive1", "ANIDive2", "ANISwim", "ANISwimIdle",
				"ANIJump", "ANIVJump", "ANIWallFront", "ANIWallFrontR", "ANIWallFrontL", "ANIWallBack", "ANIWallBackR", "ANIWallBackL",
				"ANIClimbAL", "ANIClimbAR", "ANIClimbCL", "ANIClimbCR")) {
			assertEquals(t.s(NavAnims.NODE, k, null), def.get(k), k);
		}
	}

	/**
	 * 원작 충돌 위 물 (38 §11): ATHN02A 수로 (Water 면 y −687.4, 바닥 −800) 에 떨어지면 SINK → TREAD (발 = 수면 − 28.16),
	 * □ 잠수로 바닥까지, △ 로 올라와 수면에서 물 밖 도약.
	 */
	@Test
	void athn02aCanalSwimDiveAndLeap() throws Exception {
		NavWorld w = new NavWorld(new LevelCollision(level("ATHN02A").sheets));
		HeroNavigatorTest.Body b = new HeroNavigatorTest.Body();
		HeroNavigator nav = new HeroNavigator(b, w, null);
		HeroNavigatorTest.Pad in = new HeroNavigatorTest.Pad();
		// 수로 안 열린 물: 가장 높은 바닥이 깊은 Water 면인 첫 격자 점
		Vector3f start = null;
		for (int x = -480; x <= 752 && start == null; x += 16) {
			for (int z = 2400; z <= 4320 && start == null; z += 16) {
				NavWorld.Floor f = w.floor(x, -600, z, 200);
				NavWorld.Floor bed = w.floor(x, -690, z, 200);
				if (f != null && f.has(SurfaceAttr.WATER) && bed != null && bed.y() < f.y() - 100) {
					start = new Vector3f(x, -600, z);
				}
			}
		}
		assertNotNull(start, "ATHN02A 열린 물");
		b.grounded = false;
		b.pos.set(start);
		for (int i = 0; i < 120 && nav.state() != NavState.SWIM; i++) {
			HeroNavigatorTest.tick(nav, b, w, in);
		}
		assertEquals(NavState.SWIM, nav.state(), "수로에 빠짐 " + b.pos);
		float surface = nav.waterSurface();
		assertEquals(-687.43f, surface, 0.05f);
		for (int i = 0; i < 240 && nav.water() != HeroNavigator.Water.TREAD; i++) {
			HeroNavigatorTest.tick(nav, b, w, in);
		}
		assertEquals(HeroNavigator.Water.TREAD, nav.water());
		assertEquals(surface - HeroNavigator.SWIM_DEPTH, b.pos.y, 1e-3f);
		for (int i = 0; i < 180; i++) {
			in.down.add(PadButton.SQUARE);
			HeroNavigatorTest.tick(nav, b, w, in);
		}
		assertEquals(HeroNavigator.Water.DIVE, nav.water());
		NavWorld.Floor bed = w.floor(b.pos.x, surface - 30, b.pos.z, 400);
		assertEquals(bed.y(), b.pos.y, 0.5f, "수로 바닥까지 잠수 " + b.pos);
		for (int i = 0; i < 300 && nav.state() == NavState.SWIM; i++) {
			in.down.add(PadButton.TRIANGLE);
			HeroNavigatorTest.tick(nav, b, w, in);
		}
		assertEquals(NavState.AIR, nav.state(), "물 밖 도약");
		assertEquals("swimdive", b.anim);
	}

	/**
	 * 원작 좁은 턱 벽 (38 §12): PAND02B 의 Narrow 벽 (높이 128–288) 앞 바닥에서 벽 쪽으로 걸으면 벽 보고 걷기, 오른쪽으로 0.5 초 이동.
	 * PAND00C 의 BackPress 벽은 등 대고 걷기. ATHN06B 의 Narrow 는 높이 16 평균대 옆면이라 머리 선분이 빗나가 붙지 않는다 (균형 걷기 바닥).
	 */
	@Test
	void narrowLedgeWallsInLevels() throws Exception {
		int[] front = wallWalkSpots("PAND02B", false);
		assertEquals(18, front[0], "PAND02B Narrow 벽에 붙는 자리");
		assertTrue(front[1] >= 12, "옆으로 걸음 " + front[1]);
		int[] back = wallWalkSpots("PAND00C", true);
		assertEquals(6, back[1], "PAND00C BackPress 6 면");
		assertEquals(0, wallWalkSpots("ATHN06B", false)[0], "평균대 옆면은 벽 걷기 아님");
	}

	/**
	 * 좁은 턱 윗면 (Narrow·NarrowNoBB 바닥) 에 공중에서 매달리기 = 0x1E3EE8 경로 (38 §18.1). 윗면 삼각형마다 수평 4 방향으로 떨어져
	 * 손 높이를 지나며 턱 질의 — 잡힌 윗면 수 / 전체 (발 = 턱 − 35.2).
	 */
	@Test
	void narrowLedgeTopsCatchFallingHero() throws Exception {
		int[] a06 = narrowTopCatches("ATHN06B");
		int[] p3e = narrowTopCatches("PAND03E");
		int[] ar8 = narrowTopCatches("ARENA08");
		System.out.printf("좁은 턱 윗면 잡힘: ATHN06B %d/%d, PAND03E %d/%d, ARENA08 %d/%d%n", a06[0], a06[1], p3e[0], p3e[1], ar8[0], ar8[1]);
		assertArrayEquals(new int[] {88, 88}, a06, "ATHN06B 평균대 윗면 (Narrow) 모두");
		assertArrayEquals(new int[] {59, 59}, p3e, "PAND03E 평균대 윗면 (Narrow) 모두");
		assertEquals(160, ar8[0], "ARENA08 NarrowNoBB 기둥 윗면 (가장자리에 닿는 삼각형)");
	}

	private static int[] narrowTopCatches(String lv) throws Exception {
		LevelCollision c = new LevelCollision(level(lv).sheets);
		NavWorld w = new NavWorld(c);
		HeroNavigator nav = new HeroNavigator(new HeroNavigatorTest.Body(), w, null);
		int caught = 0, total = 0;
		for (LevelCollision.Tri t : c.triangles()) {
			if (t.n().y < 0.85f || !HeroNavigator.narrowLedge(t) || !t.solidForPlayer()) {
				continue;
			}
			total++;
			Vector3f ctr = new Vector3f(t.a()).add(t.b()).add(t.c()).div(3);
			search:
			for (int k = 0; k < 4; k++) {
				Vector3f d = new Vector3f((float) Math.sin(k * Math.PI / 2), 0, (float) Math.cos(k * Math.PI / 2));
				for (float r = 6; r <= 40; r += 2) {
					Vector3f feet = new Vector3f(ctr).fma(r, d).sub(0, HeroNavigator.HANG + 0.6f, 0);
					HeroNavigator.Ledge l = nav.probeLedge(feet, new Vector3f(feet).add(0, 0.5f, 0), new Vector3f(d).negate(), null);
					if (l != null && HeroNavigator.narrowLedge(l.top())) {
						assertEquals(l.y() - HeroNavigator.HANG, l.feet().y, 1e-3f);
						caught++;
						break search;
					}
				}
			}
		}
		return new int[] {caught, total};
	}

	/** 레벨의 좁은 턱 벽 면마다 법선 쪽 12 앞 바닥에서 벽 쪽으로 걸어 본다: {붙음, 붙고 0.5 초 옆으로 걸음}. */
	private static int[] wallWalkSpots(String lv, boolean back) throws Exception {
		LevelCollision c = new LevelCollision(level(lv).sheets);
		NavWorld w = new NavWorld(c);
		int entered = 0, walked = 0;
		for (LevelCollision.Tri t : c.triangles()) {
			if (t.floor() || !HeroNavigator.narrowSurface(t)) {
				continue;
			}
			Vector3f ctr = new Vector3f(t.a()).add(t.b()).add(t.c()).div(3);
			Vector3f n = new Vector3f(t.n().x, 0, t.n().z).normalize();
			for (int sgn = -1; sgn <= 1; sgn += 2) {
				Vector3f st = new Vector3f(ctr).fma(sgn * 12f, n);
				NavWorld.Floor f = w.floor(st.x, ctr.y + 4, st.z, 80);
				if (f == null) {
					continue;
				}
				HeroNavigatorTest.Body b = new HeroNavigatorTest.Body();
				b.pos.set(st.x, f.y(), st.z);
				HeroNavigator nav = new HeroNavigator(b, w, null);
				HeroNavigatorTest.Pad in = new HeroNavigatorTest.Pad();
				Vector3f dir = new Vector3f(n).mul(-sgn);
				float yaw = HeroNavigator.yawOf(dir);
				in.lx = (float) -Math.sin(yaw);
				in.ly = (float) Math.cos(yaw);
				nav.update(in, 0, HeroNavigatorTest.DT);
				if (nav.state() != NavState.WALL_WALK) {
					continue;
				}
				assertEquals(back, nav.wallBack(), lv + " " + b.pos);
				entered++;
				Vector3f p0 = new Vector3f(b.pos);
				float ry = HeroNavigator.yawOf(new Vector3f(dir).cross(0, 1, 0));
				in.lx = (float) -Math.sin(ry);
				in.ly = (float) Math.cos(ry);
				for (int i = 0; i < 30 && nav.state() == NavState.WALL_WALK; i++) {
					nav.update(in, 0, HeroNavigatorTest.DT);
				}
				if (nav.state() == NavState.WALL_WALK && b.pos.distance(p0) > 9) {
					walked++;
				}
			}
		}
		return new int[] {entered, walked};
	}

	/**
	 * 줄 SCR +0x28 = 길이 (미터, 0x2170C0 → +0x60) — 전 레벨 매단 줄 관절 길이와 같음. 방향 제한 = 템플릿 #b38919fb (0x216AB8):
	 * RopeNorthSouth (4) = 1 (±z), RopeEastWest (2) = 2 (±(z × 위)), Rope (0) = 0 (4 방향) (38 문서 §15.2).
	 */
	@Test
	void ropeLengthFieldAndJumpDirections() throws Exception {
		NavRope a = byOwner(NavRope.of(level("ATHN03C")), "gorope");
		assertEquals(RopeDef.Type.HANG, a.type());
		assertEquals(15f, a.lengthM(), 1e-4f);
		assertEquals(a.length(), a.simLength(), 0.5f, "SCR +0x28 × 16 = 관절 길이");
		assertEquals(0, a.dirs());
		NavRope ns = byOwner(NavRope.of(level("ATHN03C")), "gorope12");
		assertEquals(4, ns.template());
		assertEquals(1, ns.dirs(), "RopeNorthSouth");
		assertEquals(160f, ns.simLength(), 1e-3f);
		for (NavRope r : NavRope.of(level("ATHN06C"))) {
			assertEquals(2, r.template());
			assertEquals(2, r.dirs(), "RopeEastWest " + r.owner());
			assertEquals(1, r.dirFirst());
			assertEquals(2, r.dirStep());
		}
		for (NavRope r : NavRope.of(level("PAND04E"))) {
			if (r.type() == RopeDef.Type.HANG) {
				assertEquals(1, r.dirs(), r.owner());
			}
		}
		// 줄 오브젝트 행렬 z 축 (원작 줄 +0x20): HDES01E gorope3 는 45° 돌아 있음
		NavRope h = byOwner(NavRope.of(level("HDES01E")), "gorope3");
		assertEquals(Math.abs(h.axis().x), Math.abs(h.axis().z), 0.02f);
		assertTrue(Math.abs(h.axis().x) > 0.6f);
	}

	static NavRope byOwner(List<NavRope> l, String owner) {
		return l.stream().filter(r -> r.owner().equals(owner)).findFirst().orElseThrow();
	}

	/**
	 * 위에서 아래 사다리로 (0x1E5638): ATHN02B 사다리 면 꼭대기 (위층 바닥이 있는 것) 모두 위층에서 바깥쪽으로 걸어가면 사다리에 붙는다 (LADDER, 7 곳).
	 */
	@Test
	void athn02bLaddersMountFromAbove() throws Exception {
		LevelData d = level("ATHN02B");
		LevelCollision lc = new LevelCollision(d.sheets);
		NavWorld w = new NavWorld(lc);
		int tops = 0, mounted = 0;
		for (LevelCollision.Tri t : lc.triangles()) {
			if (!t.has(SurfaceAttr.LADDER) || Math.abs(t.n().y) > 0.3f) {
				continue;
			}
			Vector3f n = new Vector3f(t.n().x, 0, t.n().z).normalize();
			Vector3f top = t.a().y >= t.b().y && t.a().y >= t.c().y ? t.a() : t.b().y >= t.c().y ? t.b() : t.c();
			// 꼭대기 꼭짓점 → 사다리 면 가운데 쪽으로 (옆 끝 꼭짓점 대신)
			Vector3f mid = new Vector3f(t.a()).add(t.b()).add(t.c()).div(3);
			Vector3f along = new Vector3f(mid.x - top.x, 0, mid.z - top.z);
			Vector3f start = new Vector3f(top).add(along);
			NavWorld.Floor fl = w.floor(start.x - n.x * 4, top.y + 8, start.z - n.z * 4, 12);
			if (fl == null) {
				continue;
			}
			tops++;
			HeroNavigatorTest.Body b = new HeroNavigatorTest.Body();
			HeroNavigator nav = new HeroNavigator(b, w, null);
			b.pos.set(start.x - n.x * 20, fl.y(), start.z - n.z * 20);
			for (int i = 0; i < 90 && nav.state() == NavState.GROUND; i++) {
				// 스틱 = 바깥 (법선) 쪽: camYaw 로 맞춤
				float yaw = HeroNavigator.yawOf(n);
				HeroNavigatorTest.Pad in = new HeroNavigatorTest.Pad();
				in.ly = 1;
				if (!nav.update(in, yaw, HeroNavigatorTest.DT) && !nav.active()) {
					b.yaw = yaw;
					b.pos.add(HeroNavigator.forward(yaw).mul(60 * HeroNavigatorTest.DT));
					float g = w.groundHeight(b.pos.x, b.pos.y + 14, b.pos.z, 16);
					if (Float.isNaN(g)) {
						break;
					}
					b.pos.y = g;
				}
			}
			if (nav.state() == NavState.LADDER) {
				mounted++;
			}
		}
		assertTrue(tops >= 5, "ATHN02B 사다리 꼭대기 " + tops);
		assertEquals(tops, mounted, "위에서 사다리로 붙음 " + mounted + " / " + tops);
	}

	/** 물가 (0x1CA198): PAND04E 의 수면 아래 얕은 턱 (떠 있는 발 위 6.4 안) 으로 헤엄쳐 가면 올라선다. ATHN02A 수로 벽은 못 오른다 (사다리·점프로 나감). */
	@Test
	void pand04eShelfExitsWater() throws Exception {
		NavWorld w = new NavWorld(new LevelCollision(level("PAND04E").sheets));
		Vector3f start = null, toward = null;
		float surface = 0;
		search:
		for (float x = -4000; x <= 4000; x += 16) {
			for (float z = -4000; z <= 4000; z += 16) {
				NavWorld.Floor wf = w.floor(x, 3000, z, 6000);
				if (wf == null || !wf.has(SurfaceAttr.WATER)) {
					continue;
				}
				NavWorld.Floor bed = w.floor(x, wf.y() - 0.5f, z, HeroNavigator.SWIM_DEPTH + 16);
				if (bed != null && !bed.has(SurfaceAttr.WATER)) {
					continue;
				}
				float feet = wf.y() - HeroNavigator.SWIM_DEPTH;
				for (int d = 0; d < 4; d++) {
					float nx = x + (d == 0 ? 16 : d == 1 ? -16 : 0), nz = z + (d == 2 ? 16 : d == 3 ? -16 : 0);
					NavWorld.Floor a = w.floor(nx, feet + HeroNavigator.FOOT, nz, HeroNavigator.FOOT + 3.2f);
					if (a != null && !a.has(SurfaceAttr.WATER) && a.y() > feet) {
						start = new Vector3f(x, wf.y() + 4, z);
						toward = new Vector3f(nx - x, 0, nz - z).normalize();
						surface = wf.y();
						break search;
					}
				}
			}
		}
		assertNotNull(start, "PAND04E 물속 턱");
		HeroNavigatorTest.Body b = new HeroNavigatorTest.Body();
		HeroNavigator nav = new HeroNavigator(b, w, null);
		b.grounded = false;
		b.pos.set(start);
		HeroNavigatorTest.Pad in = new HeroNavigatorTest.Pad();
		float yaw = HeroNavigator.yawOf(toward);
		for (int i = 0; i < 300 && nav.state() != NavState.GROUND; i++) {
			in.ly = nav.state() == NavState.SWIM ? 1 : 0;
			if (!nav.update(in, yaw, HeroNavigatorTest.DT) && !nav.active()) {
				b.vy -= HeroNavigator.GRAVITY * HeroNavigatorTest.DT;
				b.pos.y += b.vy * HeroNavigatorTest.DT;
			}
		}
		assertEquals(NavState.GROUND, nav.state(), "턱으로 올라섬 " + b.pos);
		assertTrue(b.pos.y > surface - HeroNavigator.SWIM_DEPTH, "바닥 > 수면 − 28.16");
	}
}
