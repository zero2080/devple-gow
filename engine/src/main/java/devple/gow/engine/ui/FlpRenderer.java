package devple.gow.engine.ui;

import devple.gow.engine.asset.WadAssets;
import devple.gow.engine.render.Mesh;
import devple.gow.engine.render.Shader;
import devple.gow.engine.render.Texture;
import devple.gow.tools.model.GowMesh;
import devple.gow.tools.model.GowModel;
import devple.gow.tools.model.MeshObject;
import devple.gow.tools.model.VertexBatch;
import devple.gow.tools.ui.FlpFile;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.lwjgl.opengl.GL41C.*;

/**
 * FLP 2D 렌더링. 가상 화면 800×600 (y 아래로, FLP 좌표 그대로) → 창에 4:3 레터박스.
 * <p>
 * 이미지·글리프는 모두 {@code MDL_<FLP이름>} 모델의 메시 파트(그룹 0)이며, 파트의 오브젝트 i 는 FLP 메시 참조의
 * 머티리얼 i (블렌드 컬러 ARGB + TXR) 로 그린다. 동적 라벨은 폰트 글리프 파트를 펜 위치에 배치한다
 * (글리프 메시 단위: em = 64, 폭 테이블 1024 = em, 기준선 y = 0).
 * 셰이더는 엔진 공용 basic (정점색 × uTint × 텍스처).
 */
public final class FlpRenderer implements AutoCloseable {

	public static final float SCREEN_W = 800, SCREEN_H = 600;
	/** 글리프 메시 em 크기 (폭 테이블 1024 = 메시 64). */
	private static final float GLYPH_EM = 64f;
	/** EditText 테두리 여백 (1/16 px 40 = 2.5px, rect 의 -40 과 일치). */
	private static final float GUTTER = 2.5f;

	private record Obj(Mesh mesh) {
	}

	private final FlpFile flp;
	private final WadAssets assets;
	private final GowMesh mesh;
	private final Map<Integer, List<Obj>> parts = new HashMap<>();
	private final Map<String, Integer> iconClips = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
	private final Matrix4f proj = new Matrix4f().ortho(0, SCREEN_W, SCREEN_H, 0, -1, 1);
	private final Matrix4f model = new Matrix4f();
	private final Vector4f tint = new Vector4f();
	private Texture white;
	private Shader shader;
	private FlpPlayer player;
	/** 버튼 태그([CircleButton] 등)를 FLP 클립으로 그릴지. */
	public boolean drawIcons = true;

	public FlpRenderer(FlpFile flp, WadAssets assets, String modelName) {
		this.flp = flp;
		this.assets = assets;
		GowModel gm = GowModel.load(assets.wad()).stream().filter(m -> m.name.equals(modelName)).findFirst()
				.orElseThrow(() -> new IllegalArgumentException("UI 모델 없음: " + modelName));
		this.mesh = gm.meshes.getFirst();
		indexIcons(flp.root);
		for (FlpFile.Timeline t : flp.clips) {
			indexIcons(t);
		}
	}

	/** 인스턴스 이름이 붙은 클립 → 핸들러 (메시지 태그 [이름] 인라인 아이콘용). */
	private void indexIcons(FlpFile.Timeline t) {
		for (FlpFile.Layer l : t.layers()) {
			for (FlpFile.KeyFrame k : l.keys()) {
				if (k.name() != null && k.handler() < flp.handlers.size() && flp.handlers.get(k.handler()).type() == 7) {
					iconClips.putIfAbsent(k.name(), k.handler());
				}
			}
		}
	}

	// ---------------------------------------------------------------- 그리기

	/** 플레이어 전체를 그린다. 창 크기(프레임버퍼 픽셀)에 4:3 레터박스. */
	public void render(FlpPlayer p, Shader basic, Texture whiteTex, int winW, int winH) {
		this.player = p;
		this.shader = basic;
		this.white = whiteTex;
		float scale = Math.min(winW / SCREEN_W, winH / SCREEN_H);
		int vw = Math.round(SCREEN_W * scale), vh = Math.round(SCREEN_H * scale);
		glViewport((winW - vw) / 2, (winH - vh) / 2, vw, vh);
		glDisable(GL_DEPTH_TEST);
		glDepthMask(false);
		glEnable(GL_BLEND);
		glBlendEquation(GL_FUNC_ADD);
		glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
		basic.use();
		basic.set("uTex", 0);
		basic.set("uViewProj", proj);
		basic.set("uLit", 0);
		draw(p.root, new float[]{1, 0, 0, 1, 0, 0}, new float[]{1, 1, 1, 1});
		glDisable(GL_BLEND);
		glDepthMask(true);
		glEnable(GL_DEPTH_TEST);
		glViewport(0, 0, winW, winH);
		basic.set("uTint", new Vector4f(1, 1, 1, 1));
	}

	private int warmRef;

	/**
	 * 페이드 동안 (48 문서 §9.7) 모든 메시 참조 (+ 글꼴마다 첫 글리프) 를 한 번씩 그려 파트 메시·텍스처 (R_PERMK 해독·GL 생성) 를 첫 화면 전에
	 * 만든다 — {@link devple.gow.engine.render.GpuWarmup} FBO 안에서 부른다. 값·그리기 결과는 바뀌지 않는다 (같은 캐시를 미리 채울 뿐).
	 *
	 * @return 다 했으면 true (deadline 이 지나면 다음에 이어서)
	 */
	public boolean warm(Shader basic, Texture whiteTex, long deadline) {
		this.shader = basic;
		this.white = whiteTex;
		glDisable(GL_DEPTH_TEST);
		glDepthMask(false);
		glEnable(GL_BLEND);
		glBlendEquation(GL_FUNC_ADD);
		glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
		basic.use();
		basic.set("uTex", 0);
		basic.set("uViewProj", proj);
		basic.set("uLit", 0);
		float[] id = {1, 0, 0, 1, 0, 0}, col = {1, 1, 1, 1};
		boolean done = false;
		try {
			int n = flp.meshRefs.size();
			while (warmRef < n + flp.fonts.size()) {
				if (System.nanoTime() >= deadline) {
					return false;
				}
				int r = warmRef++;
				if (r < n) {
					drawMeshRef(flp.meshRefs.get(r), id, col);
				} else if (!flp.fonts.get(r - n).glyphMeshes().isEmpty()) {
					drawMeshRef(flp.fonts.get(r - n).glyphMeshes().getFirst(), id, col);
				}
			}
			done = true;
		} finally {
			glDisable(GL_BLEND);
			glDepthMask(true);
			glEnable(GL_DEPTH_TEST);
			basic.set("uTint", new Vector4f(1, 1, 1, 1));
		}
		return done;
	}

	private static float[] mul(float[] p, float a, float b, float c, float d, float tx, float ty) {
		return new float[]{
				p[0] * a + p[2] * b, p[1] * a + p[3] * b,
				p[0] * c + p[2] * d, p[1] * c + p[3] * d,
				p[0] * tx + p[2] * ty + p[4], p[1] * tx + p[3] * ty + p[5]};
	}

	private void draw(FlpNode n, float[] pm, float[] pc) {
		float[] m = mul(pm, n.a, n.b, n.c, n.d, n.tx, n.ty);
		float[] col = {pc[0] * n.cr, pc[1] * n.cg, pc[2] * n.cb, pc[3] * n.ca};
		if (col[3] <= 0.002f) {
			return;
		}
		switch (n.kind) {
			case MOVIE -> {
				for (FlpNode s : n.slots) {
					if (s != null) {
						draw(s, m, col);
					}
				}
				for (FlpNode s : n.attached) {
					draw(s, m, col);
				}
			}
			case MESH -> {
				if (n.index >= 0) {
					drawMeshRef(flp.meshRefs.get(n.index), m, col);
				}
			}
			case TEXT -> drawText(flp.dynamicLabels.get(n.index), player.textOf(n), m, col);
		}
	}

	/** 노드 없이 타임라인의 frame 을 정적으로 그린다 (인라인 아이콘). */
	private void drawStatic(FlpFile.Timeline t, int frame, float[] pm, float[] pc) {
		for (FlpFile.Layer l : t.layers()) {
			FlpFile.KeyFrame key = null;
			for (FlpFile.KeyFrame k : l.keys()) {
				if (k.untilFrame() <= frame) {
					key = k;
				}
			}
			if (key == null || key.handler() >= flp.handlers.size()) {
				continue;
			}
			FlpFile.Handler h = flp.handlers.get(key.handler());
			FlpFile.Transform x = key.transform() < flp.transforms.size() ? flp.transforms.get(key.transform()) : null;
			float[] m = x == null ? pm : mul(pm, x.a(), x.b(), x.c(), x.d(), x.x(), x.y());
			int[] cc = key.color() < flp.colors.size() ? flp.colors.get(key.color()) : new int[]{256, 256, 256, 256};
			float[] col = {pc[0] * cc[0] / 256f, pc[1] * cc[1] / 256f, pc[2] * cc[2] / 256f, pc[3] * cc[3] / 256f};
			if (col[3] <= 0.002f) {
				continue;
			}
			switch (h.type()) {
				case 1 -> drawMeshRef(flp.meshRefs.get(h.index()), m, col);
				case 7 -> drawStatic(flp.clips.get(h.index()), 0, m, col);
				default -> {
				}
			}
		}
	}

	private void drawMeshRef(FlpFile.MeshRef ref, float[] m, float[] col) {
		List<Obj> objs = part(ref.meshPart());
		for (int i = 0; i < objs.size(); i++) {
			FlpFile.Material mat = ref.materials().isEmpty() ? null
					: ref.materials().get(Math.min(i, ref.materials().size() - 1));
			drawObj(objs.get(i), mat, m, col);
		}
	}

	private void drawObj(Obj o, FlpFile.Material mat, float[] m, float[] col) {
		if (o.mesh() == null) {
			return;
		}
		int argb = mat != null ? mat.color() : 0xFFFFFFFF;
		tint.set(col[0] * ((argb >> 16) & 0xFF) / 255f, col[1] * ((argb >> 8) & 0xFF) / 255f,
				col[2] * (argb & 0xFF) / 255f, col[3] * ((argb >>> 24) & 0xFF) / 255f);
		Texture tex = mat != null ? assets.texture(mat.texture()) : null;
		if (tex != null) {
			tex.bind(0);
			// UI 아틀라스는 가장자리 반복 필터링 시 이음선이 생겨 클램프 (UI 전용 텍스처라 공유 상태 변경 무해)
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
			shader.set("uUseTex", 1);
		} else {
			white.bind(0);
			shader.set("uUseTex", 0);
		}
		model.set(m[0], m[1], 0, 0, m[2], m[3], 0, 0, 0, 0, 1, 0, m[4], m[5], 0, 1);
		shader.set("uModel", model);
		shader.set("uTint", tint);
		o.mesh().draw();
	}

	// ---------------------------------------------------------------- 텍스트

	private void drawText(FlpFile.DynamicLabel dl, String text, float[] m, float[] col) {
		if (text == null || text.isEmpty()) {
			return;
		}
		FlpFile.Font font = flp.fontOf(dl.fontHandler());
		if (font == null || font.glyphMeshes().isEmpty()) {
			return;
		}
		int c = dl.color();
		float[] tc = {col[0] * ((c >> 16) & 0xFF) / 255f, col[1] * ((c >> 8) & 0xFF) / 255f,
				col[2] * (c & 0xFF) / 255f, col[3] * ((c >>> 24) & 0xFF) / 255f};
		float h = dl.fontHeight() / 16f;
		float ascent = font.size() / 1024f * h;
		float lineH = h + dl.leading() / 16f;
		float left = dl.rect()[0] / 16f + GUTTER;
		float width = dl.rect()[2] / 16f - 2 * GUTTER;
		float base = dl.rect()[1] / 16f + GUTTER + ascent;
		String[] lines = text.replace("\r", "").split("\n");
		for (String line : lines) {
			List<Object> runs = tokenize(line);
			float w = 0;
			for (Object r : runs) {
				w += advance(font, r, h);
			}
			float x = switch (dl.align()) {
				case 1 -> left + width - w;
				case 2 -> left + (width - w) / 2;
				default -> left;
			};
			for (Object r : runs) {
				if (r instanceof Character ch) {
					int g = font.glyphOf(ch);
					if (g >= 0 && ch != ' ') {
						float s = h / GLYPH_EM;
						drawMeshRef(font.glyphMeshes().get(g), mul(m, s, 0, 0, s, x, base), tc);
					}
				} else if (r instanceof String icon && drawIcons) {
					drawIcon(icon, x, base, h, ascent, m, col);
				}
				x += advance(font, r, h);
			}
			base += lineH;
		}
	}

	/** 문자 또는 버튼 태그 이름(String) 나열. [*1] 같은 인자 자리는 버린다. */
	private List<Object> tokenize(String line) {
		List<Object> out = new ArrayList<>();
		for (int i = 0; i < line.length(); i++) {
			char ch = line.charAt(i);
			if (ch == '[') {
				int e = line.indexOf(']', i);
				if (e > i) {
					String tag = line.substring(i + 1, e);
					if (!tag.startsWith("*")) {
						out.add(tag);
					}
					i = e;
					continue;
				}
			}
			out.add(ch);
		}
		return out;
	}

	private static float advance(FlpFile.Font f, Object r, float h) {
		if (r instanceof Character ch) {
			int g = f.glyphOf(ch);
			return g >= 0 ? f.widths()[g] / 1024f * h : h * 0.33f;
		}
		return h * 1.25f;
	}

	/** 버튼/아이콘 태그 → 같은 이름의 FLP 클립 frame 0 을 글자 높이에 맞춰 그린다. */
	private void drawIcon(String name, float x, float base, float h, float ascent, float[] m, float[] col) {
		Integer hi = iconClips.get(name);
		if (hi == null) {
			return;
		}
		FlpFile.Timeline t = flp.clips.get(flp.handlers.get(hi).index());
		int[] bb = t.bounds();
		float bw = (bb[2] - bb[0]) / 16f, bh = (bb[3] - bb[1]) / 16f;
		if (bw <= 0 || bh <= 0) {
			return;
		}
		float target = h * 1.15f;
		float s = target / Math.max(bw, bh);
		float cx = x + h * 0.625f, cy = base - ascent * 0.45f;
		float ox = cx - (bb[0] + bb[2]) / 32f * s, oy = cy - (bb[1] + bb[3]) / 32f * s;
		drawStatic(t, 0, mul(m, s, 0, 0, s, ox, oy), col);
	}

	// ---------------------------------------------------------------- 메시 파트

	private List<Obj> part(int p) {
		return parts.computeIfAbsent(p, this::buildPart);
	}

	private List<Obj> buildPart(int p) {
		List<Obj> out = new ArrayList<>();
		if (p < 0 || p >= mesh.parts.size()) {
			return out;
		}
		GowMesh.Part part = mesh.parts.get(p);
		if (part.groups().isEmpty()) {
			return out;
		}
		for (MeshObject o : part.groups().getFirst().objects()) {
			List<float[]> verts = new ArrayList<>();
			List<Integer> idx = new ArrayList<>();
			for (VertexBatch vb : o.batches) {
				int base = verts.size();
				for (int i = 0; i < vb.count; i++) {
					float[] uv = vb.uv != null ? vb.uv[i] : new float[2];
					int[] c = vb.color != null ? vb.color[i] : new int[]{128, 128, 128, 128};
					verts.add(new float[]{vb.x(i), vb.y(i), 0, 0, 0, 1, uv[0], uv[1],
							Math.min(c[0] / 128f, 2f), Math.min(c[1] / 128f, 2f), Math.min(c[2] / 128f, 2f), Math.min(c[3] / 128f, 1f)});
				}
				for (int t : vb.triangles()) {
					idx.add(base + t);
				}
			}
			float[] v = new float[verts.size() * Mesh.STRIDE];
			for (int i = 0; i < verts.size(); i++) {
				System.arraycopy(verts.get(i), 0, v, i * Mesh.STRIDE, Mesh.STRIDE);
			}
			int[] ix = idx.stream().mapToInt(Integer::intValue).toArray();
			out.add(new Obj(ix.length == 0 ? null : new Mesh(v, ix, GL_TRIANGLES)));
		}
		return out;
	}

	@Override
	public void close() {
		parts.values().forEach(l -> l.forEach(o -> {
			if (o.mesh() != null) {
				o.mesh().close();
			}
		}));
		parts.clear();
	}
}
