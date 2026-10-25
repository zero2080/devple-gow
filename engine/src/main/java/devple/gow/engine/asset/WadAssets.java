package devple.gow.engine.asset;

import devple.gow.engine.fx.MaterialFx;
import devple.gow.engine.render.Mesh;
import devple.gow.engine.render.Texture;
import devple.gow.tools.fx.MaterialAnim;
import devple.gow.tools.model.GowModel;
import devple.gow.tools.content.TextureOverrides;
import devple.gow.tools.model.TriMesh;
import devple.gow.tools.texture.MaterialResource;
import devple.gow.tools.texture.TextureDecoder;
import devple.gow.tools.texture.TextureLibrary;
import devple.gow.tools.wad.WadFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.opengl.GL41C.GL_TRIANGLES;

/**
 * WAD 하나를 엔진 리소스(GL 텍스처, 렌더 모델)로 변환·캐시한다.
 * 파싱은 tools 모듈(03/04 단계 분석 결과)을 그대로 사용.
 */
public final class WadAssets implements AutoCloseable {

	private final WadFile wad;
	private final TextureLibrary textures;
	private final Map<String, MaterialResource> materials = new HashMap<>();
	private final Map<String, Texture> glTextures = new HashMap<>();
	private final Map<String, RenderModel> models = new HashMap<>();
	private final List<GowModel> gowModels;
	private final Map<String, Texture[]> glFrames = new HashMap<>();
	/** 머티리얼 ANM (플립북/UV 스크롤/불투명도, 16 문서 §3). */
	private final Map<String, MaterialAnim.Anim> matAnims;

	public WadAssets(Path wadPath) throws IOException {
		Parsed p = takePrefetched(wadPath);
		if (p == null) {
			p = Parsed.of(wadPath);
		}
		this.wad = p.wad;
		this.textures = p.textures;
		for (int i = 0; i < textures.materials().size(); i++) {
			MaterialResource m = textures.materials().get(i);
			if (materials.putIfAbsent(m.name(), m) != null) {
				// 같은 이름의 두 번째 정의 (R_MIN MAT_body = 석상 돌 텍스처) — GowModel 이 MDL 앞 가장 가까운 정의를 이 별칭으로 가리킨다
				materials.put(TextureLibrary.materialAlias(m.name(), textures.materialTag(i)), m);
			}
		}
		this.gowModels = p.models;
		this.matAnims = p.matAnims;
	}

	/** GL 없는 해석 결과 (WAD 파일·텍스처 목록·MDL·머티리얼 ANM). 한 WadAssets 가 한 번만 쓴다 (나눠 쓰지 않음). */
	private record Parsed(WadFile wad, TextureLibrary textures, List<GowModel> models, Map<String, MaterialAnim.Anim> matAnims) {
		static Parsed of(Path wadPath) throws IOException {
			WadFile w = WadFile.load(wadPath);
			return new Parsed(w, new TextureLibrary(w), GowModel.load(w), MaterialAnim.load(w));
		}
	}

	/** 미리 해석 중인 WAD (경로 → 대기열, 한 번 꺼내면 끝) — 48 문서 §8. */
	private static final Map<Path, java.util.ArrayDeque<java.util.concurrent.Future<Parsed>>> PREFETCH = new HashMap<>();

	/**
	 * 레벨 전환 페이드 동안 백그라운드로 해석해 둔다 ({@link Loader}). 다음 {@code new WadAssets(같은 경로)} 가 그 결과를 쓴다
	 * (같은 파일을 같은 코드로 해석한 값 — 동기와 같음). 없는 파일은 무시.
	 */
	public static void prefetch(Path wadPath) {
		prefetch(wadPath, null);
	}

	/**
	 * {@link #prefetch(Path)} + 해석한 WadFile 에 GL 없는 파생 해석을 더 붙인다 (예: 이펙트 GO 표 {@code EffectLibrary::preparse}, 48 문서 §9).
	 */
	public static void prefetch(Path wadPath, java.util.function.Consumer<WadFile> derive) {
		if (!Loader.async() || !java.nio.file.Files.exists(wadPath)) {
			return;
		}
		Path key = wadPath.toAbsolutePath().normalize();
		var f = Loader.submit("wad " + key.getFileName(), () -> {
			Parsed p = Parsed.of(key);
			if (derive != null) {
				derive.accept(p.wad());
			}
			return p;
		});
		synchronized (PREFETCH) {
			PREFETCH.computeIfAbsent(key, k -> new java.util.ArrayDeque<>()).add(f);
		}
	}

	private static Parsed takePrefetched(Path wadPath) {
		java.util.concurrent.Future<Parsed> f;
		synchronized (PREFETCH) {
			if (PREFETCH.isEmpty()) {
				return null;
			}
			Path key = wadPath.toAbsolutePath().normalize();
			var q = PREFETCH.get(key);
			f = q == null ? null : q.poll();
			if (q != null && q.isEmpty()) {
				PREFETCH.remove(key);
			}
		}
		if (f == null || (!f.isDone() && Loader.onLoaderThread())) { // 적재 스레드끼리 기다리지 않는다 → 그 자리에서 해석
			return null;
		}
		return Loader.join(f, "wad " + wadPath.getFileName());
	}

	/** 쓰이지 않은 미리 해석 결과를 버린다 (GL 없음, 힙만). 레벨 진입 끝에 — 다음 레벨까지 붙잡지 않게. */
	public static void dropPrefetched() {
		synchronized (PREFETCH) {
			PREFETCH.clear();
		}
	}

	private TextureLibrary.Entry entry(String txrName) {
		for (TextureLibrary.Entry e : textures.textures()) {
			if (e.txr().name().equals(txrName)) {
				return e;
			}
		}
		return null;
	}

	/**
	 * 애니메이션 텍스처(GFX frames &gt; 1)의 프레임별 GL 텍스처 (세로 스트립을 frames 개로 자름, GFX 생성 0x14C588). 정지 텍스처면 null.
	 */
	public Texture[] textureFrames(String txrName) {
		if (txrName == null || txrName.isEmpty()) {
			return null;
		}
		return glFrames.computeIfAbsent(txrName, n -> {
			TextureLibrary.Entry e = entry(n);
			if (e == null || e.gfx().frames() <= 1) {
				return null;
			}
			int w = e.gfx().width(), fh = e.gfx().height() / e.gfx().frames();
			Texture[] out = new Texture[e.gfx().frames()];
			// 모드 PNG (49 문서 §14): 프레임별 <이름>.<f>.png, 없으면 <이름>.png 세로 스트립을 프레임 수로 자름
			java.awt.image.BufferedImage strip = TextureOverrides.any() ? TextureOverrides.load(wad.name(), n, -1) : null;
			int[] argb = null;
			for (int f = 0; f < out.length; f++) {
				java.awt.image.BufferedImage ov = TextureOverrides.any() ? TextureOverrides.load(wad.name(), n, f) : null;
				if (ov == null && strip != null) {
					int sh = Math.max(1, strip.getHeight() / out.length);
					ov = strip.getSubimage(0, Math.min(f * sh, strip.getHeight() - sh), strip.getWidth(), sh);
				}
				if (ov != null) {
					out[f] = upload(TextureOverrides.fit(ov, w, fh), n + "#" + f);
					continue;
				}
				if (argb == null) {
					argb = TextureDecoder.decode(e.gfx(), e.pal(), 0);
				}
				int[] sub = new int[w * fh];
				System.arraycopy(argb, f * w * fh, sub, 0, Math.min(sub.length, argb.length - f * w * fh));
				out[f] = Texture.of(TextureDecoder.toImage(w, fh, sub), true).withNormalSource(n + "#" + f, w, fh, () -> sub);
			}
			return out;
		});
	}

	/** 머티리얼 ANM (없으면 null). */
	public MaterialAnim.Anim materialAnim(String mat) {
		return matAnims.get(mat);
	}

	/**
	 * 첫 레이어 블렌드 분류 (MAT 생성 0x1537C8 의 정렬 플래그 규칙, 16 문서 §3.3):
	 * 0x48 → ADD, 0x42 → SUB, p28 ≠ 1 · p38 ≠ 1 · TXR flags bit18 → ALPHA, 텍스처 없는 0x44 → OPAQUE, 그 외 → CUTOUT.
	 */
	/**
	 * 그림자 볼륨 전용 머티리얼 (MAT_stencil: 첫 레이어 flags2 bit 28 → 머티리얼 bit 60): 원작은 그림자 특수 패스에서 프레임 초록 채널에만 더한다
	 * (FBMSK 0xFFFF00FF, 0x160518 — 47 §7.4) — 색으로는 그리지 않는다 [확실]. 예 ATHN02A 배 MDL_shipGroup 의 MAT_stencil 판 (레이어 색 2.0):
	 * 스키닝 경로가 흰 판으로 그렸다 (배가 부두로 들어오는 동안 갑판 위).
	 */
	public static boolean stencilOnly(MaterialResource mat) {
		return mat != null && !mat.layers().isEmpty() && (mat.layers().getFirst().flags2() & 0x10000000) != 0;
	}

	/**
	 * 안개 뒤 층 (3·4) 머티리얼인가: 첫 레이어 flags2 하위 5 비트 (머티리얼 bit 32..36 = 그리기 층, 0 이면 층 2) 의 0x8 · 0x10 [확실: 0x1534BC / 0x160CC0].
	 * 원작 안개 노드는 층 2 칸 5 (16 §1.2) 라 층 3·4 머티리얼은 안개를 받지 않는다 — 디스크 MAT 130 개 (번개 R_LGHTN · 메두사 머리 R_MEDHD ·
	 * 썬더볼트 R_THBLT · R_PERMK 빛줄기 …, 레벨 일부). 이펙트 GO 만 이 규칙으로 거른다 ({@code AnimatedModel.sceneFog}).
	 */
	public static boolean postFogLayer(MaterialResource mat) {
		return mat != null && !mat.layers().isEmpty() && (mat.layers().getFirst().flags2() & 0x18) != 0;
	}

	/** 그림자 합성 뒤에 그리는 머티리얼인가 (첫 레이어, 0x155B50 — {@link devple.gow.engine.render.DrawStage#lateMaterial}, 47 §7.4.2). */
	public static boolean lateStage(MaterialResource mat) {
		if (mat == null || mat.layers().isEmpty()) {
			return false;
		}
		MaterialResource.Layer l = mat.layers().getFirst();
		return devple.gow.engine.render.DrawStage.lateMaterial(l.renderFlags(), l.flags2());
	}

	public RenderModel.Blend classify(MaterialResource mat, boolean textured) {
		MaterialResource.Layer l = mat.layers().getFirst();
		switch (l.gsAlpha()) {
			case 0x48:
				return RenderModel.Blend.ADD;
			case 0x42:
				return RenderModel.Blend.SUB;
			case 0x01:
				return RenderModel.Blend.INV;
			default:
				break;
		}
		TextureLibrary.Entry te = textured ? entry(l.texture()) : null;
		if (mat.param28() != 1f || l.param38() != 1f || (te != null && (te.txr().flags() & 0x40000) != 0)) {
			return RenderModel.Blend.ALPHA;
		}
		return textured ? RenderModel.Blend.CUTOUT : RenderModel.Blend.OPAQUE;
	}

	/** 레이어 색 배율: 텍스처 없는 레이어는 header.rgb × layer.rgb / 0.8 (중립 = 1, 16 문서 §3.3) [추정]. */
	/**
	 * 동적 라이트 경로의 머티리얼 색 (VU qw 76) = MAT 헤더 색 (런타임 +0x10, 파일 0x08) × 레이어 rgba (런타임 +0x14, 파일 0x28) × 물체 색 (기본 1).
	 * 0x154410 이 만들고, 렌더 플래그 0x1000 (조명 모델 228 개 전부 0x1800) 이면 VU 플래그 4 → 커널이 이것 × 128 로 라이트 합을 곱한다 (47 §3.1) [확실].
	 * 텍스처 유무와 무관 (조명 없는 경로의 {@link #layerTint} 중립 환산과 다르다).
	 * 반환 [3..5] = 더할 색 qw 77 (MAT 파일 0x18, 런타임 +0x20) / 128 — 커널 vf29 = qw77 + 주변·M·128 이라 자르기 전에 더한다.
	 */
	public static float[] litColor(MaterialResource mat, MaterialResource.Layer l) {
		float[] add = mat.addColor();
		return new float[]{mat.color()[0] * l.rgba()[0], mat.color()[1] * l.rgba()[1], mat.color()[2] * l.rgba()[2],
				add[0] / 128f, add[1] / 128f, add[2] / 128f};
	}

	static float[] layerTint(MaterialResource mat, MaterialResource.Layer l, boolean textured) {
		float[] t = l.rgba().clone();
		if (!textured) {
			for (int k = 0; k < 3; k++) {
				t[k] = mat.color()[k] * t[k] / 0.8f;
			}
		}
		return t;
	}

	public WadFile wad() {
		return wad;
	}

	public List<String> modelNames() {
		return gowModels.stream().map(m -> m.name).toList();
	}

	public MaterialResource material(String name) {
		return materials.get(name);
	}

	public Texture texture(String txrName) {
		if (txrName == null || txrName.isEmpty()) {
			return null;
		}
		return glTextures.computeIfAbsent(txrName, n -> {
			Pre pre = decoded.remove(n);
			for (TextureLibrary.Entry e : textures.textures()) {
				if (e.txr().name().equals(n)) {
					Pre p = pre != null ? pre : image(e, n);
					if (p.override()) {
						return upload(p.img(), n);
					}
					return Texture.of(p.img(), true)
							.withNormalSource(n, e.gfx().width(), e.gfx().height(), () -> TextureDecoder.decode(e.gfx(), e.pal(), 0));
				}
			}
			return null;
		});
	}

	/** 미리 해독한 텍스처 그림 ({@link #prepare}, 아무 스레드) — {@link #texture} 가 GL 로 올릴 때 꺼내 쓴다. */
	private final Map<String, Pre> decoded = new java.util.concurrent.ConcurrentHashMap<>();

	/** CPU 그림과 모드 PNG 교체 여부. */
	private record Pre(java.awt.image.BufferedImage img, boolean override) {
	}

	/** 텍스처 그림 (CPU): 모드 PNG (49 문서 §14) 가 있으면 원본 자리에 맞춘 그것, 없으면 해독. */
	private Pre image(TextureLibrary.Entry e, String name) {
		java.awt.image.BufferedImage ov = TextureOverrides.load(wad.name(), name, -1);
		if (ov != null) {
			return new Pre(TextureOverrides.fit(ov, e.gfx().width(), e.gfx().height()), true);
		}
		return new Pre(TextureDecoder.toImage(e.gfx().width(), e.gfx().height(), TextureDecoder.decode(e.gfx(), e.pal(), 0)), false);
	}

	/** {@link #texture} 가 GL 로 올릴 그림 (CPU 만, GL 없음 — 확인·테스트용). 없는 이름이면 null. */
	public java.awt.image.BufferedImage textureImage(String txrName) {
		TextureLibrary.Entry e = entry(txrName);
		return e == null ? null : image(e, txrName).img();
	}

	/** 교체 그림 업로드: 노멀맵 출처도 교체 그림 (44 문서). */
	private static Texture upload(java.awt.image.BufferedImage img, String key) {
		return Texture.of(img, true).withNormalSource(key, img.getWidth(), img.getHeight(), () -> TextureOverrides.pixels(img));
	}

	/** CPU 만: {@link #texture} 와 같은 첫 항목을 해독해 둔다. */
	private void predecode(String txrName) {
		if (txrName == null || txrName.isEmpty() || decoded.containsKey(txrName)) {
			return;
		}
		for (TextureLibrary.Entry e : textures.textures()) {
			if (e.txr().name().equals(txrName)) {
				decoded.put(txrName, image(e, txrName));
				return;
			}
		}
	}

	/**
	 * 이름이 겹치는 텍스처 (R_PERMK 의 TXR_file1 …) 중 태그 번호 {@code beforeTag} 앞에서 마지막으로 정의된 것. 원작 자원 이름은
	 * 적재 순서상 가장 최근 정의로 풀린다 [추정: 같은 그룹 묶음 PTC → MAT → TXR 이 바로 앞에 있음].
	 */
	public Texture textureBefore(String txrName, int beforeTag) {
		TextureLibrary.Entry best = null;
		for (TextureLibrary.Entry e : textures.textures()) {
			if (e.txr().name().equals(txrName) && e.tagIndex() < beforeTag && e.mipLevel() == 0 && (best == null || e.tagIndex() > best.tagIndex())) {
				best = e;
			}
		}
		if (best == null) {
			return texture(txrName);
		}
		TextureLibrary.Entry f = best;
		return glTextures.computeIfAbsent(txrName + "@" + f.tagIndex(), n -> {
			java.awt.image.BufferedImage ov = TextureOverrides.load(wad.name(), txrName, -1);
			if (ov != null) {
				return upload(TextureOverrides.fit(ov, f.gfx().width(), f.gfx().height()), n);
			}
			int[] argb = TextureDecoder.decode(f.gfx(), f.pal(), 0);
			return Texture.of(TextureDecoder.toImage(f.gfx().width(), f.gfx().height(), argb), true)
					.withNormalSource(n, f.gfx().width(), f.gfx().height(), () -> TextureDecoder.decode(f.gfx(), f.pal(), 0));
		});
	}

	/** MDL 이름으로 렌더 모델 생성 (LOD 0). */
	public RenderModel model(String mdlName) {
		return models.computeIfAbsent(mdlName, n -> {
			GowModel gm = gowModels.stream().filter(m -> m.name.equals(n)).findFirst()
					.orElseThrow(() -> new IllegalArgumentException("모델 없음: " + n));
			return build(gm);
		});
	}

	/** 파싱된 원본 모델 (없으면 null). */
	/** MDL 이름으로 원본 모델 (없으면 null). */
	public GowModel gowModel(String mdlName) {
		return gowModels.stream().filter(m -> m.name.equals(mdlName)).findFirst().orElse(null);
	}

	/**
	 * 같은 이름을 찾고, 없으면 대소문자 무시로. 출시 데이터는 섞여 있다: R_WPN0_0 은 {@code MDL_MAIBlade}, R_WPN0_1–5 는 {@code MDL_MAIblade}
	 * (43 §17) [확실: 데이터]. 원작 자원 조회는 대문자 해시 계열 (머티리얼 0x17A7B8, 46 문서) [추정: 모델 조회 경로].
	 */
	public GowModel gowModelIgnoreCase(String mdlName) {
		GowModel m = gowModel(mdlName);
		return m != null ? m : gowModels.stream().filter(x -> x.name.equalsIgnoreCase(mdlName)).findFirst().orElse(null);
	}

	/** 반드시 있어야 하는 모델. */
	public GowModel requireModel(String mdlName) {
		GowModel m = gowModel(mdlName);
		if (m == null) {
			throw new IllegalArgumentException("모델 없음: " + mdlName);
		}
		return m;
	}

	/** 머티리얼의 첫 레이어 텍스처 이름 (없으면 null). */
	public String materialTexture(String matName) {
		MaterialResource m = materials.get(matName);
		if (m == null && matName != null) {
			// 원작 자원 조회 0x17A7B8 → 0x1759F0 은 이름을 대문자로 (0x175938, 24 자) 해시 (Hash127) 해 찾는다 [확실] — SCR_Water "REFLENVM" →
			// "MAT_REFLENVM" 이 WAD 의 MAT_reflenvm 을 찾는다. 예전엔 못 찾아 흰 텍스처로 그려 ATHN05D·06A 물이 희뿌옜다 (43 §21)
			m = materials.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(matName)).map(Map.Entry::getValue).findFirst().orElse(null);
		}
		return m == null || m.layers().isEmpty() ? null : m.layers().getFirst().texture();
	}

	/** 이미 변환된 TriMesh (예: 레벨 배치 후 월드 좌표) 를 이 WAD 의 머티리얼로 GPU 모델화. 캐시하지 않는다. */
	public RenderModel build(String name, TriMesh tm) {
		return upload(prepare(name, tm, false));
	}

	private RenderModel build(GowModel gm) {
		return upload(prepare(gm.name, TriMesh.of(gm, 0), false));
	}

	/** {@link #prepare} 결과: 서브메시별 정점·색인 배열 (GL 전). */
	public record Prepared(String name, TriMesh tm, List<float[]> vertices, List<int[]> indices, float[] min, float[] max) {
	}

	/**
	 * CPU 단계 (GL 없음, 아무 스레드 — 48 문서 §8): 정점 배열·부드러운 법선·색인과 쓸 텍스처 해독. {@link #upload} 가 GL 로 올린다.
	 * {@code build(name, tm) == upload(prepare(name, tm, false))}.
	 *
	 * @param decodeTextures 쓸 텍스처도 해독해 둔다 — 아직 GL 텍스처가 하나도 없는 새 자산을 백그라운드에서 준비할 때만 (해독 결과는 첫
	 *                       {@link #texture} 가 꺼내 쓴다)
	 */
	public Prepared prepare(String name, TriMesh tm, boolean decodeTextures) {
		List<float[]> vs = new ArrayList<>();
		List<int[]> is = new ArrayList<>();
		float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
		float[] max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
		for (TriMesh.Sub s : tm.subs) {
			float[] v = new float[s.pos.size() * Mesh.STRIDE];
			// 법선 없는 서브메시 (조명 구운 레벨 메시): 노멀맵 기준용 부드러운 법선 (44 문서). uLit = 0 이라 원래 화면엔 안 쓰인다
			float[][] smooth = s.hasNormal ? null : devple.gow.engine.render.SmoothNormals.compute(s.pos, s.tris);
			for (int i = 0; i < s.pos.size(); i++) {
				float[] p = s.pos.get(i);
				float[] n = smooth != null ? smooth[i] : s.normal.get(i);
				float[] uv = s.uv.get(i);
				int[] c = s.color.get(i);
				int o = i * Mesh.STRIDE;
				v[o] = p[0];
				v[o + 1] = p[1];
				v[o + 2] = p[2];
				v[o + 3] = n[0];
				v[o + 4] = n[1];
				v[o + 5] = n[2];
				v[o + 6] = uv[0];
				v[o + 7] = uv[1];
				// PS2 정점색 0x80 = 1.0 (GS MODULATE 는 tex * color / 0x80)
				for (int k = 0; k < 4; k++) {
					v[o + 8 + k] = Math.min(c[k] / 128f, 2f);
				}
				for (int k = 0; k < 3; k++) {
					min[k] = Math.min(min[k], p[k]);
					max[k] = Math.max(max[k], p[k]);
				}
			}
			int[] idx = new int[s.tris.size() * 3];
			for (int i = 0; i < s.tris.size(); i++) {
				System.arraycopy(s.tris.get(i), 0, idx, i * 3, 3);
			}
			vs.add(v);
			is.add(idx);
			MaterialResource mat = materials.get(s.material);
			if (decodeTextures && mat != null && !mat.layers().isEmpty()) { // upload 의 texture() 와 같은 조건·이름
				if (s.hasUv) {
					predecode(mat.layers().getFirst().texture());
				}
				for (int i = 1; i < mat.layers().size(); i++) {
					if (s.hasUv || s.hasNormal) {
						predecode(mat.layers().get(i).texture());
					}
				}
			}
		}
		return new Prepared(name, tm, vs, is, min, max);
	}

	/** GL 단계 (메인 스레드): 머티리얼·텍스처·메시. */
	public RenderModel upload(Prepared p) {
		List<RenderModel.Part> parts = new ArrayList<>();
		for (int si = 0; si < p.tm().subs.size(); si++) {
			TriMesh.Sub s = p.tm().subs.get(si);
			float[] v = p.vertices().get(si);
			int[] idx = p.indices().get(si);
			MaterialResource mat = materials.get(s.material);
			if (stencilOnly(mat)) {
				continue;
			}
			Texture tex = null;
			RenderModel.Blend blend = RenderModel.Blend.OPAQUE;
			float[] tint = {1, 1, 1, 1};
			float[] lit = null;
			MaterialFx fx = null;
			boolean late = false;
			if (mat != null && !mat.layers().isEmpty()) {
				MaterialResource.Layer l = mat.layers().getFirst();
				late = lateStage(mat);
				lit = litColor(mat, l);
				tex = s.hasUv ? texture(l.texture()) : null;
				blend = classify(mat, tex != null);
				tint = layerTint(mat, l, tex != null);
				if (blend == RenderModel.Blend.ALPHA) {
					tint[3] *= mat.param28() * l.param38();
				}
				fx = materialFx(mat, s.hasUv, s.hasNormal, p.tm().renderFlags);
			}
			parts.add(new RenderModel.Part(new Mesh(v, idx, GL_TRIANGLES), tex, blend, tint, s.hasNormal, fx,
					lit != null ? lit : new float[]{tint[0], tint[1], tint[2], 0, 0, 0}, late));
		}
		return new RenderModel(p.name(), parts, p.min(), p.max());
	}

	/**
	 * 원작 VU 플래그 4 를 모델이 늘 켜는가 = MDL 렌더 플래그 0x1000 (조명 모델 228 개 전부 0x1800) [확실: 0x167A98 → 0x168310].
	 * 플래그 4 의 다른 출처는 그리기 기록 +0x10 의 거리 페이드 α ≠ 1 (0x1580C0 → 0x1585E8, 엔진에 없음) 과 머티리얼 rf 0x4000 (디스크 0 개).
	 */
	public static final long VU_MATERIAL_ALPHA = 0x1000;

	/**
	 * 2·3 번째 레이어의 알파 배율 (43 §30) [확실: 메시 VU1 마이크로코드 0x091–0x098 · 0x154410 · 0x167A98 / 확인: 영상 54–56 ATHN07 둥근 문].
	 * 0x154410 이 레이어마다 qw78.w = 물체 α × p28 × p38 을 만들지만 메시 프로그램은 VU 플래그 4 (vi10 bit 10) 일 때만 vf30.w = qw78.w 로 정점 α 를 바꾸고,
	 * 아니면 vf30 = 1 이라 정점 α 는 메시 정점색 α 그대로다 (조명 없는 커널은 정점색을 ftoi0 로 그대로 넘긴다). 그래서 조명 없는 모델 (렌더 플래그 0x1000 없음,
	 * 레벨 배치 대부분) 의 레이어 As = 텍스처 α × 정점 α 이고 p28 · p38 은 쓰이지 않는다. 예전 엔진은 늘 p38 (0.25) 를 곱해, GS 0x01 반전 블렌드 레이어
	 * (Cd − Cs)·As + Cs 가 3/4 를 덮어써 ATHN07 둥근 문 MAT_phong15 (환경맵 ballenv7 + timeDoorA + ballenv7 메시 UV) 위에 공 사진이 반원마다 늘어나 보였다 —
	 * 원작은 timeDoorA 의 α (문양 금속 부분) 로 환경맵 금빛이 비치고 나머지는 문양 색이다. 조명 모델 (0x1000) 은 예전처럼 p38 · p28 을 곱한다.
	 * 첫 레이어는 그대로 둔다 (p28 · p38 ≠ 1 은 정렬 플래그 0x1537C8 이기도 하고, 조명 없는 모델의 첫 레이어 α 는 영상으로 확인하지 않았다 [미확인]).
	 */
	static float extraLayerAlpha(MaterialResource mat, MaterialResource.Layer l, long modelRenderFlags) {
		return (modelRenderFlags & VU_MATERIAL_ALPHA) != 0 ? l.param38() * mat.param28() : 1f;
	}

	/** 플립북 프레임 / 머티리얼 ANM / 2·3번째 레이어가 있으면 MaterialFx, 없으면 null. modelRenderFlags = MDL 렌더 플래그 ({@link #extraLayerAlpha}). */
	MaterialFx materialFx(MaterialResource mat, boolean hasUv, boolean hasNormal, long modelRenderFlags) {
		MaterialAnim.Anim anim = matAnims.get(mat.name());
		Texture[] frames = hasUv ? textureFrames(mat.layers().getFirst().texture()) : null;
		// 첫 레이어 환경맵 (VU 플래그 2): 발리스타 손잡이 틀 MAT_M02MetalTrim01b (rf 0x86, 크롬 공 TXR_entities02_file330 — 원 밖은 검정) 을
		// 메시 UV 로 찍어 틀이 검게 나왔다 (43 §25) [확실: 마이크로코드 0x0D9 / 텍스처가 구면 반사 사진]
		boolean env0 = hasNormal && (mat.layers().getFirst().renderFlags() & MaterialFx.ENV_FLAG) != 0;
		List<MaterialFx.Layer> layers = new ArrayList<>();
		for (int i = 1; i < mat.layers().size(); i++) {
			MaterialResource.Layer l = mat.layers().get(i);
			Texture t = hasUv || hasNormal ? texture(l.texture()) : null;
			RenderModel.Blend b = switch (l.gsAlpha()) {
				case 0x48 -> RenderModel.Blend.ADD;
				case 0x42 -> RenderModel.Blend.SUB;
				case 0x01 -> RenderModel.Blend.INV;
				default -> RenderModel.Blend.ALPHA;
			};
			float[] tint = layerTint(mat, l, t != null);
			tint[3] *= extraLayerAlpha(mat, l, modelRenderFlags);
			// 레이어 렌더 플래그 bit1 (0x02) = 환경맵 VU 경로 → 법선 있는 메시에서 구면 환경맵 UV (MaterialFx.ENV_FLAG). bit2 (0x04) 는 머티리얼 색 (환경맵 아님)
			boolean env = (l.renderFlags() & MaterialFx.ENV_FLAG) != 0;
			layers.add(new MaterialFx.Layer(i, t, textureFrames(l.texture()), b, tint, env, litColor(mat, l)));
		}
		if (anim == null && frames == null && layers.isEmpty() && !env0) {
			return null;
		}
		float[][] rgba = new float[mat.layers().size()][];
		for (int i = 0; i < rgba.length; i++) {
			rgba[i] = mat.layers().get(i).rgba().clone();
		}
		return new MaterialFx(frames, anim, layers, env0, mat.color().clone(), rgba); // 색 트랙 기본값 (헤더 rgb · 레이어 rgba, 43 §33)
	}

	@Override
	public void close() {
		glFrames.values().forEach(a -> {
			if (a != null) {
				for (Texture t : a) {
					t.close();
				}
			}
		});
		models.values().forEach(RenderModel::close);
		glTextures.values().forEach(t -> {
			if (t != null) {
				t.close();
			}
		});
		// 두 번 닫아도 같은 GL 이름을 두 번 지우지 않게
		glFrames.clear();
		models.clear();
		glTextures.clear();
		decoded.clear();
	}
}
