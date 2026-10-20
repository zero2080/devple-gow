package devple.gow.engine.ui;

import devple.gow.engine.render.GlStats;
import devple.gow.engine.render.Mesh;
import devple.gow.engine.render.Shader;
import devple.gow.tools.content.Content;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.opengl.GL41C.*;

/**
 * TTF 글자 그리기 (49 문서 §7). 글리프는 처음 쓸 때 굽어 동적 아틀라스 (1024² RGBA, 흰색 + 알파) 에 올리고, 장이 차면 새 장을 연다.
 * 라벨 하나의 글자를 장마다 모아 basic 셰이더로 한 번에 그린다 (정점색 1 × uTint 라벨색 × 텍스처 — FLP 글리프와 같은 색·변환).
 * GL 스레드 전용. font.properties 가 없으면 {@link #shared()} = null 이라 아무것도 만들지 않는다.
 */
public final class TtfFont {

	private static final int PAGE = 1024, PAD = 1;

	/** 아틀라스 자리: 장, UV, 펜·기준선 기준 사각형 (px), 전진 폭 (px). page −1 = 그림 없음 (공백). */
	private record Entry(int page, float u0, float v0, float u1, float v1, int x0, int y0, int w, int h, float adv) {
	}

	private static final Entry NONE = new Entry(-2, 0, 0, 0, 0, 0, 0, 0, 0, 0);

	private static TtfFont shared;
	private static Content sharedFor;
	private static boolean sharedLoaded;

	private final FontOverride cfg;
	private final TtfGlyphs glyphs;
	private final Map<Integer, Entry> cache = new HashMap<>();
	private final List<Integer> pages = new ArrayList<>();
	private GlyphPacker packer;
	private float[][] verts = new float[0][];
	private int[] counts = new int[0];
	private int vao, vbo;
	private final Matrix4f model = new Matrix4f();

	private TtfFont(FontOverride cfg, TtfGlyphs glyphs) {
		this.cfg = cfg;
		this.glyphs = glyphs;
	}

	/** 계층 설정의 TTF (없거나 실패면 null). Content 가 바뀌면 다시 읽는다. */
	public static TtfFont shared() {
		Content c = Content.get();
		if (!sharedLoaded || sharedFor != c) {
			sharedLoaded = true;
			sharedFor = c;
			shared = null;
			FontOverride o = FontOverride.load();
			if (o != null) {
				try {
					shared = new TtfFont(o, new TtfGlyphs(o.ttf(), o.index(), o.bakeSize()));
					System.out.println("[font] TTF " + o.ttf() + " (" + (o.all() ? "모든 글자" : "FLP 에 없는 글자") + ")");
				} catch (IOException | RuntimeException e) {
					System.err.println("[font] " + e.getMessage() + " — FLP 폰트 그대로");
				}
			}
		}
		return shared;
	}

	/** 이 글자를 TTF 로 그릴지: all 이면 TTF 에 있는 글자, missing 이면 FLP 에 없고 TTF 에 있는 글자. */
	public boolean covers(char ch, boolean inFlp) {
		return (cfg.all() || !inFlp) && entry(ch) != NONE;
	}

	/** 글자 높이 h (em) 일 때 전진 폭. */
	public float advance(char ch, float h) {
		return entry(ch).adv() * unit(h);
	}

	private float unit(float h) {
		return h * cfg.scale() / glyphs.px();
	}

	/** 펜 (x, base) 에 글자 사각형을 모은다 — {@link #flush} 때 그린다. */
	public void add(char ch, float x, float base, float h) {
		Entry e = entry(ch);
		if (e.page() < 0 || e.w() == 0) {
			return;
		}
		float s = unit(h);
		float x0 = x + e.x0() * s, y0 = base + e.y0() * s + cfg.yOffset() * h;
		float x1 = x0 + e.w() * s, y1 = y0 + e.h() * s;
		int p = e.page();
		float[] v = verts[p];
		int n = counts[p];
		if (n + 6 * Mesh.STRIDE > v.length) {
			v = verts[p] = Arrays.copyOf(v, Math.max(v.length * 2, 64 * 6 * Mesh.STRIDE));
		}
		n = vtx(v, n, x0, y0, e.u0(), e.v0());
		n = vtx(v, n, x1, y0, e.u1(), e.v0());
		n = vtx(v, n, x1, y1, e.u1(), e.v1());
		n = vtx(v, n, x0, y0, e.u0(), e.v0());
		n = vtx(v, n, x1, y1, e.u1(), e.v1());
		n = vtx(v, n, x0, y1, e.u0(), e.v1());
		counts[p] = n;
	}

	private static int vtx(float[] v, int n, float x, float y, float u, float t) {
		v[n] = x;
		v[n + 1] = y;
		v[n + 2] = 0;
		v[n + 3] = 0;
		v[n + 4] = 0;
		v[n + 5] = 1;
		v[n + 6] = u;
		v[n + 7] = t;
		v[n + 8] = 1;
		v[n + 9] = 1;
		v[n + 10] = 1;
		v[n + 11] = 1;
		return n + Mesh.STRIDE;
	}

	/** 모은 글자를 장마다 한 번에 그린다 (m = 라벨 변환 2×3, tint = 라벨색). */
	public void flush(Shader shader, float[] m, Vector4f tint) {
		boolean any = false;
		for (int c : counts) {
			any |= c > 0;
		}
		if (!any) {
			return;
		}
		if (vao == 0) {
			vao = GlStats.vao(glGenVertexArrays());
			glBindVertexArray(vao);
			vbo = GlStats.buf(glGenBuffers());
			glBindBuffer(GL_ARRAY_BUFFER, vbo);
			int stride = Mesh.STRIDE * Float.BYTES;
			int[][] at = {{0, 3, 0}, {1, 3, 3}, {2, 2, 6}, {3, 4, 8}};
			for (int[] a : at) {
				glEnableVertexAttribArray(a[0]);
				glVertexAttribPointer(a[0], a[1], GL_FLOAT, false, stride, (long) a[2] * Float.BYTES);
			}
		}
		model.set(m[0], m[1], 0, 0, m[2], m[3], 0, 0, 0, 0, 1, 0, m[4], m[5], 0, 1);
		shader.set("uModel", model);
		shader.set("uTint", tint);
		shader.set("uUseTex", 1);
		glActiveTexture(GL_TEXTURE0);
		glBindVertexArray(vao);
		glBindBuffer(GL_ARRAY_BUFFER, vbo);
		for (int p = 0; p < counts.length; p++) {
			int n = counts[p];
			if (n == 0) {
				continue;
			}
			glBindTexture(GL_TEXTURE_2D, pages.get(p));
			FloatBuffer fb = MemoryUtil.memAllocFloat(n).put(verts[p], 0, n).flip();
			glBufferData(GL_ARRAY_BUFFER, fb, GL_STREAM_DRAW);
			MemoryUtil.memFree(fb);
			glDrawArrays(GL_TRIANGLES, 0, n / Mesh.STRIDE);
			counts[p] = 0;
		}
		glBindVertexArray(0);
	}

	private Entry entry(char ch) {
		Entry e = cache.get((int) ch);
		if (e == null) {
			e = bake(ch);
			cache.put((int) ch, e);
		}
		return e;
	}

	private Entry bake(int cp) {
		TtfGlyphs.Glyph g = glyphs.bake(cp);
		if (g == null) {
			return NONE;
		}
		if (g.w() == 0 || g.h() == 0) {
			return new Entry(-1, 0, 0, 0, 0, 0, 0, 0, 0, g.advance());
		}
		int[] at = packer == null ? null : packer.place(g.w(), g.h());
		if (at == null) {
			newPage();
			at = packer.place(g.w(), g.h());
			if (at == null) {
				return new Entry(-1, 0, 0, 0, 0, 0, 0, 0, 0, g.advance()); // 장보다 큰 글리프
			}
		}
		int page = pages.size() - 1;
		ByteBuffer rgba = MemoryUtil.memAlloc(g.w() * g.h() * 4);
		for (byte a : g.alpha()) {
			rgba.put((byte) 255).put((byte) 255).put((byte) 255).put(a);
		}
		rgba.flip();
		glBindTexture(GL_TEXTURE_2D, pages.get(page));
		glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
		glTexSubImage2D(GL_TEXTURE_2D, 0, at[0], at[1], g.w(), g.h(), GL_RGBA, GL_UNSIGNED_BYTE, rgba);
		MemoryUtil.memFree(rgba);
		float inv = 1f / PAGE;
		return new Entry(page, at[0] * inv, at[1] * inv, (at[0] + g.w()) * inv, (at[1] + g.h()) * inv,
				g.xoff(), g.yoff(), g.w(), g.h(), g.advance());
	}

	private void newPage() {
		int id = GlStats.tex(glGenTextures());
		glActiveTexture(GL_TEXTURE0);
		glBindTexture(GL_TEXTURE_2D, id);
		ByteBuffer zero = MemoryUtil.memCalloc(PAGE * PAGE * 4);
		glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, PAGE, PAGE, 0, GL_RGBA, GL_UNSIGNED_BYTE, zero);
		MemoryUtil.memFree(zero);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		pages.add(id);
		packer = new GlyphPacker(PAGE, PAGE, PAD);
		verts = Arrays.copyOf(verts, pages.size());
		verts[pages.size() - 1] = new float[0];
		counts = Arrays.copyOf(counts, pages.size());
	}
}
