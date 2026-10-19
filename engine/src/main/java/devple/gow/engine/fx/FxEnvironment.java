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
	 * 원작 뷰포트 near (+0x384 기본 −10). GS Z24 = 0x800000 + z', z' = 16·CentreZ·n·(f−d)/((f−n)·d) (16 문서 §1.2) [확실].
	 * 안개 패스 0x127918 (Z 를 CT24 로 읽어 바이트별 부호 반전 → Z24 max(·, 0x807FFF) → Z16 보기 하위 반워드의 상위 바이트를
	 * 프레임 알파로) 의 결과: G = ⌊z'/256⌋, A8 = 256 − G (1 ≤ G ≤ 128) / 0x7F (그 밖) [확실: GS 메모리 배치 시뮬레이션],
	 * 이어서 0x126CF8 이 A8·Fog_A 로 변조 → 블렌드 계수 Ad/0x80 (basic.frag).
	 */
	public static float viewNear = 10f;
	/** 원작 뷰포트 far 기본 (+0x388 = −10000, SCR_FarClip 이 바꾼다). */
	public static float viewFar = 10000f;

	/**
	 * basic.frag 와 같은 깊이 알파 (Fog_A 곱하기 전, 0..127/128): z' = 16·C·n·(f−d)/((f−n)·d),
	 * z' ≥ 0x7FFF → 0 (안개색 스프라이트 Z 검사 탈락), 아니면 (128 − ⌊z'/256⌋)/128 (⌊z'/256⌋ = 0 이면 127/128).
	 */
	public static float depthAlpha(int centreZ, float d, float far) {
		float zp = 16f * centreZ * viewNear * Math.max(far - d, 0) / ((far - viewNear) * Math.max(d, 1e-3f));
		if (zp >= 32767f) {
			return 0;
		}
		float g = (float) Math.floor(zp / 256f);
		return (g >= 1 ? 128 - g : 127) / 128f;
	}

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

	/**
	 * 안개 대체 (원작 환경 +0x110 = 다른 안개 관리자, 렌더 0x1281C8 끝에서 레벨 안개 +0x80 으로 되돌림 → 매 프레임 다시 걸어야 한다).
	 * 포세이돈의 분노 Fog Template ({@link LightningScreen}). 대체 안개는 단일 안개 (층 비트 끔), 텍스처 없음. null = 레벨 안개.
	 */
	public void setFogOverride(EnvParams.Fog f) {
		override = f;
	}

	public EnvParams.Fog fogOverride() {
		return override;
	}

	private EnvParams.Fog override;

	/** basic.frag 안개 유니폼 설정. 안개가 없거나 꺼져 있으면 끈다. */
	public void applyFog(Shader s, Camera cam, int fbHeight) {
		EnvParams.Fog fog = override != null ? override : this.fog;
		EnvParams.LayeredFog layered = override != null ? null : this.layered;
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
			s.set("uFogDepth", 16f * layered.centreZ() * viewNear, cam.near, cam.far, back(layered.backPlane()));
			float[] band = BAND_OFF ? new float[2] : band(cam);
			s.set("uFogBand", band[0], band[1]);
		} else {
			float[] c = fog.color();
			s.set("uFogMode", 1);
			s.set("uFogBlend", fog.blend());
			// Fog_A ≥ 1 이고 블렌드 0 이면 0x1275E0 경로 (Z 바이트를 반전 없이 알파로, 안개색은 ALPHA 0x11 (Cd−Cs)·Ad + Cs):
			// 결과 = 안개 계수 (128 − G)/128 (Fog_A 곱 없음 = 1), BackPlane 패스 0x127FF0 없음 [확실: 0x1281C8 분기]
			boolean full = c[3] >= 1 && fog.blend() == 0;
			s.set("uFogColor", c[0], c[1], c[2], full ? 1f : c[3]);
			s.set("uFogDepth", 16f * fog.centreZ() * viewNear, cam.near, cam.far, full ? -1f : back(fog.backPlane()));
			s.set("uFogBand", 0f, 0f);
		}
		s.set("uFogScreen", fbHeight, 0f);
		s.set("uFogGs", params.farClips.isEmpty() ? viewFar : params.farClips.getFirst().farDistance(), viewNear);
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

	/**
	 * 층 안개 띠 좌표 (원작 0x1208C8) [확실: 코드]: 키 위치 t 는 화면 높이가 아니라 <b>시선 고도각</b> 눈금 — t = 1 − φ/π,
	 * φ = acos(카메라 행렬 z 행 .y) (z 행 = 뒤쪽, 0x183F98 = acos), 화면 위 t = 1 − (φ + h)/π, 아래 = 1 − (φ − h)/π (h = 세로 반화각).
	 * 수평으로 보면 지평선 = 0.5. 그 사이는 화면 y 선형 (Gouraud 띠 0x1262C8).
	 *
	 * @return {화면 위 t, 화면 아래 t}
	 */
	/** 비교용: GOW_FOGBAND=off 면 이전 해석 (t = 화면 높이 비율). */
	private static final boolean BAND_OFF = "off".equals(System.getenv("GOW_FOGBAND"));

	public static float[] band(Camera cam) {
		float bx = cam.eye.x - cam.target.x, by = cam.eye.y - cam.target.y, bz = cam.eye.z - cam.target.z;
		float l = (float) Math.sqrt(bx * bx + by * by + bz * bz);
		float phi = (float) Math.acos(Math.clamp(l > 1e-6f ? by / l : 0f, -1f, 1f));
		float h = (float) Math.toRadians(cam.fovDeg) / 2;
		return new float[]{1 - (phi + h) / (float) Math.PI, 1 - (phi - h) / (float) Math.PI};
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
