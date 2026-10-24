package devple.gow.engine.fx;

import devple.gow.engine.render.Camera;
import devple.gow.engine.render.Shader;
import devple.gow.tools.fx.FxResources;

import static org.lwjgl.opengl.GL41C.*;

/**
 * 안개 앞 반투명 패스의 안개 — 원작은 안개를 장면 위 후처리로 그린다 (16 문서 §1.2, 31 문서 §3.1).
 *
 * <p><b>원작 순서</b> [확실: 디스어셈블]: 프레임 0x161A80 → 층별 0x160CC0(목록, 뷰, 층, 마스크). 층 2 는 단계 k = 0..4 마다 서버 A/B/C
 * (지오메트리) → 파티클 서버 0x15DE50 의 버킷 k → 칸 k 노드 (0x15F4E0), 이어서 0x146188 → 파티클 버킷 5 → 칸 5 노드 → 파티클 버킷 6 → 칸 6
 * → 칸 7, 그다음 층 3·4. 안개 노드 (vtable 0x2BDC38, 렌더 0x1281C8) 는 0x125D08 (SCR_Fog 0x20CED8 · 포세이돈 0x195BA8) 이 <b>층 2 · 칸 5 ·
 * 키 0x7100</b> 으로 넣는다 (0x12619C → 정렬 삽입 0x15F288). 그래서 안개는
 * <ul>
 *   <li>층 1 (하늘 GO 와 그 파티클), 층 2 지오메트리 (불투명·반투명 단계·그림자 합성), 파티클 버킷 0–5, 칸 0–5 의 키 &lt; 0x7100 노드
 *       (렌즈플레어 0x20E7D4 = 키 0) <b>위에</b> 깔리고</li>
 *   <li>파티클 버킷 6 (PTC flags &amp; 0x4040: 열기 아지랑이 0x40 · 0x4000), 층 3 (PTC flags 0x80000 → 인스턴스 +0xD4 = 3, 0x15BB38;
 *       블레이드 Blades_Init · 무기 궤적 0x12DE70; 층 3 머티리얼 flags2 0x8), 층 4 는 안개 <b>뒤</b> 라 안개를 받지 않는다.</li>
 * </ul>
 * 안개 패스는 Z 버퍼만 보므로 (0x127918 / 0x1275E0 이 Z 를 프레임 알파로, 0x127000 이 (Cs−Cd)·Ad + Cd) 깊이를 쓰지 않는 파티클 화소는
 * <b>뒤 장면 깊이</b> 의 계수를 받는다 — 05A/05C 의 검은 연기 원반 (FPpart, 알파 0x44 버킷 1) 이 먼 배경 앞에서 안개색으로 묻히는 까닭 (43 §24).
 *
 * <p><b>엔진</b>: 지오메트리는 셰이더 안 안개 (조각 자신의 깊이 — 불투명은 후처리와 같은 결과) 그대로. 그 뒤에 그리는 안개 앞 반투명 패스는
 * 장면 깊이 사본 (이 클래스가 한 프레임에 한 번 복사) 으로 같은 계수를 구해 자기 색만 바꾼다 (fog.glsl {@code fogScene}): 안개 섞기
 * M(x) = (1−F)x + F·c 와 블렌드 (대상 계수 k) 가 교환되므로 "파티클 → 안개" 와 같다 (알파 P' = (1−F)P + F·c·As, 가산·감산 P' = (1−F)P).
 * <pre>
 * SceneFog.begin(env, cam, w, h)   // 주 레벨 그리기 시작 (하늘 단계 전)
 * SceneFog.armDepth()              // 깊이를 쓰는 안개 앞 지오메트리를 다 그린 뒤 (다음 사용 때 깊이 복사)
 * SceneFog.current().applyParticles(shader, sky) / applyModel(basic) / applyScene(shader)
 * SceneFog.end()                   // 안개 뒤 패스 (카메라 필터·HUD) 전
 * </pre>
 * 끄기 (비교용): {@code -Dgow.scenefog=off} 또는 환경 변수 {@code GOW_SCENEFOG=off} — 예전처럼 파티클·이펙트 GO·렌즈플레어·물에 안개 없음.
 */
public final class SceneFog {

	/** 장면 깊이 사본 유닛 (0 = 텍스처, 1 = 안개 텍스처 / 열기 아지랑이 프레임, 2 = 노멀맵). */
	public static final int DEPTH_UNIT = 3;
	/** 파티클·렌즈플레어 셰이더의 안개 텍스처 유닛 (1 은 열기 아지랑이 프레임 복사본). */
	public static final int FOG_TEX_UNIT = 4;
	/** fog.glsl uFogPass / particle.frag: 0 없음, 1 장면 깊이, 2 하늘 단계 (배경 계수). */
	public static final int PASS_NONE = 0, PASS_SCENE = 1, PASS_SKY = 2;

	private static final boolean OFF = "off".equals(System.getProperty("gow.scenefog")) || "off".equals(System.getenv("GOW_SCENEFOG"));

	private static SceneFog current;
	private static int depthTex, texW, texH;
	/** 진단: 프레임마다 깊이 복사 횟수 (테스트·-Dgow.perf). */
	private static long copies;

	private final FxEnvironment env;
	private final Camera cam;
	private final int width, height;
	private boolean armed, copied;

	private SceneFog(FxEnvironment env, Camera cam, int width, int height) {
		this.env = env;
		this.cam = cam;
		this.width = width;
		this.height = height;
	}

	/** 주 레벨 그리기 시작: 이번 프레임의 안개 (레벨 환경 · 카메라 · 프레임버퍼 크기). 하늘 단계 파티클부터 쓴다. */
	public static void begin(FxEnvironment env, Camera cam, int width, int height) {
		current = OFF || env == null || width <= 0 || height <= 0 ? null : new SceneFog(env, cam, width, height);
	}

	/** 깊이를 쓰는 안개 앞 지오메트리 (레벨·이웃 슬롯·캐릭터) 를 다 그렸다 — 다음 장면 깊이 사용 때 한 번 복사한다. */
	public static void armDepth() {
		if (current != null) {
			current.armed = true;
		}
	}

	/** 안개 뒤 패스 전 (카메라 필터·HUD) — 이후 반투명 패스는 안개 없음. */
	public static void end() {
		current = null;
	}

	/** 이번 프레임의 장면 안개 (없으면 null). */
	public static SceneFog current() {
		return current;
	}

	public FxEnvironment env() {
		return env;
	}

	/** 안개가 걸리는가 (환경 켜짐 + 안개 있음). */
	public boolean on() {
		return env.fogActive();
	}

	/** 장면 깊이를 쓸 수 있는가 ({@link #armDepth} 뒤). */
	public boolean depthReady() {
		return armed;
	}

	/**
	 * 원작 PTC 가 안개 앞인가 [확실: 0x15B8D0 버킷 · 0x15BB38 층 / 0x160CC0 순서]: 버킷 = flags &amp; 0x4040 → 6, &amp; 0x2000 → 5, 그 밖
	 * flags&amp;3 (0→1, 1→3, 2→2/4, 3→0) — 6 만 안개 뒤. flags 0x80000 → 층 3 (안개 뒤). 근사 정의 (PTC 없음) 는 원작 짝을 몰라 안개 없음.
	 */
	public static boolean preFog(FxResources.Shape ptc) {
		return ptc != null && preFogFlags(ptc.flags());
	}

	/** {@link #preFog} 의 플래그 규칙. */
	public static boolean preFogFlags(int flags) {
		return (flags & 0x4040) == 0 && (flags & 0x80000) == 0;
	}

	/**
	 * 파티클 셰이더 (particle.frag) 안개 유니폼. 하늘 단계는 깊이 없이 하늘과 같은 배경 계수 (PASS_SKY), 그 밖은 장면 깊이가 준비됐을 때만.
	 * 장면 깊이 사본은 안개 앞 정의를 처음 그릴 때 {@link #bindSceneDepth} 로 (안개 뒤 파티클만 있으면 복사하지 않는다).
	 *
	 * @return 이 패스에서 쓸 uFogPass (PASS_NONE 이면 안개 없음 — 정의마다 {@link #preFog} 로 더 거른다)
	 */
	public int applyParticles(Shader s, boolean skyStage) {
		s.use();
		int pass = passFor(on(), skyStage, armed);
		if (pass == PASS_NONE) {
			s.set("uFogPass", PASS_NONE);
			return PASS_NONE;
		}
		env.applyFog(s, cam, height, FOG_TEX_UNIT);
		s.set("uFogBack", Math.max(env.backgroundFactor(), 0f));
		return pass;
	}

	/**
	 * 파티클 패스의 안개 종류: 안개가 없으면 없음, 하늘 단계 (층 1 — 안개 노드보다 한참 앞, 그 화소는 Z 가 지워진 배경) 는 하늘과 같은 배경 계수,
	 * 세계 단계는 장면 깊이가 준비된 ({@link #armDepth}) 뒤에만 장면 깊이.
	 */
	static int passFor(boolean fogOn, boolean skyStage, boolean depthArmed) {
		if (!fogOn) {
			return PASS_NONE;
		}
		if (skyStage) {
			return PASS_SKY;
		}
		return depthArmed ? PASS_SCENE : PASS_NONE;
	}

	/**
	 * particle.frag {@code fogPremul} 의 CPU 판 (시험용 참조): 미리 곱한 색 p (rgb·As, 0..1) 를 안개 계수 f · 안개색 fc 로 바꾼다.
	 * 안개 블렌드 0 (GS 0x54): p' = (1−f)·p + (알파 블렌드면 f·fc·As), 1 (0x58) · 2 (0x52): 알파 블렌드만 p' = p ± f·fc·As (가산·감산 파티클은 그대로).
	 *
	 * @param alphaDst 대상 계수가 1 − As 인 알파 블렌드 (GS 0x44) — 가산 (0x48) · 감산 (0x42) 이면 false
	 */
	static float[] fogPremul(float[] p, float as, float[] fc, float f, int fogBlend, boolean alphaDst) {
		float[] o = p.clone();
		if (fogBlend == 0) {
			float a = Math.clamp(f, 0f, 1f);
			for (int i = 0; i < 3; i++) {
				o[i] = p[i] * (1 - a) + (alphaDst ? fc[i] * a * as : 0f);
			}
		} else if (alphaDst) {
			float sgn = fogBlend == 1 ? 1 : -1;
			for (int i = 0; i < 3; i++) {
				o[i] = p[i] + sgn * fc[i] * Math.max(f, 0f) * as;
			}
		}
		return o;
	}

	/**
	 * basic 셰이더 (이펙트 GO · 물): 레벨 지오메트리와 같은 유니폼 + 장면 깊이 사본. 불투명 파트는 그대로 (조각 깊이), 반투명 파트는 호출자가
	 * uFogScene = 1. 안개가 없거나 깊이가 준비되지 않았으면 안개를 끄고 false.
	 */
	public boolean applyModel(Shader s) {
		s.use();
		if (!on() || !armed) {
			FxEnvironment.disableFog(s);
			return false;
		}
		env.applyFog(s, cam, height);
		bindDepth(s, true);
		return true;
	}

	/** 렌즈플레어 셰이더: 장면 깊이 안개 (uFogPass 1). */
	public boolean applyScene(Shader s) {
		s.use();
		if (!on() || !armed) {
			s.set("uFogPass", PASS_NONE);
			return false;
		}
		env.applyFog(s, cam, height, FOG_TEX_UNIT);
		bindDepth(s, true);
		s.set("uFogPass", PASS_SCENE);
		return true;
	}

	private void bindDepth(Shader s, boolean need) {
		s.set("uFogBack", Math.max(env.backgroundFactor(), 0f));
		if (need) {
			bindSceneDepth(s);
		}
	}

	/** 장면 깊이 사본 (이번 프레임 처음이면 복사) 을 유닛 {@link #DEPTH_UNIT} 에 묶고 셰이더 (지금 프로그램) 의 uSceneDepth 를 맞춘다. */
	public void bindSceneDepth(Shader s) {
		copyDepth();
		glActiveTexture(GL_TEXTURE0 + DEPTH_UNIT);
		glBindTexture(GL_TEXTURE_2D, depthTex);
		glActiveTexture(GL_TEXTURE0);
		s.set("uSceneDepth", DEPTH_UNIT);
	}

	/**
	 * 지금 깊이 버퍼 (기본 프레임버퍼) → 깊이 텍스처, 프레임마다 한 번 (armDepth 뒤 처음 쓸 때). 기본 프레임버퍼 깊이는 32 비트라 32F 로 받는다.
	 * 비용 (1280×720, M 시리즈, 05A 마당): GPU +0.4–0.9 ms, 스왑 전 CPU +0.9 ms (복사 지점에서 GPU 를 기다림) — 복사 크기와 무관한 렌더 패스 분할 비용
	 * (64×64 만 복사해도 같다, 깊이 32F · 24 · 블릿 모두 같음). 수직 동기 끔 프레임 간격은 늘지 않았다 (31 §3.1).
	 */
	private void copyDepth() {
		if (copied) {
			return;
		}
		copied = true;
		copies++;
		if (depthTex == 0) {
			depthTex = devple.gow.engine.render.GlStats.tex(glGenTextures());
		}
		glActiveTexture(GL_TEXTURE0 + DEPTH_UNIT);
		glBindTexture(GL_TEXTURE_2D, depthTex);
		if (width != texW || height != texH) {
			glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT32F, width, height, 0, GL_DEPTH_COMPONENT, GL_FLOAT, (java.nio.ByteBuffer) null);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_MODE, GL_NONE);
			texW = width;
			texH = height;
		}
		glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 0, 0, width, height);
		glActiveTexture(GL_TEXTURE0);
	}

	/** 지금까지 깊이 복사 횟수 (진단). */
	public static long copies() {
		return copies;
	}
}
