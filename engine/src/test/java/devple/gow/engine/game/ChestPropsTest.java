package devple.gow.engine.game;

import devple.gow.engine.fx.EffectSystem;
import devple.gow.engine.nav.Interactables;
import devple.gow.engine.nav.Platforms;
import devple.gow.tools.content.Content;
import devple.gow.tools.content.Region;
import devple.gow.tools.fx.EffectLibrary;
import devple.gow.tools.level.GoInstance;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.wad.WadFile;
import org.joml.Vector3f;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 38 §3.5: Creator "chest" 의 몸 (R_PERMK 오브젝트 chest) — 종류별 관절 가시성 (0x215AF0), 오브 템플릿 (0x2161A8), 충돌·손잡이, act 진행.
 * 41 §2.1: ATHN01B 선창의 쓰러진 기둥·침상 (Breakable) 은 부서지기 전까지 몸을 막는다.
 */
class ChestPropsTest {

	static Path rom() {
		Path rom = Path.of(System.getProperty("gow.rom", "../gow-rom"));
		Assumptions.assumeTrue(Files.exists(Content.file(rom, "ATHN01B.WAD")), "gow-rom 없음");
		return rom;
	}

	@Test
	void typeTableAndCycle() {
		assertEquals(3, ChestProps.nextCycle(5), "0x215BB0: 처음 5 → 3");
		assertEquals(4, ChestProps.nextCycle(3));
		assertEquals(2, ChestProps.nextCycle(4));
		assertEquals(3, ChestProps.nextCycle(2));
	}

	@Test
	void athn01bHealthChest() throws Exception {
		Path rom = rom();
		WadFile perm = WadFile.load(Content.file(rom, Region.of(rom).perm()));
		LevelData lv = new LevelData(WadFile.load(Content.file(rom, "ATHN01B.WAD")));
		Tweaks tw = new Tweaks();
		tw.load(perm);
		tw.load(lv.wad);

		// 종류·오브: CSM 042 HydraHealth = 종류 2, OrbEmitter +0x90 = 014 "HealthChest"
		Interactables items = new Interactables();
		GoInstance owner = lv.layout.instances.stream().filter(g -> g.name().equalsIgnoreCase("gochest2")).findFirst().orElseThrow();
		float[] gp = owner.pos();
		Interactables.Item it = items.addChest(owner.name(), new Vector3f(gp[0], gp[1], gp[2]), 42, 1, tw);
		assertEquals(2, it.chestType);
		assertEquals(14, it.chestOrb());
		assertEquals("HealthChest", SceneNav.chestEmitter(it, tw));

		// 몸: R_PERMK gochest (모델·애니·CDV), 손잡이 handle1, 길이 = 상자 chestGrab 1.967 초
		EffectSystem fx = EffectSystem.headless();
		fx.add(perm);
		Platforms ps = new Platforms();
		ChestProps chests = new ChestProps();
		ChestProps.Prop p = chests.spawn(it, owner.matrix(), fx, ps, tw);
		assertNotNull(p, "R_PERMK 이펙트 GO chest");
		assertEquals(1, it.handles.size(), "handle1");
		assertEquals(1.967f, it.length, 0.01f);
		assertNotNull(ps.body(owner.name()), "CDV_chest 충돌");
		assertTrue(ps.collideSphere(new Vector3f(gp[0] + 3, gp[1] + 5, gp[2]), 8, Set.of()), "상자 몸이 막는다");

		// 관절 가시성: 종류 2 = health/health2 만 보임
		var sk = p.effect.def.skeleton();
		boolean[] m = p.effect.jointMask;
		for (int j = 0; j < sk.size(); j++) {
			String n = sk.joints.get(j).name();
			switch (n) {
				case "health", "health2" -> assertFalse(m[j], n);
				case "magic", "magic2", "power", "power2", "plain" -> assertTrue(m[j], n);
				default -> assertFalse(m[j], n + " (종류와 무관)");
			}
		}

		// act 진행: 대기 chestParts → 완료 chestOpen 재생
		chests.update(1 / 60f);
		assertEquals("chestParts", p.effect.actName());
		assertTrue(p.effect.hold);
		it.done = true;
		chests.update(1 / 60f);
		assertEquals("chestOpen", p.effect.actName());
		assertFalse(p.effect.hold);
	}

	/** 링크 번호 = 상태 디스크립터 번호 (40 §3): 상자 chestGrab 은 종류마다 첫 이미터 (sub 5), chestOpen 은 둘째·셋째에 곡선이 있다. */
	@Test
	void chestEmitterChannelsFollowDescriptorIndex() throws Exception {
		Path rom = rom();
		EffectLibrary lib = new EffectLibrary();
		lib.add(WadFile.load(Content.file(rom, Region.of(rom).perm())));
		EffectLibrary.EffectDef d = lib.resolve("chest");
		assertNotNull(d);
		var grab = d.act("chestGrab");
		var open = d.act("chestOpen");
		List<String> grabbing = new java.util.ArrayList<>(), opening = new java.util.ArrayList<>();
		for (EffectLibrary.EmitterDef e : d.emitters()) {
			float g = 0, o = 0;
			for (float t = 0; t <= grab.duration; t += 1 / 30f) {
				g = Math.max(g, d.rate(grab, e, t));
			}
			for (float t = 0; t <= open.duration; t += 1 / 30f) {
				o = Math.max(o, d.rate(open, e, t));
			}
			String n = e.fxc().name().replace("FXC_", "");
			if (g > 0) {
				grabbing.add(n);
			}
			if (o > 0) {
				opening.add(n);
			}
		}
		assertTrue(grabbing.containsAll(List.of("HSemit1", "MSemit1", "PSemit1")), "chestGrab: " + grabbing);
		assertTrue(opening.containsAll(List.of("HSemit2", "HSemit3", "MSemit2", "MSemit3")), "chestOpen: " + opening);
		assertFalse(opening.contains("HSemit1"), "chestOpen: " + opening);
	}

	@Test
	void athn01bBlockersSolidUntilBroken() throws Exception {
		Path rom = rom();
		LevelData lv = new LevelData(WadFile.load(Content.file(rom, "ATHN01B.WAD")));
		Tweaks tw = new Tweaks();
		tw.load(WadFile.load(Content.file(rom, Region.of(rom).perm())));
		tw.load(lv.wad);
		Breakables b = new Breakables(lv, tw);
		Breakables.Item posts = b.item("gofallenposts");
		assertNotNull(posts);
		assertEquals(25f, posts.hp, "Brkb 912 HydraBreakBig");
		assertEquals(1400, posts.center.z, 20, "판정 중심 = hull 중심");
		Platforms ps = Platforms.of(lv, Set.of(), Set.of());
		for (String o : List.of("gofallenposts", "gobreakablebunk01", "gobreakablebunk08")) {
			assertNotNull(ps.body(o), o + " 충돌");
		}
		// 영웅이 복도를 따라 (x 1621, −z 로) 걸으면 기둥 앞에서 막힌다 (기록: z 1468 에서 멈춤), 부서진 뒤에는 끝까지 지나간다
		assertTrue(sweepBlockedAt(ps) > 1420, "부서지기 전 = 막음");
		ps.setSolid("gofallenposts", false);
		assertTrue(Float.isNaN(sweepBlockedAt(ps)), "부서진 뒤 = 지나감");
	}

	/** 복도 (x 1621, 영웅 몸 구 = 발 + 16, 반지름 9.6) 를 z 1520 → 1300 으로 훑어 처음 밀려나는 z (없으면 NaN). */
	private static float sweepBlockedAt(Platforms ps) {
		for (float z = 1520; z > 1300; z -= 2) {
			Vector3f c = new Vector3f(1621, -138 + 16, z);
			if (ps.collideSphere(c, 9.6f, Set.of())) {
				return z;
			}
		}
		return Float.NaN;
	}
}
