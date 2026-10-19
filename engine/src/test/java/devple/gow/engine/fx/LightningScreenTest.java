package devple.gow.engine.fx;

import devple.gow.engine.game.Tweaks;
import devple.gow.tools.fx.EnvParams;
import devple.gow.tools.wad.WadFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 포세이돈 화면 효과 0x195BA8 / 0x195C90 (35 문서 §4.6) 와 신들의 분노 틴트 값. */
class LightningScreenTest {

	private static Tweaks permk() throws Exception {
		Path rom = Path.of(System.getProperty("gow.rom", "../gow-rom"));
		Assumptions.assumeTrue(Files.exists(rom.resolve("R_PERMK.WAD")), "gow-rom 없음");
		Tweaks tw = new Tweaks();
		tw.load(WadFile.load(rom.resolve("R_PERMK.WAD")));
		return tw;
	}

	@Test
	void stageValuesAndFogTemplate() throws Exception {
		Tweaks tw = permk();
		String n = "/Player/Lightning/";
		for (int s = 0; s < 3; s++) {
			String sp = "Stage " + s + " ";
			assertEquals(0, tw.i16(n, sp + "Bright Camera Filter", 9));
			assertEquals(-1, tw.i16(n, sp + "Dark Camera Filter", 9));
			assertEquals(5, tw.i16(n, sp + "Flash Freq", 9));
			assertEquals(0, tw.i16(n, sp + "Fog Template", 9));
			assertEquals(1f, tw.f(n, sp + "Tint Red", 0));
			assertEquals(1f, tw.f(n, sp + "Tint Blue", 0));
		}
		EnvParams.Fog f = LightningScreen.fogTemplate(tw, 0);
		assertEquals("360 Lightning", f.owner());
		assertArrayEquals(new float[]{0.35f, 0.25f, 0.2f, 0.6f}, f.color(), 1e-3f);
		assertEquals(16500, f.centreZ());
		assertEquals(2, f.blend(), "감산");
		assertEquals(-1, f.backPlane());
		assertNull(f.material(), "관리자 0x2FF730 은 안개 텍스처가 없다");
		assertEquals("None", tw.s("/TweakTemplates/CameraFilter/000/", "Instance Name", null));
		// 신들의 분노 틴트 (Weapon Level n +0x20)
		assertEquals(0.5f, tw.f("/Player/Weapon Level 2/", "God Mode Tint R", 0));
		assertEquals(0.75f, tw.f("/Player/Weapon Level 2/", "God Mode Tint B", 0));
		assertEquals(0f, tw.f("/Player/Weapon Level 5/", "God Mode Tint G", 9));
	}

	@Test
	void flashEveryFifthSecondAndFogOnlyWhileScript() throws Exception {
		LightningScreen s = new LightningScreen(permk());
		float dt = 1 / 60f;
		for (int i = 0; i < 60; i++) {
			s.update(true, 1, dt);
			assertNotNull(s.fog());
		}
		assertEquals(5, s.flashes, 1, "Flash Freq 5 → 초당 5번 (첫 프레임 포함)");
		assertEquals(0, s.filter());
		s.update(false, 1, dt);
		assertNull(s.fog());
		// 다시 시작하면 타이머 0 → 첫 프레임에 바로 플래시
		int before = s.flashes;
		s.update(true, 1, dt);
		assertEquals(before + 1, s.flashes);
	}
}
