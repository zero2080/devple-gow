package devple.gow.tools.texture;

import devple.gow.tools.wad.WadTag;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * MAT_ 리소스 (매직 0x08) — 0x38 바이트 헤더 + 0x40 바이트 레이어 × layerCount.
 * 크기 = 0x38 + 0x40*N (120 / 184 / 248 바이트 = 1~3 레이어).
 * <pre>
 * 0x00 u32    magic       0x00000008
 * 0x04 u32    reserved    항상 0
 * 0x08 f32[3] color       기본 0.8,0.8,0.8 (디퓨즈 색으로 추정)
 * 0x14 f32    param14     0 또는 0.5 (스펙큘러/환경 강도로 추정)
 * 0x18 f32[4] addColor    더할 색 (VU qw 77, 0..128 단위 — 128 = 1 배, 대부분 0, 디스크 237 개: 12.8 / 64 / 128 … 적 발광) [확실: 0x154410]
 * 0x28 f32    param28     항상 1.0
 * 0x2C f32    specPower   0, 2, 20, 64, 100 ... (스펙큘러 지수로 추정)
 * 0x30 u32    param30     거의 1 (0 은 296개)
 * 0x34 u32    layerCount  1~3
 * 0x38 Layer[layerCount]
 * </pre>
 */
public record MaterialResource(String name, float[] color, float param14, float param28, float specPower,
							   int param30, List<Layer> layers, float[] addColor) {

	public static final int MAGIC = 0x08;
	public static final int HEADER = 0x38;
	public static final int LAYER_SIZE = 0x40;

	/**
	 * 머티리얼 레이어 (멀티패스).
	 * <pre>
	 * 0x00 u32      renderFlags  bit24~31 = GS ALPHA 레지스터 하위 8비트(A,B,C,D 2비트씩)
	 *                            0x44=(Cs-Cd)*As+Cd 알파블렌드, 0x48=Cs*As+Cd 가산, 0x42=Cd-Cs*As 감산
	 *                            bit0~23 = 기타 렌더 플래그 (0x80, 0x010000, 0x080000 ...)
	 * 0x04 u32      flags2       0, 0x100, 0x8, 0x80000000 ...
	 * 0x08 u32      mask1        renderFlags 하위 24비트 중 무시할 비트의 반전 마스크로 추정
	 * 0x0C u32      mask2        항상 ~flags2
	 * 0x10 char[24] texture      TXR_ 이름 (빈 문자열 = 텍스처 없음)
	 * 0x28 f32[4]   rgba         레이어 색 배율 (1.0, 1.5, 2.0 ...)
	 * 0x38 f32      param38      1.0 또는 0.25 (2번째 이후 레이어)
	 * 0x3C u32      reserved     0
	 * </pre>
	 */
	public record Layer(int renderFlags, int flags2, int mask1, int mask2, String texture, float[] rgba,
						float param38) {

		/** GS ALPHA 레지스터 A/B/C/D 필드 (renderFlags 상위 바이트). */
		public int gsAlpha() {
			return renderFlags >>> 24;
		}

		/** 블렌드 식을 사람이 읽을 수 있게. */
		public String blendName() {
			return switch (gsAlpha()) {
				case 0x44 -> "blend";
				case 0x48 -> "add";
				case 0x42 -> "sub";
				case 0x01 -> "invblend";
				default -> String.format("alpha%02X", gsAlpha());
			};
		}
	}

	public static MaterialResource parse(WadTag t) {
		ByteBuffer b = t.data();
		if (b.getInt(0) != MAGIC) {
			throw new IllegalArgumentException(t.name() + ": MAT 아님");
		}
		int n = b.getInt(0x34);
		if (n < 0 || HEADER + (long) n * LAYER_SIZE > b.limit()) {
			throw new IllegalArgumentException(t.name() + ": 레이어 수 이상 " + n);
		}
		List<Layer> layers = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			int o = HEADER + i * LAYER_SIZE;
			layers.add(new Layer(b.getInt(o), b.getInt(o + 4), b.getInt(o + 8), b.getInt(o + 0x0C),
					TextureResource.cstr(b, o + 0x10), floats(b, o + 0x28, 4), b.getFloat(o + 0x38)));
		}
		return new MaterialResource(t.name(), floats(b, 0x08, 3), b.getFloat(0x14), b.getFloat(0x28),
				b.getFloat(0x2C), b.getInt(0x30), List.copyOf(layers), floats(b, 0x18, 3));
	}

	private static float[] floats(ByteBuffer b, int off, int n) {
		float[] f = new float[n];
		for (int i = 0; i < n; i++) {
			f[i] = b.getFloat(off + i * 4);
		}
		return f;
	}
}
