package devple.gow.engine.asset;

import devple.gow.engine.render.Mesh;
import devple.gow.engine.render.Texture;
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

	public WadAssets(Path wadPath) throws IOException {
		this.wad = WadFile.load(wadPath);
		this.textures = new TextureLibrary(wad);
		for (MaterialResource m : textures.materials()) {
			materials.putIfAbsent(m.name(), m);
		}
		this.gowModels = GowModel.load(wad);
	}

	public WadFile wad() {
		return wad;
	}

	public List<String> modelNames() {
		return gowModels.stream().map(m -> m.name).toList();
	}

	public Texture texture(String txrName) {
		if (txrName == null || txrName.isEmpty()) {
			return null;
		}
		return glTextures.computeIfAbsent(txrName, n -> {
			for (TextureLibrary.Entry e : textures.textures()) {
				if (e.txr().name().equals(n)) {
					int[] argb = TextureDecoder.decode(e.gfx(), e.pal(), 0);
					return Texture.of(TextureDecoder.toImage(e.gfx().width(), e.gfx().height(), argb), true);
				}
			}
			return null;
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
	public GowModel gowModel(String mdlName) {
		return gowModels.stream().filter(m -> m.name.equals(mdlName)).findFirst().orElse(null);
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
			for (int i = 0; i < s.pos.size(); i++) {
				float[] p = s.pos.get(i);
				float[] n = s.normal.get(i);
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
			if (mat != null && !mat.layers().isEmpty()) {
				MaterialResource.Layer l = mat.layers().getFirst();
				tex = s.hasUv ? texture(l.texture()) : null;
				blend = switch (l.blendName()) {
					case "add" -> RenderModel.Blend.ADD;
					case "sub" -> RenderModel.Blend.SUB;
					case "blend", "invblend" -> RenderModel.Blend.ALPHA;
					default -> RenderModel.Blend.OPAQUE;
				};
				tint = l.rgba();
			}
			parts.add(new RenderModel.Part(new Mesh(v, idx, GL_TRIANGLES), tex, blend, tint, s.hasNormal));
		}
		return new RenderModel(name, parts, min, max);
	}

	@Override
	public void close() {
		models.values().forEach(RenderModel::close);
		glTextures.values().forEach(t -> {
			if (t != null) {
				t.close();
			}
		});
	}
}
