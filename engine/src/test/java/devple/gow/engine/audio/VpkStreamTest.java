package devple.gow.engine.audio;

import devple.gow.tools.audio.AudioDecoder;
import devple.gow.tools.audio.PcmSound;
import devple.gow.tools.audio.PsAdpcm;
import devple.gow.tools.content.Content;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 09 문서 §3: VPK 인터리브는 채널 수와 상관없이 0x1000 (헤더 0x0C 8192 의 절반). 4ch 음악 (ATH1C1_4 = ATHN01A 갑판 전투) 을
 * 0x0C / 4 = 0x800 으로 읽으면 0.11초마다 다른 채널 조각이 섞여 음악이 끊겼다.
 */
class VpkStreamTest {

	/** 채널 c 의 모든 샘플이 c+1 인 4ch VPK (predictor 0, shift 12 → 샘플 = 니블). */
	private static Path synthetic(Path dir, int blocks) throws Exception {
		int inter = 0x1000;
		int ch = 4;
		ByteBuffer b = ByteBuffer.allocate(0x800 + blocks * inter * ch).order(ByteOrder.LITTLE_ENDIAN);
		b.put(new byte[]{' ', 'K', 'P', 'V'}).putInt(blocks * inter).putInt(0x800).putInt(8192).putInt(32000).putInt(ch);
		for (int k = 0; k < blocks; k++) {
			for (int c = 0; c < ch; c++) {
				for (int f = 0; f < inter / 16; f++) {
					int o = 0x800 + (k * ch + c) * inter + f * 16;
					b.put(o, (byte) 12);
					int nib = c + 1;
					for (int i = 2; i < 16; i++) {
						b.put(o + i, (byte) (nib | nib << 4));
					}
				}
			}
		}
		Path f = dir.resolve("T_4.VPK");
		Files.write(f, b.array());
		return f;
	}

	@Test
	void fourChannelInterleaveIs0x1000(@TempDir Path dir) throws Exception {
		Path f = synthetic(dir, 3);
		VpkStream s = new VpkStream(f, false);
		short[] out = new short[2 * 28 * 256 * 3];
		int n = s.read(out);
		assertEquals(out.length, n, "3 블록 × 채널당 256 프레임 × 28 샘플");
		for (int i = 0; i < n; i += 2) {
			// 층 0 = 채널 0·1, 층 1 = 채널 2·3: L = 1 + 3, R = 2 + 4 (0x800 이면 조각마다 1+2 / 3+4 로 바뀐다)
			assertEquals(4, out[i], "L @" + i);
			assertEquals(6, out[i + 1], "R @" + i);
		}
		s.close();

		PcmSound p = AudioDecoder.decode("T_4", Files.readAllBytes(f));
		assertEquals(4, p.channels());
		for (int i = 0; i < p.samples().length; i++) {
			assertEquals(i % 4 + 1, p.samples()[i], "audio:wav 채널 " + i % 4);
		}
	}

	/** 실제 4ch 음악: 0x1000 경계의 ADPCM 이음매가 일반 샘플 차와 같은 크기 (0x800 은 20 % 이상 크다). */
	@Test
	void realFourChannelMusicIsSeamlessAt0x1000() throws Exception {
		Path rom = SoundBankTest.rom();
		Path f = Content.file(rom, "ATH1C1_4.VPK");
		Assumptions.assumeTrue(Files.exists(f));
		byte[] d = Files.readAllBytes(f);
		double good = seam(d, 0x1000);
		double bad = seam(d, 0x800);
		assertTrue(good < 1.1, "0x1000 이음매 비율 " + good);
		assertTrue(bad > 1.15, "0x800 이음매 비율 " + bad);
		assertEquals(0x1000, AudioDecoder.vpkInterleave(8192, 4));
		assertEquals(0x1000, AudioDecoder.vpkInterleave(8192, 2));
	}

	/** 채널 0 을 인터리브 inter 로 디코드: 조각 경계 샘플 차 평균 / 조각 안 프레임 경계 샘플 차 평균. */
	private static double seam(byte[] f, int inter) {
		int off = ByteBuffer.wrap(f).order(ByteOrder.LITTLE_ENDIAN).getInt(0x08);
		PsAdpcm dec = new PsAdpcm();
		short[] out = new short[28];
		short last = 0;
		double seam = 0;
		double norm = 0;
		long ns = 0;
		long nn = 0;
		for (int k = 0, p = off; k < 2000 && p + inter * 4 <= f.length; k++, p += inter * 4) {
			for (int fr = 0; fr < inter / 16; fr++) {
				dec.decodeFrame(f, p + fr * 16, out, 0);
				double dd = Math.abs(out[0] - last);
				if (fr == 0) {
					seam += dd;
					ns++;
				} else {
					norm += dd;
					nn++;
				}
				last = out[27];
			}
		}
		return seam / ns / (norm / nn);
	}
}
