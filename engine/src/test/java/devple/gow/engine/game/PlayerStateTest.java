package devple.gow.engine.game;

import devple.gow.tools.wad.WadFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 11 문서의 수치/규칙을 원본 데이터로 검증. */
class PlayerStateTest {

	private static GameRules rules;

	@BeforeAll
	static void load() throws Exception {
		Path perm = Path.of(System.getProperty("gow.rom", "../gow-rom"), "R_PERMK.WAD");
		Assumptions.assumeTrue(Files.exists(perm), "gow-rom 없음");
		rules = GameRules.load(WadFile.load(perm));
	}

	@Test
	void upgradeCostsFromFlash() {
		assertEquals(1500, rules.upgradeCost(Weapon.BLADES, 0));
		assertEquals(9000, rules.upgradeCost(Weapon.BLADES, 3));
		assertEquals(750, rules.upgradeCost(Weapon.ZEUS, 0));
		assertEquals(10000, rules.upgradeCost(Weapon.HADES, 1));
		assertEquals(Integer.MAX_VALUE, rules.upgradeCost(Weapon.BLADES, 4));
	}

	@Test
	void investCarriesOver() {
		PlayerState p = new PlayerState(rules);
		p.addRedOrbs(2000);
		assertTrue(p.invest(Weapon.BLADES, 2000));
		assertEquals(1, p.level.get(Weapon.BLADES));
		assertEquals(500, p.invested.get(Weapon.BLADES));
		assertEquals(2250 - 500, p.neededOrbs(Weapon.BLADES));
		assertFalse(p.invest(Weapon.ZEUS, 10), "보유하지 않은 마법은 투자 불가");
	}

	@Test
	void gorgonEyesRaiseMaxHp() {
		PlayerState p = new PlayerState(rules);
		p.addItem(10, 5);
		assertEquals(0, p.hpLevel);
		p.addItem(10, 1);
		assertEquals(1, p.hpLevel);
		assertEquals(125f, p.maxHp());
		p.addItem(10, 100);
		assertEquals(18, p.gorgonEyes);
		assertEquals(3, p.hpLevel);
	}

	@Test
	void firstMagicGivesMagicMeter() {
		PlayerState p = new PlayerState(rules);
		assertEquals(0f, p.maxMp());
		p.addItem(3, 1);
		assertEquals(Weapon.POSEIDON, p.selectedMagic);
		assertEquals(100f, p.maxMp());
	}

	@Test
	void redOrbCapAndCheckpointHp() {
		PlayerState p = new PlayerState(rules);
		p.addRedOrbs(70000);
		assertEquals(60000, p.redOrbs);
		p.deaths = 5;
		p.onCheckpointRestart(10);
		assertEquals(45f, p.hp);
	}
}
