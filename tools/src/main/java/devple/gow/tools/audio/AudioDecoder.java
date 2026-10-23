package devple.gow.tools.audio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * VAG / VPK 디코더.
 * <pre>
 * VAG (빅엔디언 헤더 48바이트)
 *   0x00 "VAGp"  0x04 version  0x0C dataSize  0x10 sampleRate  0x20 name[16]  0x30 ADPCM 데이터
 * VPK (리틀엔디언)
 *   0x00 " KPV"  0x04 채널당 데이터 크기  0x08 dataOffset(0x800)  0x0C 블록 크기 (8192, 스테레오 한 쌍 합계)
 *   0x10 sampleRate  0x14 channels   데이터: 채널별 {@link #vpkInterleave} (0x0C / 2 = 0x1000) 바이트씩 번갈아 배치
 *   (0x0C 를 채널당 크기로 보면 좌우 상관 ≈ 0, 절반으로 보면 음성 파일에서 0.67 → 2ch 는 0x1000.
 *    4ch 음악도 0x1000 — 0x0C / 4 = 0x800 으로 읽으면 0.11초마다 다른 채널 조각이 섞여 끊긴다, 09 문서 §3)
 * </pre>
 */
public final class AudioDecoder {

	private AudioDecoder() {
	}

	/**
	 * VPK 채널 인터리브 (바이트): 채널 수와 상관없이 헤더 0x0C 의 절반 (= 0x1000).
	 * 4ch 파일 4개 모두 0x1000 경계에서만 ADPCM 이음매가 매끄럽다 (경계/일반 샘플 차 비율 1.0, 0x800 은 1.2–1.5) [확실: 데이터].
	 */
	public static int vpkInterleave(int block, int channels) {
		return channels <= 1 ? block : block / 2;
	}

	public static PcmSound decode(String name, byte[] file) {
		if (file.length >= 4 && file[0] == 'V' && file[1] == 'A' && file[2] == 'G' && file[3] == 'p') {
			return decodeVag(name, file);
		}
		if (file.length >= 4 && file[0] == ' ' && file[1] == 'K' && file[2] == 'P' && file[3] == 'V') {
			return decodeVpk(name, file);
		}
		throw new IllegalArgumentException("알 수 없는 오디오 포맷: " + name);
	}

	static PcmSound decodeVag(String name, byte[] f) {
		ByteBuffer b = ByteBuffer.wrap(f).order(ByteOrder.BIG_ENDIAN);
		int size = Math.min(b.getInt(0x0C), f.length - 0x30);
		int rate = b.getInt(0x10);
		short[] out = new short[size / 16 * 28];
		PsAdpcm dec = new PsAdpcm();
		int n = 0;
		for (int off = 0x30; off + 16 <= 0x30 + size; off += 16) {
			if (!dec.decodeFrame(f, off, out, n)) {
				break;
			}
			n += 28;
		}
		return new PcmSound(name, rate, 1, Arrays.copyOf(out, n));
	}

	static PcmSound decodeVpk(String name, byte[] f) {
		ByteBuffer b = ByteBuffer.wrap(f).order(ByteOrder.LITTLE_ENDIAN);
		int dataOff = b.getInt(0x08);
		int rate = b.getInt(0x10);
		int ch = Math.max(1, b.getInt(0x14));
		int inter = vpkInterleave(b.getInt(0x0C), ch);
		int dataLen = Math.min(b.getInt(0x04) * ch, f.length - dataOff);
		int perCh = dataLen / ch / 16 * 28;
		short[][] chans = new short[ch][perCh];
		int[] filled = new int[ch];
		PsAdpcm[] dec = new PsAdpcm[ch];
		for (int c = 0; c < ch; c++) {
			dec[c] = new PsAdpcm();
		}
		boolean[] ended = new boolean[ch];
		for (int blk = dataOff; blk < dataOff + dataLen; blk += inter * ch) {
			for (int c = 0; c < ch; c++) {
				int start = blk + c * inter;
				for (int off = start; off + 16 <= Math.min(start + inter, f.length); off += 16) {
					if (ended[c] || filled[c] + 28 > perCh) {
						break;
					}
					if (!dec[c].decodeFrame(f, off, chans[c], filled[c])) {
						ended[c] = true;
						break;
					}
					filled[c] += 28;
				}
			}
		}
		int len = Arrays.stream(filled).min().orElse(0);
		short[] out = new short[len * ch];
		for (int i = 0; i < len; i++) {
			for (int c = 0; c < ch; c++) {
				out[i * ch + c] = chans[c][i];
			}
		}
		return new PcmSound(name, rate, ch, out);
	}
}
