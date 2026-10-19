package devple.gow.engine.audio;

import devple.gow.tools.audio.SoundBank;
import devple.gow.tools.wad.WadFile;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 29 문서 §9: 989snd LFO (IRX 0x24D8) — 모양·주기 단위 검사 + 실데이터 횃불 소리 볼륨 흔들림. */
class SoundLfoTest {

	@Test
	void stepIsHertzAt240() {
		// step 279620 → 1.000 Hz: 240 틱 후 위상이 한 바퀴 (사인 0 → 0)
		SoundBank.Lfo p = new SoundBank.Lfo(0, SoundLfo.PBEND, 0, 1, 1024, 1024, 0, 0, 279620);
		SoundLfo l = new SoundLfo(p, new Random(1), 100);
		int max = Integer.MIN_VALUE, min = Integer.MAX_VALUE;
		for (int i = 0; i < 240; i++) {
			int v = l.next(new Random(1));
			max = Math.max(max, v);
			min = Math.min(min, v);
		}
		assertTrue(max > 32700 && min < -32700, max + " " + min);
		int ph = l.phase >> 16;
		assertTrue(ph <= 1 || ph >= 2047, "240 틱 = 한 주기: " + ph);
		assertEquals(32767, l.scaled, "PBEND 깊이 1024 = ±32767");
	}

	@Test
	void triangleAndSquareShapes() {
		SoundLfo tri = new SoundLfo(new SoundBank.Lfo(0, 1, 0, 3, 1024, 1024, 0, 0x200, 0), new Random(1), 127);
		assertEquals(0x7FFF, tri.next(new Random(1)), "삼각 1/4 지점 = 최대");
		SoundLfo sq = new SoundLfo(new SoundBank.Lfo(0, 1, 0, 2, 1024, 1024, 1, 0, 0), new Random(1), 127);
		assertEquals(-0x7FFF, sq.next(new Random(1)), "사각 앞 절반 = +, flags 1 반전");
	}

	@Test
	void torchLoopWobblesVolume() throws Exception {
		var rom = SoundBankTest.rom();
		SoundManager m = SoundManager.headless(rom);
		m.load(WadFile.load(rom.resolve("R_PERMK.WAD")));
		assertTrue(m.has("SND_TORCH_LOOP"));
		int h = m.play("SND_TORCH_LOOP", null);
		assertTrue(h > 0);
		NullAudioBackend b = (NullAudioBackend) m.backend();
		float lo = Float.MAX_VALUE, hi = 0;
		for (int i = 0; i < 240; i++) {
			m.update(1 / 60f);
			for (NullAudioBackend.Started s : b.log()) {
				Voice v = b.lastUpdate(s.id());
				if (v != null) {
					lo = Math.min(lo, v.gain);
					hi = Math.max(hi, v.gain);
				}
			}
		}
		assertNotNull(b.log());
		assertTrue(hi > 0 && hi - lo > 0.01f, "LFO 볼륨 흔들림: " + lo + ".." + hi);
		System.out.println("SND_TORCH_LOOP 볼륨 범위 " + lo + ".." + hi);
	}
}
