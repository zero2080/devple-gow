package devple.gow.tools.audio;

/**
 * PS2 SPU2 ADPCM 디코더. 16바이트 프레임 → 28샘플.
 * <pre>
 * byte0: (predictor << 4) | shift
 * byte1: flags (1 = loop end, 2 = loop, 4 = loop start, 7 = 무음/종료)
 * byte2..15: 4비트 샘플 28개 (하위 니블 먼저)
 * </pre>
 */
public final class PsAdpcm {

	private static final double[][] COEF = {
			{0.0, 0.0}, {60.0 / 64, 0.0}, {115.0 / 64, -52.0 / 64}, {98.0 / 64, -55.0 / 64}, {122.0 / 64, -60.0 / 64}};

	private double h1;
	private double h2;

	/** frame 오프셋부터 28샘플을 out[outOff..] 에 기록. 종료 플래그(7)면 false. */
	public boolean decodeFrame(byte[] src, int off, short[] out, int outOff) {
		int pred = (src[off] >> 4) & 0x0F;
		int shift = src[off] & 0x0F;
		int flags = src[off + 1] & 0xFF;
		if (flags == 7) {
			return false;
		}
		if (pred > 4) {
			pred = 0;
		}
		for (int i = 0; i < 28; i++) {
			int b = src[off + 2 + i / 2] & 0xFF;
			int nib = (i & 1) == 0 ? b & 0x0F : b >> 4;
			int s = (nib << 28) >> 28; // 부호 확장
			double sample = (s << 12 >> shift) + h1 * COEF[pred][0] + h2 * COEF[pred][1];
			h2 = h1;
			h1 = sample;
			out[outOff + i] = (short) Math.max(-32768, Math.min(32767, Math.round(sample)));
		}
		return true;
	}
}
