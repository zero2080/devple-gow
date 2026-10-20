package devple.gow.tools.audio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * 모드 사운드 교체용 WAV (49 문서 §11): RIFF PCM 8/16 비트, 모노·스테레오 (그 이상은 앞 두 채널), 아무 샘플레이트.
 * {@code smpl} 청크의 첫 루프가 있으면 그 시작 샘플부터 반복한다.
 *
 * @param pcm       PCM16 (채널 인터리브)
 * @param loopStart 루프 시작 (채널당 샘플 번호), 없으면 -1
 */
public record WavClip(PcmSound pcm, int loopStart) {

	public int channels() {
		return pcm.channels();
	}

	public int frames() {
		return pcm.samples().length / pcm.channels();
	}

	/** 같은 루프의 다른 시작 (-1 = 한 번 재생). */
	public WavClip withLoop(int start) {
		return new WavClip(pcm, start);
	}

	/** 모노 클립 (스테레오면 평균) — 3D 위치 보이스로 재생할 수 있는 형태. */
	public AdpcmClip mono() {
		short[] s = pcm.samples();
		int ch = pcm.channels();
		short[] m = s;
		if (ch > 1) {
			m = new short[s.length / ch];
			for (int i = 0; i < m.length; i++) {
				int sum = 0;
				for (int c = 0; c < ch; c++) {
					sum += s[i * ch + c];
				}
				m[i] = (short) (sum / ch);
			}
		}
		return new AdpcmClip(pcm.name(), pcm.sampleRate(), m, loopStart);
	}

	public static WavClip read(String name, byte[] b) {
		ByteBuffer d = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
		if (b.length < 12 || !tag(b, 0, "RIFF") || !tag(b, 8, "WAVE")) {
			throw new IllegalArgumentException(name + ": RIFF/WAVE 아님");
		}
		int ch = 0, rate = 0, bits = 0, fmt = 0, dataOff = -1, dataLen = 0, loop = -1;
		for (int p = 12; p + 8 <= b.length; ) {
			int len = d.getInt(p + 4);
			int body = p + 8;
			if (len < 0 || body + len > b.length) {
				len = b.length - body; // 잘린 파일: 남은 만큼
			}
			if (tag(b, p, "fmt ") && len >= 16) {
				fmt = Short.toUnsignedInt(d.getShort(body));
				ch = Short.toUnsignedInt(d.getShort(body + 2));
				rate = d.getInt(body + 4);
				bits = Short.toUnsignedInt(d.getShort(body + 14));
				if (fmt == 0xFFFE && len >= 26) {
					fmt = Short.toUnsignedInt(d.getShort(body + 24)); // WAVE_FORMAT_EXTENSIBLE 하위 형식
				}
			} else if (tag(b, p, "data")) {
				dataOff = body;
				dataLen = len;
			} else if (tag(b, p, "smpl") && len >= 36 + 24 && d.getInt(body + 28) > 0) {
				loop = d.getInt(body + 36 + 8);
			}
			p = body + len + (len & 1);
		}
		if (fmt != 1 || (bits != 16 && bits != 8) || ch < 1 || rate <= 0 || dataOff < 0) {
			throw new IllegalArgumentException(name + ": PCM 8/16 비트 WAV 아님 (형식 " + fmt + ", " + bits + " 비트, " + ch + " 채널)");
		}
		int outCh = Math.min(ch, 2);
		int bps = bits / 8;
		int frames = dataLen / (bps * ch);
		short[] out = new short[frames * outCh];
		for (int i = 0; i < frames; i++) {
			for (int c = 0; c < outCh; c++) {
				int at = dataOff + (i * ch + c) * bps;
				out[i * outCh + c] = bits == 16 ? d.getShort(at) : (short) (((b[at] & 0xFF) - 128) << 8);
			}
		}
		return new WavClip(new PcmSound(name, rate, outCh, out), loop >= 0 && loop < frames ? loop : -1);
	}

	private static boolean tag(byte[] b, int at, String t) {
		return at + 4 <= b.length && new String(b, at, 4, StandardCharsets.US_ASCII).equals(t);
	}
}
