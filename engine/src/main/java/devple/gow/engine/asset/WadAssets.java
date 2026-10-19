package devple.gow.engine.asset;

import devple.gow.engine.fx.MaterialFx;
import devple.gow.engine.render.Mesh;
import devple.gow.engine.render.Texture;
import devple.gow.tools.fx.MaterialAnim;
import devple.gow.tools.model.GowModel;
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
		this.wad = WadFile.load(wadPath);
		this.textures = new TextureLibrary(wad);
		for (MaterialResource m : textures.materials()) {
			materials.putIfAbsent(m.name(), m);
		}
		this.gowModels = GowModel.load(wad);
		this.matAnims = MaterialAnim.load(wad);
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
			int[] argb = TextureDecoder.decode(e.gfx(), e.pal(), 0);
			Texture[] out = new Texture[e.gfx().frames()];
			for (int f = 0; f < out.length; f++) {
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
			for (TextureLibrary.Entry e : textures.textures()) {
				if (e.txr().name().equals(n)) {
					int[] argb = TextureDecoder.decode(e.gfx(), e.pal(), 0);
					return Texture.of(TextureDecoder.toImage(e.gfx().width(), e.gfx().height(), argb), true)
							.withNormalSource(n, e.gfx().width(), e.gfx().height(), () -> TextureDecoder.decode(e.gfx(), e.pal(), 0));
				}
			}
			return null;
		});
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
		return m == null || m.layers().isEmpty() ? null : m.layers().getFirst().texture();
	}

	/** 이미 변환된 TriMesh (예: 레벨 배치 후 월드 좌표) 를 이 WAD 의 머티리얼로 GPU 모델화. 캐시하지 않는다. */
	public RenderModel build(String name, TriMesh tm) {
		return buildMesh(name, tm);
	}

	private RenderModel build(GowModel gm) {
		return buildMesh(gm.name, TriMesh.of(gm, 0));
	}

	private RenderModel buildMesh(String name, TriMesh tm) {
		List<RenderModel.Part> parts = new ArrayList<>();
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
			MaterialResource mat = materials.get(s.material);
			Texture tex = null;
			RenderModel.Blend blend = RenderModel.Blend.OPAQUE;
			float[] tint = {1, 1, 1, 1};
			MaterialFx fx = null;
			if (mat != null && !mat.layers().isEmpty()) {
				MaterialResource.Layer l = mat.layers().getFirst();
				tex = s.hasUv ? texture(l.texture()) : null;
				blend = classify(mat, tex != null);
				tint = layerTint(mat, l, tex != null);
				if (blend == RenderModel.Blend.ALPHA) {
					tint[3] *= mat.param28() * l.param38();
				}
				fx = materialFx(mat, s.hasUv, s.hasNormal);
			}
			parts.add(new RenderModel.Part(new Mesh(v, idx, GL_TRIANGLES), tex, blend, tint, s.hasNormal, fx));
		}
		return new RenderModel(name, parts, min, max);
	}

	/** 플립북 프레임 / 머티리얼 ANM / 2·3번째 레이어가 있으면 MaterialFx, 없으면 null. */
	private MaterialFx materialFx(MaterialResource mat, boolean hasUv, boolean hasNormal) {
		MaterialAnim.Anim anim = matAnims.get(mat.name());
		Texture[] frames = hasUv ? textureFrames(mat.layers().getFirst().texture()) : null;
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
			tint[3] *= l.param38() * mat.param28();
			// 레이어 렌더 플래그 bit1 (0x02) = 조명+환경맵 VU 프로그램 → 법선 있는 메시에서 구면 환경맵 UV [추정]
			boolean env = (l.renderFlags() & 0x02) != 0 || (l.renderFlags() & 0x04) != 0;
			layers.add(new MaterialFx.Layer(i, t, textureFrames(l.texture()), b, tint, env));
		}
		if (anim == null && frames == null && layers.isEmpty()) {
			return null;
		}
		return new MaterialFx(frames, anim, layers);
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
	}
}
