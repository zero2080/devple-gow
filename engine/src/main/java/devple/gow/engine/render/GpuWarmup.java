package devple.gow.engine.render;

import static org.lwjgl.opengl.GL41C.*;

/**
 * 1×1 화면 밖 FBO (48 문서 §9.7, 원작에 없는 엔진 전용) — 장면 전환 페이드 동안 새 레벨의 메시·텍스처를 한 번씩 그려 드라이버의 지연 작업
 * (텍스처·정점 버퍼의 첫 GPU 올리기, 상태 조합 검증) 을 첫 화면 그리기 전에 끝낸다. 화면·깊이·게임 상태에는 아무것도 남기지 않는다:
 * {@link #begin} 이 이 FBO 와 1×1 뷰포트로 바꾸고 {@link #end} 가 이전 FBO·뷰포트로 되돌린다.
 * 색 RGBA8 + 깊이 24/스텐실 8 (기본 프레임버퍼와 같은 구성).
 */
public final class GpuWarmup implements AutoCloseable {

	private int fbo, color, depth;
	private final int[] viewport = new int[4];
	private int prevFbo;
	private boolean active;

	public void begin() {
		if (active) {
			return;
		}
		prevFbo = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
		glGetIntegerv(GL_VIEWPORT, viewport);
		if (fbo == 0) {
			color = GlStats.tex(glGenTextures());
			glBindTexture(GL_TEXTURE_2D, color);
			glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
			depth = GlStats.tex(glGenTextures());
			glBindTexture(GL_TEXTURE_2D, depth);
			glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH24_STENCIL8, 1, 1, 0, GL_DEPTH_STENCIL, GL_UNSIGNED_INT_24_8, (java.nio.ByteBuffer) null);
			glBindTexture(GL_TEXTURE_2D, 0);
			fbo = GlStats.fbo(glGenFramebuffers());
			glBindFramebuffer(GL_FRAMEBUFFER, fbo);
			glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, color, 0);
			glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_TEXTURE_2D, depth, 0);
		} else {
			glBindFramebuffer(GL_FRAMEBUFFER, fbo);
		}
		glViewport(0, 0, 1, 1);
		glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT | GL_STENCIL_BUFFER_BIT);
		active = true;
	}

	public void end() {
		if (!active) {
			return;
		}
		glBindFramebuffer(GL_FRAMEBUFFER, prevFbo);
		glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
		active = false;
	}

	@Override
	public void close() {
		if (fbo != 0) {
			GlStats.delFbo(fbo);
			GlStats.delTex(color);
			GlStats.delTex(depth);
			fbo = 0;
		}
	}
}
