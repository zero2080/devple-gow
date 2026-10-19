package devple.gow.engine.audio;

import devple.gow.tools.audio.SoundBank;
import devple.gow.tools.audio.WadSounds;
import devple.gow.tools.wad.WadFile;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 29 문서 §7: SPU ADSR 엔벨로프 — 규칙 단위 검사 + 전 뱅크 ADSR 값 분포. */
class SpuEnvelopeTest {

	@Test
	void attackSustainRelease() {
		// 공격 shift 0 step +7 (즉시), 감쇠 0, 서스테인 레벨 F (=0x8000 → 최대 유지), 서스테인 shift 1F(고정), 릴리스 선형 shift 10
		SpuEnvelope e = new SpuEnvelope(0x000F, 0x1FC0 | 10);
		e.advanceSamples(10);
		assertTrue(e.level() > 0.99f, "즉시 공격: " + e.level());
		e.advance(1);
		assertTrue(e.level() > 0.99f, "서스테인 유지: " + e.level());
		e.release();
		// 선형 −8<<1 = −16/샘플 → 0x7FFF/16 ≈ 2048 샘플 ≈ 43ms
		e.advanceSamples(1000);
		assertTrue(e.level() > 0.3f && e.level() < 0.7f, "릴리스 중간: " + e.level());
		e.advanceSamples(1100);
		assertTrue(e.off(), "릴리스 끝");
	}

	@Test
	void slowAttackIsGradual() {
		SpuEnvelope e = new SpuEnvelope(0x5000 | 0xF, 0x1FC0);  // 공격 shift 20 → 512 샘플마다 +7
		e.advance(0.5f);
		assertTrue(e.level() < 0.05f, "느린 공격: " + e.level());
	}

	@Test
	void adsrDistribution() throws Exception {
		Path rom = SoundBankTest.rom();
		List<Path> wads;
		try (Stream<Path> s = Files.list(rom)) {
			wads = s.filter(p -> p.toString().endsWith(".WAD")).sorted().toList();
		}
		Map<String, Integer> hist = new TreeMap<>();
		Map<String, Float> releaseMs = new TreeMap<>();
		int tones = 0;
		for (Path p : wads) {
			for (SoundBank b : WadSounds.load(WadFile.load(p)).banks()) {
				for (SoundBank.Grain g : b.grains()) {
					if (g.type() != SoundBank.G_TONE && g.type() != SoundBank.G_TONE2) {
						continue;
					}
					SoundBank.Tone t = b.tone(g);
					tones++;
					String k = String.format("%04X/%04X", t.adsr1() & 0xFFFF, t.adsr2() & 0xFFFF);
					hist.merge(k, 1, Integer::sum);
					if (!releaseMs.containsKey(k)) {
						SpuEnvelope e = new SpuEnvelope(t.adsr1(), t.adsr2());
						e.advance(0.2f);
						e.release();
						int n = 0;
						while (!e.off() && n < 48000 * 20) {
							e.advanceSamples(48);
							n += 48;
						}
						releaseMs.put(k, n / 48f);
					}
				}
			}
		}
		StringBuilder sb = new StringBuilder("톤 " + tones + " ADSR 종류 " + hist.size() + "\n");
		hist.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(20)
				.forEach(e -> sb.append("  ").append(e.getKey()).append(" ×").append(e.getValue())
						.append(" 릴리스 ").append(releaseMs.get(e.getKey())).append("ms\n"));
		System.out.println(sb);
		assertTrue(tones > 1000);
		assertEquals(hist.values().stream().mapToInt(Integer::intValue).sum(), tones);
	}
}
