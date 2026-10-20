package devple.gow.engine.audio;

import devple.gow.tools.content.Content;
import devple.gow.tools.content.Region;
import devple.gow.tools.audio.AdpcmClip;
import devple.gow.tools.audio.SoundBank;
import devple.gow.tools.audio.VagPack;
import devple.gow.tools.audio.WadSounds;
import devple.gow.tools.wad.WadFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 14 문서: 전 WAD 의 SBlk 뱅크·VAG 팩 디코딩 + 신호 검사. */
class SoundBankTest {

	static Path rom() {
		Path rom = Path.of(System.getProperty("gow.rom", "../gow-rom"));
		Assumptions.assumeTrue(Files.exists(Content.file(rom, Region.of(rom).perm())), "gow-rom 없음");
		return rom;
	}

	@Test
	void decodesAllBanksOfAllWads() throws Exception {
		Path rom = rom();
		List<Path> wads;
		try (Stream<Path> s = Files.list(rom)) {
			wads = s.filter(p -> p.toString().endsWith(".WAD")).sorted().toList();
		}
		int banks = 0, packs = 0, tones = 0, loops = 0, clipped = 0, names = 0;
		long samples = 0;
		for (Path p : wads) {
			WadSounds ws = WadSounds.load(WadFile.load(p));
			for (SoundBank b : ws.banks()) {
				banks++;
				names += b.names().size();
				for (SoundBank.Grain g : b.grains()) {
					if (g.type() == SoundBank.G_TONE) {
						SoundBank.Tone t = b.tone(g);
						assertTrue(t.sampleOffset() + t.sampleSize() <= b.sampleData().length, b + " 톤 범위");
						double rate = t.sampleRate();
						assertTrue(rate > 1000 && rate <= 192000, "재생 레이트 " + rate); // SPU2 피치 상한 0x3FFF = 4배
						AdpcmClip c = b.decode(t);
						tones++;
						samples += c.pcm().length;
						if (c.looped()) {
							loops++;
						}
						if (clipRatio(c.pcm()) > 0.01) {
							clipped++;
						}
					} else if (g.type() == SoundBank.G_PLUGIN) {
						assertTrue(b.plugin(g) != null, "DPMS 아닌 PLUGIN 데이터");
					}
				}
			}
			for (VagPack pk : ws.packs()) {
				packs++;
				for (String e : pk.entries().keySet()) {
					AdpcmClip c = pk.clip(e);
					assertTrue(c.rate() >= 8000 && c.pcm().length > 0, e);
				}
			}
		}
		System.out.printf("뱅크 %d, VAG 팩 %d, 이름 %d, 톤 %d (루프 %d), 샘플 %d, 클리핑 %d%n", banks, packs, names, tones, loops, samples,
				clipped);
		assertEquals(157, banks);
		assertEquals(48, packs);
		assertTrue(tones > 8000, "톤 수");
		assertTrue(loops > 300, "루프 톤");
		assertTrue(clipped < tones / 100, "클리핑 톤 " + clipped);
	}

	@Test
	void signalChecks() throws Exception {
		WadSounds perm = WadSounds.load(WadFile.load(rom().resolve("R_PERMK.WAD")));
		SoundBank gen = perm.banks().stream().filter(b -> b.bankId().equals("GEN1")).findFirst().orElseThrow();
		assertEquals(223, gen.sounds().size());
		// 발소리 STEP 톤: 0x970 바이트 = 162 프레임 → 끝 플래그에서 정지, 12016Hz 근처
		SoundBank.Sound step = gen.sound("SND_FOOTSTEP_DIRTSTEP");
		SoundBank.Tone t = gen.grains(step).stream().filter(g -> g.type() == SoundBank.G_TONE).map(gen::tone).findFirst().orElseThrow();
		assertEquals(-84, t.centerNote());
		assertEquals(12016.0, t.sampleRate(), 2.0);
		AdpcmClip c = gen.decode(t);
		assertTrue(c.seconds() > 0.1 && c.seconds() < 1.0, "길이 " + c.seconds());
		double rms = rms(c.pcm());
		double zc = zeroCross(c.pcm());
		System.out.printf("DIRTSTEP %.2fs rms=%.0f zc=%.3f%n", c.seconds(), rms, zc);
		assertTrue(rms > 500 && rms < 20000, "RMS");
		assertTrue(zc < 0.45, "영교차율이 노이즈 수준(0.5) 아님");
		// 앞뒤 프레임 상관: 정상 신호는 인접 샘플 상관이 높다 (노이즈 ≈ 0)
		assertTrue(lag1(c.pcm()) > 0.3, "인접 샘플 상관");
	}

	static double clipRatio(short[] x) {
		int n = 0;
		for (short s : x) {
			if (s == Short.MAX_VALUE || s == Short.MIN_VALUE) {
				n++;
			}
		}
		return x.length == 0 ? 0 : n / (double) x.length;
	}

	static double rms(short[] x) {
		double s = 0;
		for (short v : x) {
			s += v * (double) v;
		}
		return Math.sqrt(s / Math.max(1, x.length));
	}

	static double zeroCross(short[] x) {
		int n = 0;
		for (int i = 1; i < x.length; i++) {
			if ((x[i - 1] < 0) != (x[i] < 0)) {
				n++;
			}
		}
		return n / (double) Math.max(1, x.length);
	}

	static double lag1(short[] x) {
		double a = 0, b = 0, c = 0;
		for (int i = 1; i < x.length; i++) {
			a += x[i] * (double) x[i - 1];
			b += x[i] * (double) x[i];
			c += x[i - 1] * (double) x[i - 1];
		}
		return a / Math.sqrt(Math.max(1, b * c));
	}
}
