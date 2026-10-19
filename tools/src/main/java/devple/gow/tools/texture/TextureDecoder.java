package devple.gow.tools.texture;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;

/**
 * GFX(+PAL) → ARGB 디코더.
 * <ul>
 *   <li>픽셀 데이터는 선형(행 우선). PSMT4 는 바이트당 하위 니블이 왼쪽 픽셀.</li>
 *   <li>256색 팔레트는 GS CSM1 배치로 저장 → 인덱스 i 의 비트3/비트4 를 교환해 조회.</li>
 *   <li>알파는 PS2 규약 0x80 = 불투명 → 0..255 로 확장.</li>
 * </ul>
 */
public final class TextureDecoder {

	private TextureDecoder() {
	}

	/** CSM1: 32엔트리 블록마다 8~15 와 16~23 이 뒤바뀌어 저장됨. */
	public static int csm1(int i) {
		return (i & ~0x18) | ((i & 0x08) << 1) | ((i & 0x10) >> 1);
	}

	/**
	 * PSMT8 스위즐 해제: 데이터는 PSMCT32 (w/2 x h/2) 로 전송된 뒤 PSMT8 로 읽히는 배치로 저장되어 있다.
	 * (x,y) 픽셀이 저장 버퍼의 몇 번째 바이트인지 반환.
	 */
	public static int swizzle8(int x, int y, int w) {
		int blockLocation = (y & ~0xF) * w + (x & ~0xF) * 2;
		int swapSelector = (((y + 2) >> 2) & 1) * 4;
		int posY = (((y & ~3) >> 1) + (y & 1)) & 7;
		int columnLocation = posY * w * 2 + ((x + swapSelector) & 7) * 4;
		int byteNum = ((y >> 1) & 1) + ((x >> 2) & 2);
		return blockLocation + columnLocation + byteNum;
	}

	/** PS2 알파(0..0x80) → 0..255. */
	public static int alpha(int a) {
		return a >= 0x80 ? 255 : (a * 255 + 64) >> 7;
	}

	/** RGBA8888(LE: R,G,B,A 바이트 순) 한 픽셀 → ARGB. */
	static int rgba32(ByteBuffer b, int off) {
		int r = b.get(off) & 0xFF, g = b.get(off + 1) & 0xFF, bl = b.get(off + 2) & 0xFF, a = b.get(off + 3) & 0xFF;
		return alpha(a) << 24 | r << 16 | g << 8 | bl;
	}

	/** RGBA5551 → ARGB. */
	static int rgba16(int v) {
		int r = (v & 0x1F) << 3, g = (v >> 5 & 0x1F) << 3, bl = (v >> 10 & 0x1F) << 3;
		int a = (v & 0x8000) != 0 ? 255 : 0;
		return a << 24 | (r | r >> 5) << 16 | (g | g >> 5) << 8 | (bl | bl >> 5);
	}

	/**
	 * 팔레트 리소스에서 index 번째 CLUT 을 ARGB 배열로 꺼낸다 (CSM1 해제 포함).
	 *
	 * @param colors 16 또는 256
	 */
	public static int[] clut(GfxResource pal, int index, int colors) {
		if (pal.bpp() != 32) {
			throw new IllegalArgumentException(pal.name() + ": 팔레트 bpp " + pal.bpp());
		}
		int total = pal.width() * pal.height();
		int base = index * colors;
		if (base + colors > total) {
			base = 0;
		}
		int[] out = new int[colors];
		for (int i = 0; i < colors; i++) {
			int src = colors == 256 ? csm1(i) : i;
			if (base + src < total) {
				out[i] = rgba32(pal.data(), (base + src) * 4);
			}
		}
		return out;
	}

	/** 팔레트 리소스에 들어있는 CLUT 수. */
	public static int clutCount(GfxResource pal, int colors) {
		return Math.max(1, pal.width() * pal.height() / colors);
	}

	/**
	 * 이미지 전체(모든 프레임 세로 스트립)를 ARGB 로 디코드.
	 *
	 * @param pal 인덱스 이미지면 필수, 없으면 그레이스케일 대체
	 */
	public static int[] decode(GfxResource img, GfxResource pal, int clutIndex) {
		return decode(img, pal, clutIndex, img.swizzled());
	}

	/** swizzle8 을 강제로 지정해 디코드 (스위즐 판별 실험용). */
	public static int[] decode(GfxResource img, GfxResource pal, int clutIndex, boolean swizzle8) {
		int w = img.width(), h = img.height();
		ByteBuffer d = img.data();
		int[] out = new int[w * h];
		switch (img.bpp()) {
			case 4, 8 -> {
				int colors = img.bpp() == 4 ? 16 : 256;
				int[] lut = pal != null ? clut(pal, clutIndex, colors) : gray(colors);
				for (int i = 0; i < w * h; i++) {
					int idx;
					if (img.bpp() == 8) {
						int s = swizzle8 ? swizzle8(i % w, i / w, w) : i;
						idx = s < d.limit() ? d.get(s) & 0xFF : 0;
					} else {
						int v = d.get(i >> 1) & 0xFF;
						idx = (i & 1) == 0 ? v & 0x0F : v >> 4;
					}
					out[i] = lut[idx];
				}
			}
			case 16 -> {
				for (int i = 0; i < w * h; i++) {
					out[i] = rgba16(Short.toUnsignedInt(d.getShort(i * 2)));
				}
			}
			case 24 -> {
				for (int i = 0; i < w * h; i++) {
					int o = i * 3;
					out[i] = 0xFF000000 | (d.get(o) & 0xFF) << 16 | (d.get(o + 1) & 0xFF) << 8 | (d.get(o + 2) & 0xFF);
				}
			}
			case 32 -> {
				for (int i = 0; i < w * h; i++) {
					out[i] = rgba32(d, i * 4);
				}
			}
			default -> throw new IllegalArgumentException(img.name() + ": 지원하지 않는 bpp " + img.bpp());
		}
		return out;
	}

	public static BufferedImage toImage(int w, int h, int[] argb) {
		BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		bi.setRGB(0, 0, w, h, argb, 0, w);
		return bi;
	}

	private static int[] gray(int colors) {
		int[] g = new int[colors];
		for (int i = 0; i < colors; i++) {
			int v = i * 255 / (colors - 1);
			g[i] = 0xFF000000 | v << 16 | v << 8 | v;
		}
		return g;
	}
}
