package devple.gow.tools.texture;

import devple.gow.tools.wad.WadTag;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * GFX_ / PAL_ 리소스 (매직 0x0C) — GS 로 그대로 전송되는 원시 이미지 데이터.
 * 이미지와 팔레트(CLUT) 가 같은 포맷을 쓴다. 팔레트는 bpp=32 인 작은 이미지(8x2 또는 16x16).
 * <pre>
 * 0x00 u32 magic   0x0000000C
 * 0x04 u32 width   픽셀
 * 0x08 u32 height  픽셀 (frames 개의 서브 이미지를 세로로 쌓은 전체 높이)
 * 0x0C u32 flags   0 또는 2 (R_PERMK 상주 텍스처에서만 2 관찰)
 * 0x10 u32 bpp     4 / 8 (인덱스 이미지), 32 (팔레트 RGBA)
 * 0x14 u32 frames  서브 이미지 수 (애니메이션 프레임 / 팔레트 수). 보통 1
 * 0x18 u8[] data   width*height*bpp/8 바이트, 선형(비 스위즐) 행 우선
 * </pre>
 *
 * @param data position 0 기준 픽셀 데이터 슬라이스
 */
public record GfxResource(String name, int width, int height, int flags, int bpp, int frames, ByteBuffer data) {

	public static final int MAGIC = 0x0C;
	public static final int HEADER = 0x18;

	public static GfxResource parse(WadTag t) {
		ByteBuffer b = t.data();
		if (b.getInt(0) != MAGIC) {
			throw new IllegalArgumentException(t.name() + ": GFX 매직 아님 " + Integer.toHexString(b.getInt(0)));
		}
		int w = b.getInt(4), h = b.getInt(8), flags = b.getInt(0x0C), bpp = b.getInt(0x10), frames = b.getInt(0x14);
		long need = (long) w * h * bpp / 8;
		if (need > b.limit() - HEADER) {
			throw new IllegalArgumentException(t.name() + ": 데이터 부족 " + need + " > " + (b.limit() - HEADER));
		}
		ByteBuffer px = b.slice(HEADER, (int) need).order(ByteOrder.LITTLE_ENDIAN);
		return new GfxResource(t.name(), w, h, flags, bpp, Math.max(1, frames), px);
	}

	/** PSMT8 데이터가 PSMCT32 스위즐 배치인지. flags bit1(=2) 이 서 있으면 선형. PSMT4 는 항상 선형. */
	public boolean swizzled() {
		return bpp == 8 && (flags & 2) == 0;
	}

	/** 서브 이미지(프레임/팔레트) 하나의 높이. */
	public int frameHeight() {
		return height / frames;
	}

	/** PS2 GS 픽셀 포맷 이름 (PSMT4 / PSMT8 / PSMCT16 / PSMCT32). */
	public String psm() {
		return switch (bpp) {
			case 4 -> "PSMT4";
			case 8 -> "PSMT8";
			case 16 -> "PSMCT16";
			case 24 -> "PSMCT24";
			case 32 -> "PSMCT32";
			default -> "bpp" + bpp;
		};
	}
}
