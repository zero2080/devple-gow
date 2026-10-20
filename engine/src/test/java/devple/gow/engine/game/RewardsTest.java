package devple.gow.engine.game;

import devple.gow.tools.content.Content;
import devple.gow.tools.content.Region;
import devple.gow.engine.combat.GodMode;
import devple.gow.tools.wad.WadFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** 오브 드롭 / 히트 카운터 / 신들의 분노 수치를 원본 데이터로 검증 (10 문서 §9, §11). */
class RewardsTest {

	private static Tweaks load(String... wads) throws Exception {
		Path rom = Path.of(System.getProperty("gow.rom", "../gow-rom"));
		Assumptions.assumeTrue(Files.exists(Content.file(rom, Region.of(rom).perm())), "gow-rom 없음");
		Tweaks t = new Tweaks();
		for (String w : wads) {
			t.load(WadFile.load(Content.file(rom, w)));
		}
		return t;
	}

	@Test
	void minotaurDeathDrops25RedOrbPoints() throws Exception {
		// Orb 템플릿(051/052 …)은 레벨 WAD 에 있으므로 미노타우로스가 나오는 레벨과 함께 로드
		Tweaks tw = load("R_PERMK.WAD", "R_MIN.WAD", "PAND03A.WAD");
		List<OrbDrops.Orb> orbs = OrbDrops.roll(tw, "MIN Death", new Random(1), 100, 100);
		assertFalse(orbs.isEmpty());
		assertTrue(orbs.stream().allMatch(o -> o.type() == 2), orbs.toString());
		assertEquals(25f, (float) orbs.stream().mapToDouble(OrbDrops.Orb::points).sum(), orbs.toString());
	}

	@Test
	void hitCounterMilestones() throws Exception {
		HitCounter hc = new HitCounter(load("R_PERMK.WAD"));
		assertEquals(20, hc.milestoneCount());
		HitCounter.Reward r = null;
		for (int i = 0; i < 10; i++) {
			r = hc.hit();
		}
		assertNotNull(r);
		assertEquals(10, r.hits());
		assertEquals(2, r.redOrbs());
		hc.update(1.5f);
		assertEquals(0, hc.hits());
	}

	@Test
	void godModeLastsAbout14Seconds() throws Exception {
		Tweaks tw = load("R_PERMK.WAD");
		GodMode gm = new GodMode(tw);
		var c = new devple.gow.engine.combat.Combatant(null, null, null);
		c.godMeter = 100;
		assertFalse(gm.update(c, true, 0, 100, 0), "블레이드 Lv0 은 발동 불가");
		assertTrue(gm.update(c, true, 1, 100, 0));
		float t = 0;
		while (gm.active()) {
			gm.update(c, false, 1, 100, 1 / 60f);
			t += 1 / 60f;
		}
		assertEquals(14f, t, 0.2f);
	}
}
