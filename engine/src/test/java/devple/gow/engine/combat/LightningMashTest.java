package devple.gow.engine.combat;

import devple.gow.tools.content.Content;
import devple.gow.tools.content.Region;
import devple.gow.engine.game.Tweaks;
import devple.gow.tools.wad.WadFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 포세이돈 매시 미터 (SCR_Lightning 0x194B38–0x194BD4, 40 문서 §4.1). */
class LightningMashTest {

	private static final float DT = 1 / 60f;

	@Test
	void pressesFillAndDrainEmpties() {
		LightningMash m = new LightningMash();
		m.start();
		// 2단계: Drain 3/초, Max 15. 매 6 프레임(초당 10번) 누르면 초당 +10 − 3 = +7
		float rate = 0;
		for (int f = 0; f < 60; f++) {
			rate = m.update(true, 3, 15, f % 6 == 0, DT);
		}
		assertEquals(7f / 15f, rate, 0.05f);
		// 계속 연타하면 Max 에서 멈춘다 (누른 프레임에는 Max + 1 까지 잠깐 넘을 수 있음)
		for (int f = 0; f < 600; f++) {
			rate = m.update(true, 3, 15, f % 6 == 0, DT);
		}
		assertTrue(m.meter() <= 16f && m.meter() >= 14f, "meter " + m.meter());
		// 손을 떼면 초당 3 씩 빠져 5 초 뒤 0
		for (int f = 0; f < 60 * 6; f++) {
			rate = m.update(true, 3, 15, false, DT);
		}
		assertEquals(0f, rate);
	}

	@Test
	void notAllowedStaysZero() {
		LightningMash m = new LightningMash();
		m.start();
		for (int f = 0; f < 60; f++) {
			assertEquals(0f, m.update(false, 1, 1, true, DT));
		}
	}

	@Test
	void startResets() {
		LightningMash m = new LightningMash();
		m.update(true, 3, 10, true, DT);
		assertTrue(m.meter() > 0);
		m.start();
		assertEquals(0f, m.meter());
	}

	/** R_PERMK /Player/Lightning 값 (short/bool 은 하위 바이트, 06 문서 §5). */
	@Test
	void tweakValues() throws Exception {
		Path rom = Path.of(System.getProperty("gow.rom", "../gow-rom"));
		Assumptions.assumeTrue(Files.exists(Content.file(rom, Region.of(rom).perm())), "gow-rom 없음");
		Tweaks tw = new Tweaks();
		tw.load(WadFile.load(Content.file(rom, Region.of(rom).perm())));
		String n = "/Player/Lightning/";
		assertEquals(12, tw.i16(n, "Damage Freq", 0));
		assertEquals(20, tw.i16(n, "From Above Bolt Freq", 0));
		assertEquals(1200f, tw.f(n, "Mash Max Spin", 0));
		assertEquals(0, tw.i(n, "Stage 0 Allow Mashing", -1) & 0xFF);
		assertEquals(1, tw.i(n, "Stage 1 Allow Mashing", -1) & 0xFF);
		assertEquals(15f, tw.f(n, "Stage 1 Mashometer Max", 0));
		assertEquals(3f, tw.f(n, "Stage 1 Mashometer Drain", 0));
		assertEquals(10f, tw.f(n, "Stage 2 Mashometer Max", 0));
		assertEquals(3f, tw.f(n, "Stage 2 Mash Reach Scale", 0));
	}
}
