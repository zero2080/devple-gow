package devple.gow.engine.render;

import devple.gow.engine.light.ShadowVolume;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL41C.*;

/**
 * 그림자 볼륨 그리기 — 원작 층 특수 패스 0x160518 의 GL 판 (47 문서 §7.4).
 * <p>
 * 원작 (주 뷰 · 층 2 · 불투명 단계 뒤, 0x160CC0) [확실: GS 패킷]:
 * <ol>
 *   <li>0x12D170: Z 버퍼를 PSMCT24 프레임으로 보고 흰색 − Z (ALPHA_2 0xA4, FIX 0x80) 로 뒤집고 (0x12C908),
 *       프레임 초록을 0x7F 로 채운다 (0x12C790: FBMSK 0xFFFF00FF, RGBAQ G 0x7F).</li>
 *   <li>MAT_stencil (flags2 bit 28 → 머티리얼 bit 60 → 정렬 키 = 층 첫 키, 0x155990) 묶음을 초록만 (FBMSK 0xFFFF00FF), 가산
 *       (ALPHA 0x80_00000068 = Cs·FIX + Cd), COLCLAMP 0 (8 비트 감김), ZMSK, TEST 0x50000 (Z GEQUAL) 로 두 번 —
 *       0x168AA0 이 RGBAQ G = 0xFF (−1) 로 VU 변형 0, G = 0x01 (+1) 로 변형 1 (면 방향별) 을 부른다.</li>
 *   <li>0x12D5A8: 초록 최상위 비트를 알파 최상위 비트로 옮기고 (16 비트 재해석 셔플 0x12CA78),
 *       0x12CFD0 = 전체 화면 TEST_2 0x3C000 (DATE · DATM = 1: 대상 알파 bit 7 = 1 인 화소만), ALPHA_2 0x42 (Cd − Cs·As),
 *       RGBAQ (0x26, 0x26, 0x26, 0x80) → <b>그 화소 RGB 에서 38 을 뺀다</b> (COLCLAMP 1 로 0 에서 멈춤).</li>
 * </ol>
 * ⇒ 그림자 = 0x7F + Σ(면 ±1) 의 bit 7 = Σ ∈ [1, 128]. Z 를 뒤집고 GEQUAL 로 세므로 장면 면 <b>뒤</b> 의 볼륨 면을 센다 (z-fail) [추정: 정점 Z 반전은
 * 330A34 bit 3 뷰 플래그로 추정]. 카메라가 볼륨 밖이면 z-pass 와 Σ 가 같다.
 * <p>
 * 엔진: GL 스텐실을 0x7F 로 지우고 z-fail (뒷면 깊이 실패 +1, 앞면 −1, 감김, 깊이 클램프) 로 센 뒤
 * 스텐실 bit 7 화소에 역뺄셈 블렌드로 38/255 를 뺀다 — 원작과 같은 식 [확실: 식 / 추정: 면 방향 부호].
 */
public final class ShadowRenderer implements AutoCloseable {

	/** 스텐실 시작값 = 원작 초록 채움 0x7F (0x12C790). */
	public static final int STENCIL_BASE = 0x7F;
	/** 그림자 판정 비트 = 원작 DATE 가 보는 알파 bit 7 (초록 bit 7 을 옮긴 것). */
	public static final int SHADOW_BIT = 0x80;
	/** 원작 어둡게 하기 색 0x26 (= 38) — 0x29CF60 +0x08 = 0x80262626, ALPHA 0x42 = Cd − Cs·As (As = 0x80 → 1). */
	public static final int SUBTRACT_8BIT = 0x26;
	/** GL 색 단위 (프레임 8 비트 → 0..1). */
	public static final float SUBTRACT = SUBTRACT_8BIT / 255f;

	private final Shader shader = new Shader("shadow.vert", "shadow.frag");
	private final int vao = GlStats.vao(glGenVertexArrays());
	private final int vbo = GlStats.buf(glGenBuffers());
	private final int screenVao = GlStats.vao(glGenVertexArrays());
	private FloatBuffer buf = MemoryUtil.memAllocFloat(9 * 1024);

	public ShadowRenderer() {
		glBindVertexArray(vao);
		glBindBuffer(GL_ARRAY_BUFFER, vbo);
		glEnableVertexAttribArray(0);
		glVertexAttribPointer(0, 3, GL_FLOAT, false, 12, 0);
		glBindVertexArray(0);
	}

	/** 원작 판정: 볼륨 면 수 합 Σ (뒷면 +1, 앞면 −1) 에서 그림자인가 — (0x7F + Σ) 의 bit 7 (8 비트 감김). */
	public static boolean shadowed(int sum) {
		return ((STENCIL_BASE + sum) & SHADOW_BIT) != 0;
	}

	/** 원작 합성 (8 비트 프레임 한 채널): 그림자면 max(c − 38, 0). */
	public static int composite8(int c, boolean shadow) {
		return shadow ? Math.max(c - SUBTRACT_8BIT, 0) : c;
	}

	/**
	 * 안개를 이미 받은 화소에서 뺄 양 (0..1, 43 §31): 원작은 그림자 합성 (층 2, 0x160518) 뒤에 안개 노드 (층 2 칸 5, 0x1281C8) 가 프레임을
	 * 안개색에 섞으므로 (1 − F)·(c − 38) + F·f = [(1 − F)·c + F·f] − (1 − F)·38 — 안개 블렌드 0 이면 38·(1 − F), 가산·감산 안개 (1·2) 는
	 * c − 38 ± F·f 라 38 그대로 [확실: 순서 0x160CC0 · 식 / 근사: 0 에서 멈추는 자리 (원작은 빼기에서, 엔진은 안개 뒤에서)].
	 */
	public static float foggedSubtract(float fogFactor, int fogBlend) {
		return fogBlend == 0 ? SUBTRACT * (1 - Math.clamp(fogFactor, 0f, 1f)) : SUBTRACT;
	}

	/** 볼륨 삼각형 (바깥 반시계) 을 스텐실로 세고 그림자 화소를 어둡게 한다. 불투명 장면 (깊이) 이 그려진 뒤 부른다. */
	public void draw(Camera cam, float aspect, ShadowVolume.Out tris) {
		int n = tris.triangles();
		if (n == 0) {
			return;
		}
		if (buf.capacity() < n * 9) {
			MemoryUtil.memFree(buf);
			buf = MemoryUtil.memAllocFloat(n * 9 * 2);
		}
		buf.clear();
		buf.put(tris.data, 0, n * 9).flip();
		boolean cull = glIsEnabled(GL_CULL_FACE), blend = glIsEnabled(GL_BLEND), depth = glIsEnabled(GL_DEPTH_TEST);
		shader.use();
		shader.set("uViewProj", cam.viewProj(aspect));
		shader.set("uScreen", 0);
		// 1) 스텐실 세기: 시작 0x7F (원작 초록 채움), z-fail — 뒷면 깊이 실패 +1, 앞면 깊이 실패 −1 (원작 G += 0x01 / 0xFF, 감김).
		//    먼 덮개가 먼 면에 잘리지 않게 깊이 클램프
		glClearStencil(STENCIL_BASE);
		glStencilMask(0xFF);
		glClear(GL_STENCIL_BUFFER_BIT);
		glEnable(GL_STENCIL_TEST);
		glEnable(GL_DEPTH_TEST);
		glEnable(GL_DEPTH_CLAMP);
		glDisable(GL_CULL_FACE);
		glDisable(GL_BLEND);
		glDepthMask(false);
		glDepthFunc(GL_LESS);
		glColorMask(false, false, false, false);
		glStencilFunc(GL_ALWAYS, 0, 0xFF);
		glStencilOpSeparate(GL_BACK, GL_KEEP, GL_INCR_WRAP, GL_KEEP);
		glStencilOpSeparate(GL_FRONT, GL_KEEP, GL_DECR_WRAP, GL_KEEP);
		glBindVertexArray(vao);
		glBindBuffer(GL_ARRAY_BUFFER, vbo);
		glBufferData(GL_ARRAY_BUFFER, buf, GL_STREAM_DRAW);
		glDrawArrays(GL_TRIANGLES, 0, n * 3);
		glDisable(GL_DEPTH_CLAMP);
		// 2) bit 7 화소 (원작 DATE · DATM 1) 에서 RGB 38/255 빼기 (원작 ALPHA 0x42 = Cd − Cs·As, Cs = 0x26, As = 1). 알파는 그대로
		glColorMask(true, true, true, false);
		glDisable(GL_DEPTH_TEST);
		glStencilFunc(GL_EQUAL, SHADOW_BIT, SHADOW_BIT);
		glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
		glEnable(GL_BLEND);
		glBlendEquation(GL_FUNC_REVERSE_SUBTRACT);
		glBlendFunc(GL_ONE, GL_ONE);
		// 안개 (43 §31): 받는 면 화소의 안개 계수만큼 빼는 양을 줄인다 (foggedSubtract) — 장면 깊이 사본은 지금 깊이 (그림자 받는 면까지)
		devple.gow.engine.fx.SceneFog fog = devple.gow.engine.fx.SceneFog.current();
		boolean fogged = fog != null && fog.applyShadow(shader);
		shader.set("uShadowFog", fogged ? 1 : 0);
		shader.set("uScreen", 1);
		shader.set("uColor", new org.joml.Vector4f(SUBTRACT, SUBTRACT, SUBTRACT, 0));
		glBindVertexArray(screenVao);
		glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
		glBindVertexArray(0);
		if (fogged) {
			devple.gow.engine.fx.FxEnvironment.disableFog(shader);
			shader.set("uShadowFog", 0);
		}
		// 복원
		glBlendEquation(GL_FUNC_ADD);
		glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
		glColorMask(true, true, true, true);
		glDisable(GL_STENCIL_TEST);
		glClearStencil(0);
		glDepthMask(true);
		if (depth) {
			glEnable(GL_DEPTH_TEST);
		}
		if (!blend) {
			glDisable(GL_BLEND);
		}
		if (cull) {
			glEnable(GL_CULL_FACE);
		}
	}

	@Override
	public void close() {
		shader.close();
		MemoryUtil.memFree(buf); // 네이티브 정점 버퍼 (레벨마다 새 렌더러)
		buf = null;
		GlStats.delBuf(vbo);
		GlStats.delVao(vao);
		GlStats.delVao(screenVao);
	}
}
