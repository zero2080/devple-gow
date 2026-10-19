package devple.gow.tools.texture;

import devple.gow.tools.wad.WadTag;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * TXR_ 리소스 (매직 0x07, 크기 0x58) — GFX 이미지 + PAL 팔레트 + 밉맵 체인.
 * <pre>
 * 0x00 u32      magic    0x00000007
 * 0x04 char[24] gfx      GFX_ 이미지 이름
 * 0x1C char[24] pal      PAL_ 팔레트 이름 (밉 레벨 TXR 은 빈 문자열 = 상위 텍스처 팔레트 공유)
 * 0x34 char[24] nextMip  다음(1/2 크기) 밉 레벨 TXR_ 이름. 없으면 빈 문자열
 * 0x4C s32      mipK     GS TEX1.K 로 추정 (고정소수 7.4, 예 -144 = -9.0). 0 = 밉 없음
 * 0x50 f32      mipL     밉 LOD 거리/스케일로 추정 (256.0, 512.0 ...)
 * 0x54 u32      flags    bit15(0x8000)=밉맵 사용, bit16 항상 1, bit18~22 = 클램프 계열 추정
 * </pre>
 */
public record TextureResource(String name, String gfx, String pal, String nextMip, int mipK, float mipL, int flags) {

	public static final int MAGIC = 0x07;
	public static final int SIZE = 0x58;

	public static TextureResource parse(WadTag t) {
		ByteBuffer b = t.data();
		if (b.getInt(0) != MAGIC || b.limit() < SIZE) {
			throw new IllegalArgumentException(t.name() + ": TXR 아님");
		}
		return new TextureResource(t.name(), cstr(b, 0x04), cstr(b, 0x1C), cstr(b, 0x34),
				b.getInt(0x4C), b.getFloat(0x50), b.getInt(0x54));
	}

	public boolean hasMip() {
		return !nextMip.isEmpty();
	}

	static String cstr(ByteBuffer b, int off) {
		int len = 0;
		while (len < 24 && b.get(off + len) != 0) {
			len++;
		}
		byte[] tmp = new byte[len];
		b.get(off, tmp);
		return new String(tmp, StandardCharsets.ISO_8859_1);
	}
}
