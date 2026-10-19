package devple.gow.engine.audio;

import devple.gow.tools.wad.WadFile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 29 문서 §10: 수중 카메라 → 그룹별 볼륨·피치 (exe 0x1EFB90, TWK_Sound Underwater *). */
class UnderwaterTest {

	@Test
	void underwaterMixesGroups() throws Exception {
		var rom = SoundBankTest.rom();
		SoundManager m = SoundManager.headless(rom);
		m.load(WadFile.load(rom.resolve("R_PERMK.WAD")));
		assertEquals(1f, m.groupGain(0), 1e-6);
		m.setUnderwater(true);
		m.update(0.05f);
		assertEquals(0.5f, m.underwaterMix(), 0.02f, "Tween 0.1초의 절반");
		m.update(0.2f);
		assertEquals(1f, m.underwaterMix(), 1e-6);
		assertEquals(0.5f, m.groupGain(0), 1e-6, "SFX × Underwater Volume Scale 512/1024");
		assertEquals(0.75f, m.groupGain(1), 1e-6, "Music × 768/1024");
		assertEquals(1f, m.groupGain(6), 1e-6, "Menu 는 그대로");
		float p = m.underwaterPitch(0);
		assertEquals(Math.pow(2, -1524 / 128.0 / 12.0), p, 1e-4);
		assertTrue(p < 0.51f, "약 한 옥타브 낮게: " + p);
		assertEquals(1f, m.underwaterPitch(1), "음악 피치 그대로");
		assertTrue(m.underwaterPitch(10) < 1f, "SFX UW pitch 그룹");
		m.setUnderwater(false);
		m.update(0.2f);
		assertEquals(1f, m.groupGain(0), 1e-6);
	}
}
