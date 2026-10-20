package devple.gow.engine.render;

import static org.lwjgl.opengl.GL41C.*;

/**
 * SCR_Mirror 거울 뷰 합성 보조 (43 문서 §12) — 현재 스텐실 상태 그대로 그린다 (호출자가 거울 화소 스텐실을 건다).
 * <ul>
 *   <li>{@link #fill}: 거울 뷰는 프레임 맨 처음 (지운 프레임 위) 에 그려지므로 반사 물체가 없는 거울 화소 = 지움 색 [확실: 순서 0x161A80]</li>
 *   <li>{@link #blur}: 값 [0] ≠ 0 의 후처리 0x120498 → 0x11FEA0 × 2 [확실: GS 패킷] — 뷰 사각형을 32 화소 띠마다 작업 버퍼 (0x330A24) 로
 *       ½ 쌍선형 축소 (UV 오프셋 0x29C9C0 = 0) 한 뒤 프레임으로 쌍선형 확대 (UV +15/16 텍셀, 0x29C9C8 = 15), 블렌드 없음.
 *       GL 은 반해상도 FBO 로 같은 일 [근사: GS 표본점 (화소 왼쪽 위) → GL 화소 중심 환산으로 확대 오프셋 3/16 작업 텍셀]</li>
 * </ul>
 */
public final class MirrorComposite implements AutoCloseable {

	/** 확대 UV 오프셋 (작업 버퍼 텍셀): GS 15/16 − (GS 축소가 반 텍셀 앞선 만큼 3/4) = 3/16 [근사]. */
	static final float UPSCALE_OFFSET = 3f / 16f;

	private final Shader shader = new Shader("blit.vert", "blit.frag");
	private final int vao = GlStats.vao(glGenVertexArrays());
	private int fbo, tex, w, h;

	/** 화면 전체 단색 (스텐실 통과 화소만). 깊이 검사·쓰기 끔. */
	public void fill(float r, float g, float b) {
		boolean depth = glIsEnabled(GL_DEPTH_TEST), blend = glIsEnabled(GL_BLEND);
		glDisable(GL_DEPTH_TEST);
		glDisable(GL_BLEND);
		glDepthMask(false);
		shader.use();
		shader.set("uUseTex", 0);
		shader.set("uColor", new org.joml.Vector4f(r, g, b, 1f));
		glBindVertexArray(vao);
		glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
		glBindVertexArray(0);
		glDepthMask(true);
		if (depth) {
			glEnable(GL_DEPTH_TEST);
		}
		if (blend) {
			glEnable(GL_BLEND);
		}
	}

	/**
	 * 0x11FEA0 를 passes 번: 현재 그리기 프레임버퍼 (width×height) → 반해상도 (쌍선형) → 다시 프레임 (쌍선형, 스텐실 통과 화소만).
	 *
	 * @param factor 축소 배율 (거울 렌더러 +0x18 = 0.5)
	 */
	public void blur(int width, int height, float factor, int passes) {
		int hw = Math.max(1, Math.round(width * factor)), hh = Math.max(1, Math.round(height * factor));
		ensure(hw, hh);
		int drawFbo = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
		int[] vp = new int[4];
		glGetIntegerv(GL_VIEWPORT, vp);
		boolean depth = glIsEnabled(GL_DEPTH_TEST), blend = glIsEnabled(GL_BLEND), stencil = glIsEnabled(GL_STENCIL_TEST);
		for (int i = 0; i < passes; i++) {
			// 1) 축소: 프레임 → 작업 버퍼 (스텐실 무관, 원작도 뷰 사각형 전체)
			glBindFramebuffer(GL_READ_FRAMEBUFFER, drawFbo);
			glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fbo);
			glBlitFramebuffer(0, 0, width, height, 0, 0, hw, hh, GL_COLOR_BUFFER_BIT, GL_LINEAR);
			// 2) 확대: 작업 버퍼 → 프레임 (거울 화소만)
			glBindFramebuffer(GL_FRAMEBUFFER, drawFbo);
			glViewport(vp[0], vp[1], vp[2], vp[3]);
			if (stencil) {
				glEnable(GL_STENCIL_TEST);
			}
			glDisable(GL_DEPTH_TEST);
			glDisable(GL_BLEND);
			glDepthMask(false);
			shader.use();
			glActiveTexture(GL_TEXTURE0);
			glBindTexture(GL_TEXTURE_2D, tex);
			shader.set("uTex", 0);
			shader.set("uUseTex", 1);
			shader.set("uOffset", UPSCALE_OFFSET / hw, UPSCALE_OFFSET / hh);
			glBindVertexArray(vao);
			glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
			glBindVertexArray(0);
			glDepthMask(true);
		}
		if (depth) {
			glEnable(GL_DEPTH_TEST);
		}
		if (blend) {
			glEnable(GL_BLEND);
		}
	}

	private void ensure(int nw, int nh) {
		if (fbo != 0 && nw == w && nh == h) {
			return;
		}
		if (fbo != 0) {
			GlStats.delFbo(fbo);
			GlStats.delTex(tex);
		}
		w = nw;
		h = nh;
		tex = GlStats.tex(glGenTextures());
		glBindTexture(GL_TEXTURE_2D, tex);
		glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		int prev = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
		fbo = GlStats.fbo(glGenFramebuffers());
		glBindFramebuffer(GL_FRAMEBUFFER, fbo);
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
		glBindFramebuffer(GL_FRAMEBUFFER, prev);
	}

	@Override
	public void close() {
		shader.close();
		GlStats.delVao(vao);
		if (fbo != 0) {
			GlStats.delFbo(fbo);
			GlStats.delTex(tex);
		}
	}
}
