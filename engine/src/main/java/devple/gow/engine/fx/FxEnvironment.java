package devple.gow.engine.fx;

import devple.gow.engine.asset.WadAssets;
import devple.gow.engine.render.Camera;
import devple.gow.engine.render.Texture;
import devple.gow.engine.render.Shader;
import devple.gow.tools.fx.EnvParams;
import devple.gow.tools.level.GoInstance;
import devple.gow.tools.level.LevelData;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static org.lwjgl.opengl.GL41C.GL_TEXTURE0;
import static org.lwjgl.opengl.GL41C.glActiveTexture;

/**
 * 레벨 환경 (16 / 31 문서): SCR_Fog / SCR_LayeredFog / SCR_FarClip 값을 렌더 상태로 바꾼다.
 * <pre>
 * FxEnvironment env = new FxEnvironment(levelData);
 * env.update(dt, cam.eye);                      // 활성 안개 선택 (소유 인스턴스가 가장 가까운 것)
 * env.applyCamera(cam);                         // FarClip → cam.far
 * env.applyFog(shader, cam, fbHeight);          // basic.frag 안개 유니폼 (skinned 셰이더도 같은 frag 라 그대로 사용 가능)
 * env.setBackground(shader, true/false);        // 하늘(배경)을 그리는 동안 BackPlane_A 사용
 * FxEnvironment.disableFog(shader);
 * </pre>
 */
public final class FxEnvironment {

	/**
	 * 안개 시작 거리 D0 = CentreZ × 이 값 (16 문서 §1.2). GS Z = 16·CentreZ·n·(f−d)/((f−n)·d) (n = 10, 확실),
	 * Z→알파 램프 기준 Z 는 미확정 [추정] — 스크린샷 비교로 2^14 → D0 = CentreZ·10/1024 (HDES01A 610, ATHN01A 293).
	 */
	public static float fogScale = 10f / 1024f;

	private final EnvParams params;
	private final Map<String, float[]> ownerPos = new HashMap<>();
	private EnvParams.Fog fog;
	private EnvParams.LayeredFog layered;
	private float time;
	private boolean enabled = true;

	private final WadAssets assets;

	public FxEnvironment(WadAssets assets, LevelData data) {
		this.assets = assets;
		this.params = new EnvParams(data.wad);
		for (GoInstance g : data.layout.instances) {
			ownerPos.put(g.name(), g.pos());
		}
		fog = params.fogs.isEmpty() ? null : params.fogs.getFirst();
		layered = params.layeredFogs.isEmpty() ? null : params.layeredFogs.getFirst();
		System.out.print(params.describe().lines().map(l -> "  env " + l + "\n").reduce("", String::concat));
	}

	public EnvParams params() {
		return params;
	}

	public float time() {
		return time;
	}

	public void setEnabled(boolean on) {
		enabled = on;
	}

	public boolean enabled() {
		return enabled;
	}

	/** 시간 진행 + 활성 안개 선택: 여러 SCR_Fog 가 있으면 소유 인스턴스가 카메라에 가장 가까운 것 [추정: 원작은 ESC 로 켜고 끔]. */
	public void update(float dt, org.joml.Vector3f eye) {
		time += dt;
		if (params.fogs.size() > 1) {
			float best = Float.MAX_VALUE;
			for (EnvParams.Fog f : params.fogs) {
				float[] p = ownerPos.get(f.owner());
				if (p == null) {
					continue;
				}
				float d = (p[0] - eye.x) * (p[0] - eye.x) + (p[1] - eye.y) * (p[1] - eye.y) + (p[2] - eye.z) * (p[2] - eye.z);
				if (d < best) {
					best = d;
					fog = f;
				}
			}
		}
	}

	/** SCR_FarClip → 카메라 far. */
	public void applyCamera(Camera cam) {
		if (!params.farClips.isEmpty()) {
			cam.far = params.farClips.getFirst().farDistance();
		}
	}

	/** 지금 쓰는 안개 요약 (디버그). */
	public String activeFog() {
		if (layered != null) {
			return String.format(Locale.ROOT, "LayeredFog %s centreZ=%d", layered.owner(), layered.centreZ());
		}
		return fog == null ? "없음" : String.format(Locale.ROOT, "Fog %s centreZ=%d", fog.owner(), fog.centreZ());
	}

	/** basic.frag 안개 유니폼 설정. 안개가 없거나 꺼져 있으면 끈다. */
	public void applyFog(Shader s, Camera cam, int fbHeight) {
		if (!enabled || (fog == null && layered == null)) {
			disableFog(s);
			return;
		}
		if (layered != null) {
			float[][] c = layered.colors();
			s.set("uFogMode", 2);
			s.set("uFogBlend", layered.blend());
			s.set("uFogColor", c[0][0], c[0][1], c[0][2], c[0][3]);
			s.set("uFogColor1", c[1][0], c[1][1], c[1][2], c[1][3]);
			s.set("uFogColor2", c[2][0], c[2][1], c[2][2], c[2][3]);
			float[] st = layered.stops();
			s.set("uFogStops", st[0], st[1], st[2]);
			s.set("uFogDepth", layered.centreZ() * fogScale, cam.near, cam.far, back(layered.backPlane()));
		} else {
			float[] c = fog.color();
			s.set("uFogMode", 1);
			s.set("uFogBlend", fog.blend());
			s.set("uFogColor", c[0], c[1], c[2], c[3]);
			s.set("uFogDepth", fog.centreZ() * fogScale, cam.near, cam.far, back(fog.backPlane()));
		}
		s.set("uFogScreen", fbHeight, 0f);
		// 안개 텍스처 레이어: 스크롤(초당) · 회전(도/초) · 배율
		String mat = layered != null ? layered.material() : fog.material();
		float[] sc = layered != null ? layered.scroll() : fog.scroll();
		float rot = layered != null ? layered.rotDeg() : fog.rotDeg();
		float scale = layered != null ? layered.texScale() : fog.texScale();
		Texture t = fogTexture(mat);
		if (t != null && textured) {
			t.bind(1);
			glActiveTexture(GL_TEXTURE0);
			s.set("uFogTex", 1);
			s.set("uFogTexOn", 1);
			s.set("uFogTexXf", sc[0] * time, sc[1] * time, (float) Math.toRadians(rot * time), scale <= 0 ? 1 : scale);
			s.set("uFogWidth", (float) fbWidth);
		} else {
			s.set("uFogTexOn", 0);
		}
	}

	/** 안개 텍스처 레이어 사용 여부 (비교용). */
	public static boolean textured = true;
	private int fbWidth = 1;
	private final Map<String, Texture> fogTex = new HashMap<>();

	public void setFramebufferWidth(int w) {
		fbWidth = w;
	}

	/** "FOG" → TXR_FOG, 그 외 MAT_&lt;이름&gt; 첫 레이어 텍스처 (생성자 0x20CED8). */
	private Texture fogTexture(String mat) {
		if (mat == null || mat.isEmpty()) {
			return null;
		}
		return fogTex.computeIfAbsent(mat, m -> {
			if (m.equals("FOG")) {
				return assets.texture("TXR_FOG");
			}
			String t = assets.materialTexture("MAT_" + m);
			return t == null ? null : assets.texture(t);
		});
	}

	private static float back(int b) {
		return b < 0 ? -1f : Math.min(b / 128f, 1f);
	}

	/** 배경(하늘) 그리기 표시: BackPlane_A 가 있으면 깊이 대신 그 알파를 쓴다. */
	public void setBackground(Shader s, boolean bg, int fbHeight) {
		s.set("uFogScreen", fbHeight, bg ? 1f : 0f);
	}

	public static void disableFog(Shader s) {
		s.set("uFogMode", 0);
	}
}
