package devple.gow.engine.game;

import devple.gow.engine.combat.AiData;
import devple.gow.engine.combat.DamageRules;
import devple.gow.engine.combat.GrowTimer;
import devple.gow.engine.script.ScriptHost;
import devple.gow.engine.script.ScriptListener;
import devple.gow.engine.script.ScriptWorld;
import devple.gow.tools.exe.Elf;
import devple.gow.tools.exe.R5900;
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
 * PAND02A 포세이돈의 시험 원형 경기장 — 케르베로스 (R_CEA) 의 토하기 · 새끼 (R_CEP) 가 자라 어른이 됨 · 깔려 물리기 (영상 3화 2255–2490, 43 §40).
 */
class CerberusArenaTest {

	private static final Path ROM = Path.of(System.getProperty("gow.rom", "../gow-rom"));

	private static WadFile wad(String name) throws Exception {
		Path p = ROM.resolve(name + ".WAD");
		Assumptions.assumeTrue(Files.exists(p), "gow-rom 없음");
		return WadFile.load(p);
	}

	private static Elf exe() throws Exception {
		Path p = ROM.toAbsolutePath().normalize().resolveSibling("work").resolve("SCKA_300.02");
		Assumptions.assumeTrue(Files.exists(p), "work/SCKA_300.02 없음");
		return Elf.load(p);
	}

	private static R5900.Insn at(Elf e, int pc) {
		return R5900.decode(e.u32(pc), pc);
	}

	/** GrowChar/085 "PuppyGrow" (R_CEP): 30 초 (+ 난수 × 0), 마지막 2 초 불꽃, 어른 배율 1.5, "GrowAdult", 액션 30 TRANSFORM, goCEPtranSpark. */
	@Test
	void puppyGrowTemplate() throws Exception {
		Tweaks tw = new Tweaks();
		tw.load(wad("R_CEP"));
		GrowTimer.Def d = GrowTimer.Def.of(tw, 85);
		assertNotNull(d);
		assertEquals(30f, d.time(), 1e-4);
		assertEquals(0f, d.timeRand(), 1e-4);
		assertEquals(0f, d.sparkBase(), 1e-4);
		assertEquals(2f, d.sparkLead(), 1e-4);
		assertEquals(1.5f, d.adultScale(), 1e-4);
		assertEquals("GrowAdult", d.entity());
		assertEquals(30, d.action());
		assertEquals("goCEPtranSpark", d.fx());

		GrowTimer g = new GrowTimer(d, 0.7f);
		int frames = 0;
		while (!g.tick()) {
			frames++;
			if (frames == 28 * 60) {
				assertTrue(g.spark() < 0, "28 초 (남은 2 초) 전에는 불꽃 없음");
			}
			if (frames == 29 * 60) {
				assertEquals(0.5f, g.spark(), 0.02f, "남은 1 초 = 세기 절반 (0 + (1 − 1/2) × 1)");
			}
		}
		assertEquals(30 * 60, frames, 2, "1/60 씩 30 초");
		assertEquals(GrowTimer.Phase.TRANSFORM, g.phase());
		assertEquals(1f, g.spark());
		assertEquals(1.75f, g.scale(0.5f), 1e-5);
		assertFalse(g.swap(0.95f), "0.95 초과일 때만 (c.lt.s 0.95, p)");
		assertTrue(g.swap(0.96f));
		assertEquals(GrowTimer.Phase.DONE, g.phase());
		assertFalse(g.swap(1f), "한 번만");
	}

	/** 원작 상수: 갱신 0x18F440 의 1/60 · 불꽃 앞당김 +0x24 · 변신 0x18F798 의 0.95 · 배율 +0x28 · 액션 +0x2C (u8) · 시작 0x190030 의 +0x18 + 난수 × +0x1C. */
	@Test
	void growTimerCode() throws Exception {
		Elf e = exe();
		assertTrue(at(e, 0x18f474).text().contains("0x24(a2)"), at(e, 0x18f474).text());
		assertEquals(0x3f73, Integer.decode(at(e, 0x18f9bc).ops().replaceAll(".*,\\s*", "")));
		assertTrue(at(e, 0x18f9fc).text().contains("0x28(s0)"), at(e, 0x18f9fc).text());
		assertTrue(at(e, 0x18ffd8).text().contains("0x2c(v0)"), at(e, 0x18ffd8).text());
		assertEquals(0x209378, at(e, 0x18ffd4).target(), "Combat_RequestAction");
		assertEquals(0x209918, at(e, 0x18ffc4).target(), "Combat_EndMove");
		assertTrue(at(e, 0x190198).text().contains("0x1c(v1)"), at(e, 0x190198).text());
		assertTrue(at(e, 0x19019c).text().contains("0x18(v1)"), at(e, 0x19019c).text());
		// 변신 끝: 0x1A90D0 (정보) → 0x1DFFA8 (새끼 지움) → 0x1A9298 (어른)
		assertEquals(0x1a90d0, at(e, 0x18fa58).target());
		assertEquals(0x1dffa8, at(e, 0x18fad0).target());
		assertEquals(0x1a9298, at(e, 0x18fb70).target());
	}

	/** 케르베로스 DTre 081: 토하기 VOMIT 4 는 조건 25 (인자 81 = Spawn/081 "VomitSpawnTransform") 가 참일 때만. DTre 080 · 080 템플릿 엔티티는 이 레벨에 없다. */
	@Test
	void vomitNeedsSpawnerCondition() throws Exception {
		WadFile cea = wad("R_CEA");
		AiData ai = AiData.load(cea);
		String t = ai.trees().get(81).toString();
		assertTrue(t.contains("c25"), t);
		boolean vomitLeaf = ai.trees().get(81).leaves().stream().anyMatch(l -> l.attacks().stream().anyMatch(a -> a.move() == 4));
		assertTrue(vomitLeaf);
		assertFalse(ai.trees().get(80).leaves().stream().anyMatch(l -> l.attacks().stream().anyMatch(a -> a.move() == 4)), "DTre 080 은 토하지 않음");
		Tweaks tw = new Tweaks();
		tw.load(cea);
		String n = tw.findNode("/TweakTemplates/Spawn/081/");
		assertEquals("VomitSpawnTransform", tw.s(n, "Entity Name", null));
		assertEquals("synchJoint", tw.s(n, "Joint Name", null));
		assertEquals("VomitSpawn", tw.s(tw.findNode("/TweakTemplates/Spawn/080/"), "Entity Name", null));
	}

	/**
	 * 기술 SCRIPT 생성 (0x1A9298 에 정보를 넘김): MaximumCount 검사 · 만든 수 증가 없이 Action 을 돈다 — VomitSpawnTransform (새끼 86, 생성 기술 502,
	 * Filter 2, LevelData4 + 1), GrowAdult (어른 81, 생성 기술 10, Filter 3, Action 없음). 조건 25 는 만든 수 &lt; MaximumCount.
	 */
	@Test
	void scriptSpawnAtMatrix() throws Exception {
		LevelData lv = new LevelData(wad("PAND02A"));
		List<ScriptListener.SpawnRequest> spawns = new ArrayList<>();
		ScriptWorld w = new ScriptWorld(lv, new ScriptHost(), new ScriptListener() {
			@Override
			public void spawnEnemy(SpawnRequest r) {
				spawns.add(r);
			}
		});
		w.start();
		w.update(1 / 60f);
		assertTrue(w.canSpawn("VomitSpawnTransform"), "MaximumCount 100, 만든 수 0");
		assertFalse(w.canSpawn("VomitSpawn"), "PAND02A 에 없는 엔티티");
		assertTrue(w.canSpawn("GrowAdult"), "MaximumCount 6");
		Object before = w.memory().levelData().get(4);
		float[] m = new org.joml.Matrix4f().translation(-100, -384, -300).get(new float[16]);
		for (int i = 0; i < 7; i++) {
			assertNotNull(w.spawnAt("GrowAdult", m), "MaximumCount 6 을 넘어도 (0x1A9298 정보 경로)");
		}
		assertTrue(w.canSpawn("GrowAdult"), "만든 수가 늘지 않음");
		assertEquals(7, spawns.size());
		ScriptListener.SpawnRequest a = spawns.getFirst();
		assertEquals(81, a.templateId());
		assertEquals(10, a.spawnAction());
		assertEquals(3, a.actor().group);
		assertEquals(-100f, a.world()[12], 1e-3);
		assertEquals(before, w.memory().levelData().get(4), "GrowAdult 에는 Action 이 없다");
		ScriptListener.SpawnRequest p = spawns.get(spawns.size() - 1);
		assertNotNull(w.spawnAt("VomitSpawnTransform", m));
		p = spawns.getLast();
		assertEquals(86, p.templateId());
		assertEquals(502, p.spawnAction());
		assertEquals(2, p.actor().group);
		assertEquals(((Number) before).intValue() + 1, ((Number) w.memory().levelData().get(4)).intValue(), "Action: LevelData4 + 1");
		assertNull(w.spawnAt("VomitSpawn", m));
	}

	/**
	 * 영웅 SELF_HP (0x2069AC → 0x204690 플래그 7): 바닥 무시 피해 b 그대로, HP 0 이면 그대로, 음수 = 회복 (최대까지). 케르베로스 깔려 물리기
	 * MAICEALEAPBITE 381 은 10 × 2, HP ≤ 10 이면 382 MAICEALEAPDEATH (SELF_HP 200), 흔들기 게이지 &gt; 4 면 383 탈출.
	 */
	@Test
	void heroSelfHp() throws Exception {
		assertEquals(80f, DamageRules.heroSelfHp(100, 100, 20));
		assertEquals(0f, DamageRules.heroSelfHp(5, 100, 10));
		assertEquals(0f, DamageRules.heroSelfHp(0, 100, 10));
		assertEquals(100f, DamageRules.heroSelfHp(95, 100, -15));
		Elf e = exe();
		assertEquals(0x204690, at(e, 0x207084).target());
		assertEquals("li", at(e, 0x207088).mnem());
		assertTrue(at(e, 0x207088).text().endsWith("0x7"), at(e, 0x207088).text());
		// SCR_FlashSprite 0x19CD88: 아이콘 슬롯 4 = idx (0x21DA50(4, +0x68))
		assertEquals(0x21da50, at(e, 0x19cdb4).target());
		assertTrue(at(e, 0x19cdb8).text().endsWith("0x4"), at(e, 0x19cdb8).text());
	}
}
