package devple.gow.tools.texture;

/**
 * GS 로컬 메모리 배치 에뮬레이션 (PSMCT32 쓰기 → PSMT8 읽기).
 * GFX 의 PSMT8 데이터는 "PSMCT32 (w/2 x h/2) 이미지로 전송하면 GS 메모리에서 올바른 PSMT8 텍스처가 되는" 순서로 저장되어 있다.
 * 페이지 = 8KB, 블록 = 256B (페이지당 32블록). PSMCT32 페이지 64x32 / 블록 8x8, PSMT8 페이지 128x64 / 블록 16x16.
 */
public final class GsSwizzle {

	private static final int[][] BLOCK32 = {
			{0, 1, 4, 5, 16, 17, 20, 21},
			{2, 3, 6, 7, 18, 19, 22, 23},
			{8, 9, 12, 13, 24, 25, 28, 29},
			{10, 11, 14, 15, 26, 27, 30, 31}};

	/** PSMT8 블록 배치는 PSMCT32 와 동일 (8x4 블록). */
	private static final int[][] BLOCK8 = BLOCK32;

	private static final int[][] COLUMN32 = {
			{0, 1, 4, 5, 8, 9, 12, 13},
			{2, 3, 6, 7, 10, 11, 14, 15},
			{16, 17, 20, 21, 24, 25, 28, 29},
			{18, 19, 22, 23, 26, 27, 30, 31},
			{32, 33, 36, 37, 40, 41, 44, 45},
			{34, 35, 38, 39, 42, 43, 46, 47},
			{48, 49, 52, 53, 56, 57, 60, 61},
			{50, 51, 54, 55, 58, 59, 62, 63}};

	/** PSMT8 블록(16x16) 안의 바이트 주소. */
	private static final int[][] COLUMN8 = {
			{0, 4, 16, 20, 32, 36, 48, 52, 2, 6, 18, 22, 34, 38, 50, 54},
			{8, 12, 24, 28, 40, 44, 56, 60, 10, 14, 26, 30, 42, 46, 58, 62},
			{33, 37, 49, 53, 1, 5, 17, 21, 35, 39, 51, 55, 3, 7, 19, 23},
			{41, 45, 57, 61, 9, 13, 25, 29, 43, 47, 59, 63, 11, 15, 27, 31},
			{96, 100, 112, 116, 64, 68, 80, 84, 98, 102, 114, 118, 66, 70, 82, 86},
			{104, 108, 120, 124, 72, 76, 88, 92, 106, 110, 122, 126, 74, 78, 90, 94},
			{65, 69, 81, 85, 97, 101, 113, 117, 67, 71, 83, 87, 99, 103, 115, 119},
			{73, 77, 89, 93, 105, 109, 121, 125, 75, 79, 91, 95, 107, 111, 123, 127},
			{128, 132, 144, 148, 160, 164, 176, 180, 130, 134, 146, 150, 162, 166, 178, 182},
			{136, 140, 152, 156, 168, 172, 184, 188, 138, 142, 154, 158, 170, 174, 186, 190},
			{161, 165, 177, 181, 129, 133, 145, 149, 163, 167, 179, 183, 131, 135, 147, 151},
			{169, 173, 185, 189, 137, 141, 153, 157, 171, 175, 187, 191, 139, 143, 155, 159},
			{224, 228, 240, 244, 192, 196, 208, 212, 226, 230, 242, 246, 194, 198, 210, 214},
			{232, 236, 248, 252, 200, 204, 216, 220, 234, 238, 250, 254, 202, 206, 218, 222},
			{193, 197, 209, 213, 225, 229, 241, 245, 195, 199, 211, 215, 227, 231, 243, 247},
			{201, 205, 217, 221, 233, 237, 249, 253, 203, 207, 219, 223, 235, 239, 251, 255}};

	private GsSwizzle() {
	}

	/** PSMCT32 (x,y) 의 GS 메모리 바이트 주소. pagesPerRow = 버퍼 폭(페이지 단위). */
	static int addr32(int x, int y, int pagesPerRow) {
		int page = (y >> 5) * pagesPerRow + (x >> 6);
		int block = BLOCK32[(y & 31) >> 3][(x & 63) >> 3];
		return page * 8192 + block * 256 + COLUMN32[y & 7][x & 7] * 4;
	}

	/** PSMT8 (x,y) 의 GS 메모리 바이트 주소. */
	static int addr8(int x, int y, int pagesPerRow) {
		int page = (y >> 6) * pagesPerRow + (x >> 7);
		int block = BLOCK8[(y & 63) >> 4][(x & 127) >> 4];
		return page * 8192 + block * 256 + COLUMN8[y & 15][x & 15];
	}

	/**
	 * PSMT8 w x h 텍스처의 각 픽셀이 저장 데이터의 몇 번째 바이트인지 계산한다.
	 * 저장 데이터 = PSMCT32 (w/2 x h/2) 선형 이미지. 대응되는 바이트가 없으면 -1.
	 */
	public static int[] unswizzleMap8(int w, int h) {
		return unswizzleMap8(w, h, Math.max(1, w / 2), Math.max(1, h / 2));
	}

	/** 전송 사각형(PSMCT32 w32 x h32) 을 직접 지정하는 버전. */
	public static int[] unswizzleMap8(int w, int h, int w32, int h32) {
		int pagesPerRow = Math.max(1, (w + 127) / 128);
		int pagesTotal = pagesPerRow * ((h + 63) / 64 + 1);
		int[] memToSrc = new int[pagesTotal * 8192];
		java.util.Arrays.fill(memToSrc, -1);
		for (int y = 0; y < h32; y++) {
			for (int x = 0; x < w32; x++) {
				int a = addr32(x, y, pagesPerRow);
				int src = (y * w32 + x) * 4;
				for (int b = 0; b < 4; b++) {
					memToSrc[a + b] = src + b;
				}
			}
		}
		int[] map = new int[w * h];
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				map[y * w + x] = memToSrc[addr8(x, y, pagesPerRow)];
			}
		}
		return map;
	}
}
