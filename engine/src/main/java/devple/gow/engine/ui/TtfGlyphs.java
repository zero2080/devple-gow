package devple.gow.engine.ui;

import org.lwjgl.stb.STBTTFontinfo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.lwjgl.stb.STBTruetype.*;

/** stb_truetype 로 TTF/TTC 글리프를 굽는다 (GL 없음 — 49 문서 §7). 단위는 em = px 픽셀. */
public final class TtfGlyphs implements AutoCloseable {

	/** 구운 글리프: 비트맵 w×h (알파), 펜·기준선 기준 왼쪽 위 (xoff, yoff, y 아래로), 전진 폭 (모두 픽셀). */
	public record Glyph(int w, int h, int xoff, int yoff, float advance, byte[] alpha) {
	}

	private final ByteBuffer data; // stbtt 가 계속 참조 — 닫을 때까지 유지
	private final STBTTFontinfo info = STBTTFontinfo.create();
	private final int px;
	private final float scale;

	public TtfGlyphs(Path file, int index, int px) throws IOException {
		byte[] bytes = Files.readAllBytes(file);
		data = MemoryUtil.memAlloc(bytes.length).put(bytes).flip();
		int off = stbtt_GetFontOffsetForIndex(data, index);
		if (off < 0 || !stbtt_InitFont(info, data, off)) {
			MemoryUtil.memFree(data);
			throw new IOException("TTF 해석 실패: " + file + " (index " + index + ")");
		}
		this.px = px;
		this.scale = stbtt_ScaleForMappingEmToPixels(info, px);
	}

	/** em 픽셀 크기. */
	public int px() {
		return px;
	}

	public boolean has(int cp) {
		return stbtt_FindGlyphIndex(info, cp) != 0;
	}

	/** 코드포인트를 굽는다 (없으면 null). 공백처럼 그림이 없으면 w = h = 0. */
	public Glyph bake(int cp) {
		int g = stbtt_FindGlyphIndex(info, cp);
		if (g == 0) {
			return null;
		}
		try (MemoryStack st = MemoryStack.stackPush()) {
			IntBuffer adv = st.mallocInt(1), lsb = st.mallocInt(1);
			stbtt_GetGlyphHMetrics(info, g, adv, lsb);
			IntBuffer x0 = st.mallocInt(1), y0 = st.mallocInt(1), x1 = st.mallocInt(1), y1 = st.mallocInt(1);
			stbtt_GetGlyphBitmapBox(info, g, scale, scale, x0, y0, x1, y1);
			int w = Math.max(0, x1.get(0) - x0.get(0)), h = Math.max(0, y1.get(0) - y0.get(0));
			byte[] alpha = new byte[w * h];
			if (w > 0 && h > 0) {
				ByteBuffer out = MemoryUtil.memAlloc(w * h);
				stbtt_MakeGlyphBitmap(info, out, w, h, w, scale, scale, g);
				out.get(alpha);
				MemoryUtil.memFree(out);
			}
			return new Glyph(w, h, x0.get(0), y0.get(0), adv.get(0) * scale, alpha);
		}
	}

	@Override
	public void close() {
		MemoryUtil.memFree(data);
	}
}
