package devple.gow.engine.combat;

import devple.gow.tools.content.Content;
import devple.gow.tools.content.Region;
import devple.gow.tools.wad.WadFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 적 WAD 자동 구성이 10 문서 적 표(AI1 ↔ CombatFileSet)와 일치하는지. GL 없이 데이터만 검사. */
class EnemyFactoryTest {

	private static EnemyFactory.Kind load(String wad) throws Exception {
		Path rom = Path.of(System.getProperty("gow.rom", "../gow-rom"));
		Assumptions.assumeTrue(Files.exists(Content.file(rom, Region.of(rom).perm())), "gow-rom 없음");
		return EnemyFactory.load(Content.file(rom, wad), WadFile.load(Content.file(rom, Region.of(rom).perm())), -1);
	}

	@Test
	void minotaur() throws Exception {
		EnemyFactory.Kind k = load("R_MIN.WAD");
		assertEquals("min", k.skeleton().name);
		assertEquals(50, k.ai1());
		assertEquals(List.of("TWK_CombatFile_050", "TWK_CombatFile_051", "TWK_CombatFile_052", "TWK_CombatFile_053"), k.combatFiles());
		assertEquals(17, k.block());
		EnemyFighter f = k.spawn();
		assertEquals(90f, f.maxHp());
	}

	@Test
	void skeletonSoldierAndHarpy() throws Exception {
		EnemyFactory.Kind s = load("R_SKS.WAD");
		assertEquals(20, s.ai1());
		assertEquals(35f, s.spawn().maxHp());
		assertTrue(s.combatFiles().containsAll(List.of("TWK_CombatFile_020", "TWK_CombatFile_028")));
		EnemyFactory.Kind h = load("R_HAR.WAD");
		assertEquals("har", h.skeleton().name, "석화 파편 스켈레톤이 아니라 본체");
		assertEquals(15f, h.spawn().maxHp());
	}

	/** 레벨 자체 AI1 템플릿 (ATHN01D 301 "AOXF1") → 같은 레벨 WAD 의 aoxf1 오브젝트 (36 문서 §4). */
	@Test
	void levelLocalTemplateUsesLevelObject() throws Exception {
		Path rom = Path.of(System.getProperty("gow.rom", "../gow-rom"));
		Assumptions.assumeTrue(Files.exists(Content.file(rom, "ATHN01D.WAD")), "gow-rom 없음");
		WadFile lvl = WadFile.load(Content.file(rom, "ATHN01D.WAD"));
		devple.gow.engine.game.Tweaks tw = new devple.gow.engine.game.Tweaks();
		tw.load(lvl);
		assertEquals("aoxf1", EnemyFactory.levelTemplateObject(tw, 301));
		assertEquals("aoxf4", EnemyFactory.levelTemplateObject(tw, 303));
		assertNull(EnemyFactory.levelTemplateObject(tw, 20));
		EnemyFactory.Kind k = EnemyFactory.load(Content.file(rom, "ATHN01D.WAD"), WadFile.load(Content.file(rom, Region.of(rom).perm())), 301, "aoxf1");
		assertEquals("aoxf1", k.skeleton().name);
		assertEquals("MDL_aoXF1", k.model());
		assertEquals(301, k.ai1());
		assertTrue(EnemyFactory.resourceWads(lvl).contains("R_SKS"));
	}

	/**
	 * 전투 분기의 종류 = AI1 템플릿 +0x9C (해시 0x247B, 0x244AF8) — 변종은 기본 종류: R_MI2 51 → 50 (영웅 GRAB HIT_CONFIRM tgt=50 → 400 MAIMIN01),
	 * R_SKA 31 → 20, R_SKB 24 → 25. 전에는 AI1 번호를 써서 R_MI2 를 잡으면 영웅은 그대로, 적만 MAIMIN01→02 (속도 0, 무적) 에 멈췄다 (ATHN03A 자동 플레이).
	 */
	@Test
	void combatClassIsTemplateClassField() throws Exception {
		assertEquals(50, load("R_MIN.WAD").spawn().classId());
		EnemyFactory.Kind m2 = load("R_MI2.WAD");
		EnemyFighter f = m2.spawn();
		assertEquals(51, f.ai1());
		assertEquals(50, f.classId());
		assertEquals(20, load("R_SKA.WAD").spawn().classId());
		assertEquals(25, load("R_SKB.WAD").spawn().classId());
		assertEquals(20, load("R_SKS.WAD").spawn().classId());
	}
}
