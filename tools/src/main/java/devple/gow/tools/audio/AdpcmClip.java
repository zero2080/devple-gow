package devple.gow.tools.audio;

/**
 * 디코딩된 모노 PS-ADPCM 클립 + 루프 정보.
 *
 * @param rate      재생 샘플레이트 (Hz, 톤의 CenterNote/Fine 으로 계산한 값 또는 VAG 헤더 값)
 * @param loopStart 루프 시작 샘플 (플래그 0x04 프레임). 루프가 없으면 -1 — 끝 프레임 플래그 3(끝+반복)일 때만 설정
 */
public record AdpcmClip(String name, int rate, short[] pcm, int loopStart) {

	public boolean looped() {
		return loopStart >= 0;
	}

	public double seconds() {
		return pcm.length / (double) rate;
	}

	public PcmSound toPcm() {
		return new PcmSound(name, rate, 1, pcm);
	}

	/**
	 * SPU ADPCM 블록 디코딩. 플래그: 0x04 루프 시작, 0x01 끝, 0x02 (끝과 함께) 반복, 7 = 무음 종료 프레임(디코딩 안 함).
	 * 끝 플래그(0x01) 프레임까지 디코딩하고 멈춘다.
	 */
	public static AdpcmClip decode(String name, int rate, byte[] src, int off, int size) {
		int end = Math.min(src.length, off + size);
		short[] out = new short[Math.max(0, (end - off) / 16 * 28)];
		PsAdpcm dec = new PsAdpcm();
		int n = 0;
		int loopStart = -1;
		boolean loop = false;
		for (int p = off; p + 16 <= end; p += 16) {
			int flags = src[p + 1] & 0xFF;
			if (!dec.decodeFrame(src, p, out, n)) {
				break;
			}
			if ((flags & 4) != 0 && loopStart < 0) {
				loopStart = n;
			}
			n += 28;
			if ((flags & 1) != 0) {
				loop = (flags & 2) != 0;
				break;
			}
		}
		short[] pcm = n == out.length ? out : java.util.Arrays.copyOf(out, n);
		return new AdpcmClip(name, rate, pcm, loop ? Math.max(0, loopStart) : -1);
	}
}
