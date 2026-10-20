package devple.gow.engine.ui;

/** 아틀라스 한 장의 선반(shelf) 배치: 왼→오로 채우고, 넘치면 다음 줄. 글리프 사이 pad 픽셀 (필터링 번짐 방지). */
final class GlyphPacker {

	private final int width, height, pad;
	private int x, y, shelf;

	GlyphPacker(int width, int height, int pad) {
		this.width = width;
		this.height = height;
		this.pad = pad;
		this.x = pad;
		this.y = pad;
	}

	/** w×h 자리 {x, y}, 이 장에 안 들어가면 null. */
	int[] place(int w, int h) {
		if (w + 2 * pad > width || h + 2 * pad > height) {
			return null;
		}
		if (x + w + pad > width) {
			x = pad;
			y += shelf + pad;
			shelf = 0;
		}
		if (y + h + pad > height) {
			return null;
		}
		int[] at = {x, y};
		x += w + pad;
		shelf = Math.max(shelf, h);
		return at;
	}
}
