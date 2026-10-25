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
 * 계층에 {@code font.properties} 가 있으면 글자를 TTF 아틀라스로 대신 그린다 ({@link TtfFont}, 49 문서 §7).
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
	/**
	 * 텍스처 갈아 끼우기 (원작 0x1B8178: HUD 머티리얼 슬롯 표의 텍스처 칸을 다른 텍스처로) — FLP 텍스처 이름 (TXR_*) → 대신 그릴 텍스처.
	 * 메시지 그림 MAT_FlashMat&lt;번호&gt; 를 PickUpInfoMenu 의 "GenericPickUp" 메시에 (0x1B80F8 이 그 이름으로 슬롯을 찾음, 0x1B96D0) [확실].
	 */
	private final Map<String, java.util.function.Supplier<Texture>> textureSwaps = new HashMap<>();

	/** name 텍스처를 그릴 때 대신 쓸 텍스처 (null = 되돌림). */
	public void swapTexture(String name, java.util.function.Supplier<Texture> t) {
		if (name == null) {
			return;
		}
		if (t == null) {
			textureSwaps.remove(name);
		} else {
			textureSwaps.put(name, t);
		}
	}

	/** FLP 자원 WAD 의 텍스처 (바꿔 끼우지 않은 원래 그림 — 저장 화면 빈 슬롯 그림 0x1B8138). */
	public Texture texture(String name) {
		return name != null ? assets.texture(name) : null;
	}

	/** 인스턴스 이름이 붙은 클립 (예 "GenericPickUp") 첫 레이어의 메시 머티리얼 텍스처 이름 (없으면 null). */
	public String clipTexture(String instanceName) {
		Integer h = iconClips.get(instanceName);
		if (h == null) {
			return null;
		}
		FlpFile.Timeline t = flp.clips.get(flp.handlers.get(h).index());
		for (FlpFile.Layer l : t.layers()) {
			for (FlpFile.KeyFrame k : l.keys()) {
				if (k.handler() < flp.handlers.size() && flp.handlers.get(k.handler()).type() == 1) {
					FlpFile.MeshRef ref = flp.meshRefs.get(flp.handlers.get(k.handler()).index());
					for (FlpFile.Material m : ref.materials()) {
						if (m.texture() != null) {
							return m.texture();
						}
					}
				}
			}
		}
		return null;
	}

	public FlpRenderer(FlpFile flp, WadAssets assets, String modelName) {
		this.flp = flp;
		this.assets = assets;
		// 자산이 이미 해석해 둔 모델 목록 (같은 GowModel.load 결과) — 예전엔 WAD 의 모든 MDL 을 다시 해석했다 (레벨 HUD FLP_Athn01B 10 ms, 43 §27)
		GowModel gm = assets.gowModel(modelName);
		if (gm == null) {
			throw new IllegalArgumentException("UI 모델 없음: " + modelName);
		}
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
				case 5 -> {
					// 아이콘 클립 안의 동적 라벨 — L2Button (clip 359) 의 'L2' 클립 358 = 라벨 _level0/:StaticTextL2, 값 "L2" 는 FLP_HUDK 루트 프레임 0
					// 액션의 SetVariable (L1/R1/L2/R2/L3/R3 · + = ( / 1–4 · R) [확실: FLP 덤프]. 예전엔 메시 (틀) 만 그려 배지에 글자가 없었다 (영상 8056 "L2")
					FlpFile.DynamicLabel dl = flp.dynamicLabels.get(h.index());
					String s = dl.variable() != null && player != null ? player.getVariable(dl.variable()) : null;
					drawText(dl, s == null || s.isEmpty() ? dl.placeholder() : s, m, col);
				}
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
		Texture tex = null;
		if (mat != null) {
			java.util.function.Supplier<Texture> sw = mat.texture() != null ? textureSwaps.get(mat.texture()) : null;
			tex = sw != null ? sw.get() : null;
			if (tex == null) {
				tex = assets.texture(mat.texture());
			}
		}
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
		TtfFont ttf = TtfFont.shared();
		if (font == null || font.glyphMeshes().isEmpty() && ttf == null) {
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
				w += advance(font, ttf, r, h);
			}
			float x = switch (dl.align()) {
				case 1 -> left + width - w;
				case 2 -> left + (width - w) / 2;
				default -> left;
			};
			int style = 0; // 줄마다 0 에서 (0x149868)
			float[] sc = tc;
			for (Object r : runs) {
				if (r instanceof Style st) {
					if (ttf != null && st.n() != style) { // 앞 글자는 앞 서체 색으로
						tint.set(sc[0], sc[1], sc[2], sc[3]);
						ttf.flush(shader, m, tint);
					}
					style = st.n();
					sc = styleColor(style, tc, col);
					if (ttf != null) {
						ttf.setShear(style != 0 ? ITALIC : 0f);
					}
					continue;
				}
				if (r instanceof Character ch) {
					int g = font.glyphOf(ch);
					if (ttf != null && ttf.covers(ch, g >= 0)) {
						ttf.add(ch, x, base, h);
					} else if (g >= 0 && ch != ' ') {
						float s = h / GLYPH_EM;
						drawMeshRef(font.glyphMeshes().get(g), mul(m, s, 0, style != 0 ? -ITALIC * s : 0, s, x, base), sc);
					}
				} else if (r instanceof String icon && drawIcons) {
					if (ttf != null) {
						// 아이콘 안의 라벨 (배지 글자) 도 같은 공유 글자 묶음을 쓰므로 앞 글자를 이 라벨 변환·색으로 먼저 그린다
						tint.set(sc[0], sc[1], sc[2], sc[3]);
						ttf.flush(shader, m, tint);
					}
					drawIcon(icon, x, base, h, ascent, m, col);
				}
				x += advance(font, ttf, r, h);
			}
			if (ttf != null && style != 0) {
				tint.set(sc[0], sc[1], sc[2], sc[3]);
				ttf.flush(shader, m, tint);
				ttf.setShear(0f);
			}
			base += lineH;
		}
		if (ttf != null) {
			tint.set(tc[0], tc[1], tc[2], tc[3]);
			ttf.flush(shader, m, tint);
		}
	}

	/** 글자 서체 태그 [*n] (0 = 보통). */
	record Style(int n) {
	}

	/**
	 * 글자 서체 [*n] (원작 텍스트 배치 0x1494A8: '[' 뒤 '*' 면 다음 숫자 − '0' 을 글자 레코드 +0x6 에 — 0x1498AC–0x1498DC, "[*]" 는 0; 그리기 0x148388 이
	 * 그 번호로 세 벌의 행렬 · 색 중 하나를 고른다) [확실: 코드]: 0 = 라벨 색 · 보통, 1 = 고정 색 (0xDC, 0x6E, 0x00) (0x29CCDC) · 기울임, 2 = (0x00, 0xDC, 0xDC)
	 * (0x29CCE4) · 기울임. 색은 (바이트 + 1) × 부모 색 변환 ≫ 8, 알파 = 부모 알파 (0x148794–0x1488C4), 기울임 = x 를 −0.2 · y 만큼 (0x29CCE0 / 0x29CCE8 = 0.2
	 * 를 부호를 바꿔 행렬에, 0x148A0C · 0x148AA4). 예 책 126 의 "[*1]이 신전은 …" · 카드 267 "[*1]아르테미스의 검" 은 붉은 기울임 (영상 3614 · 3998).
	 * 예전 엔진은 "인자 자리" 로 보고 버렸다 (43 §33).
	 */
	static final float ITALIC = 0.2f;
	static final int[][] STYLE_RGB = {null, {0xDC, 0x6E, 0x00}, {0x00, 0xDC, 0xDC}};

	static float[] styleColor(int style, float[] labelColor, float[] col) {
		if (style <= 0 || style >= STYLE_RGB.length) {
			return labelColor;
		}
		int[] c = STYLE_RGB[style];
		return new float[]{col[0] * (c[0] + 1) / 256f, col[1] * (c[1] + 1) / 256f, col[2] * (c[2] + 1) / 256f, col[3]};
	}

	/** 문자, 버튼 태그 이름(String), 서체 태그 ({@link Style}) 나열. */
	static List<Object> tokenize(String line) {
		List<Object> out = new ArrayList<>();
		for (int i = 0; i < line.length(); i++) {
			char ch = line.charAt(i);
			if (ch == '[') {
				int e = line.indexOf(']', i);
				if (e > i) {
					String tag = line.substring(i + 1, e);
					if (tag.startsWith("*")) {
						// "[*]" = 0, "[*n" = 숫자 − '0' (원작은 '*' 다음 한 글자만 본다)
						out.add(new Style(tag.length() > 1 ? Math.max(0, tag.charAt(1) - '0') : 0));
					} else {
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

	private static float advance(FlpFile.Font f, TtfFont ttf, Object r, float h) {
		if (r instanceof Style) {
			return 0;
		}
		if (r instanceof Character ch) {
			int g = f.glyphOf(ch);
			if (ttf != null && ttf.covers(ch, g >= 0)) {
				return ttf.advance(ch, h);
			}
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
