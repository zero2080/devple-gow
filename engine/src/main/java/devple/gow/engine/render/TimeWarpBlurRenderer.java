package devple.gow.engine.render;

import static org.lwjgl.opengl.GL41C.*;

/**
 * 시간 왜곡 흐림 화면 패스 ({@link devple.gow.engine.fx.TimeWarpBlur}, 원작 0x142A28) — 앞 프레임 결과를 텍스처로 두고, 가장자리에서 확대 값만큼
 * 안쪽 UV 로 화면을 덮어 (선형 필터) 색을 곱한 뒤 (GS MODULATE: × RGB·255/128) 지금 장면과 FIX 로 섞는다: 결과 = Cs·(1 − A) + Cd·A
 * (ALPHA 0x21, FIX = A·128). 끝난 화면을 다음 프레임 텍스처로 복사한다 (원작은 더블 버퍼의 앞 화면을 TEX0 로 [추정: 버퍼 주소 0x2FF4D0]).
 * 확대 단위는 원작 640 × 448 프레임 버퍼의 1/16 화소 [근사: 엔진 화면 비율로 환산].
 */
public final class TimeWarpBlurRenderer implements AutoCloseable {

	private final Shader shader = new Shader("timewarp.vert", "timewarp.frag");
	private final int vao = GlStats.vao(glGenVertexArrays());
	private final int[] viewport = new int[4];
	private int tex, texW, texH;
	private boolean primed;

	/** 효과가 새로 시작될 때 (앞 화면이 아직 없음 — 첫 프레임은 지금 장면을 그대로 담는다). */
	public void reset() {
		primed = false;
	}

	/** @param s {@link devple.gow.engine.fx.TimeWarpBlur#sample()} = {R, G, B, A, 확대} */
	public void draw(float[] s) {
		glGetIntegerv(GL_VIEWPORT, viewport);
		int w = viewport[2], h = viewport[3];
		if (w <= 0 || h <= 0) {
			return;
		}
		if (tex == 0) {
			tex = GlStats.tex(glGenTextures());
		}
		glActiveTexture(GL_TEXTURE0);
		glBindTexture(GL_TEXTURE_2D, tex);
		if (w != texW || h != texH) {
			glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
			texW = w;
			texH = h;
			primed = false;
		}
		if (primed) {
			float zx = s[4] / 16f / 640f, zy = s[4] / 16f / 448f; // 1/16 화소 → 텍스처 좌표 (원작 프레임 640 × 448)
			shader.use();
			shader.set("uTex", 0);
			shader.set("uInset", zx, zy);
			float k = 255f / 128f; // RGBAQ = 색·255, MODULATE 0x80 = 1
			shader.set("uColor", s[0] * k, s[1] * k, s[2] * k);
			boolean depth = glIsEnabled(GL_DEPTH_TEST);
			glDisable(GL_DEPTH_TEST);
			glDepthMask(false);
			glEnable(GL_BLEND);
			glBlendEquation(GL_FUNC_ADD);
			float a = Math.max(0f, Math.min(1f, s[3]));
			glBlendColor(0, 0, 0, a);
			glBlendFunc(GL_ONE_MINUS_CONSTANT_ALPHA, GL_CONSTANT_ALPHA); // Cs·(1 − FIX) + Cd·FIX
			glBindVertexArray(vao);
			glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
			glBindVertexArray(0);
			glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
			glDisable(GL_BLEND);
			glDepthMask(true);
			if (depth) {
				glEnable(GL_DEPTH_TEST);
			}
			glBindTexture(GL_TEXTURE_2D, tex);
		}
		glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, viewport[0], viewport[1], w, h);
		primed = true;
	}

	@Override
	public void close() {
		shader.close();
		if (tex != 0) {
			GlStats.delTex(tex);
		}
		GlStats.delVao(vao);
	}
}
