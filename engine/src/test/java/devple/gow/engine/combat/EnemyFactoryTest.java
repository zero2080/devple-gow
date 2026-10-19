package devple.gow.engine.combat;

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
		Assumptions.assumeTrue(Files.exists(rom.resolve("R_PERMK.WAD")), "gow-rom 없음");
		return EnemyFactory.load(rom.resolve(wad), WadFile.load(rom.resolve("R_PERMK.WAD")), -1);
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
}
