package devple.gow.engine.audio;

import devple.gow.engine.game.GameSession;
import devple.gow.tools.wad.WadFile;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 29 문서 §8: 리버브 이미터(SEM flags bit0) → 리스너 리버브 깊이. */
class ReverbTest {

	@Test
	void deckEmitterDrivesReverb() throws Exception {
		Path rom = SoundBankTest.rom();
		GameSession s = new GameSession(rom); // TWK_Sound 프리셋 적용
		SoundManager m = SoundManager.headless(rom);
		m.load(WadFile.load(rom.resolve("R_PERMK.WAD")));
		m.load(WadFile.load(rom.resolve("ATHN01A.WAD")));
		m.setReverbPresets(new int[]{0, 2728, 3432, 4792, 2080, 2608, 3472, 3752}, 3500);
		m.listener(new Vector3f(), new Vector3f(0, 0, -1), new Vector3f(0, 1, 0));
		m.update(0.1f);
		assertEquals(3500, m.reverbDepth(), 1, "이미터 없음 = Base Reverb");
		// SNDAmbDeckofShip: 종류 3 (Outdoor Large 4792), 원점 근처
		m.soundGroup("SNDGrpAmbDeckofShip", 1, new Vector3f());
		for (int i = 0; i < 30; i++) {
			m.update(1 / 30f);
		}
		assertTrue(m.reverbDepth() > 4500, "갑판 = Outdoor Large 쪽: " + m.reverbDepth());
		assertEquals(m.reverbDepth() / 32767f, ((NullAudioBackend) m.backend()).reverbDepth(), 1e-4);
		// 멀어지면 Base 로 돌아간다
		m.listener(new Vector3f(5000, 0, 0), new Vector3f(0, 0, -1), new Vector3f(0, 1, 0));
		for (int i = 0; i < 30; i++) {
			m.update(1 / 30f);
		}
		assertEquals(3500, m.reverbDepth(), 1);
		m.soundGroup("SNDGrpAmbDeckofShip", 0, null);
		m.update(0.1f);
		assertTrue(s.sound != null);
	}
}
