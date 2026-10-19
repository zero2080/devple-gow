package devple.gow.engine.render;

import devple.gow.engine.fx.CameraFilter;
import org.joml.Vector3f;

import static org.lwjgl.opengl.GL41C.*;

/**
 * 카메라 필터 화면 덮개 (원작 필터 노드 0x29CF6C, 그리기 0x121128, 45 문서 §1).
 * GS 상태: TEST ZTE=1 ZTST=ALWAYS (깊이 무시), ALPHA 0x44 블렌드 = (SRC_ALPHA, 1−SRC_ALPHA), 0x48 가산 = (SRC_ALPHA, ONE),
 * 0x42 감산 = 역감산 (SRC_ALPHA, ONE). 색은 R·255 / A·128 이라 GL 에서는 RGB 그대로, A 그대로 (1.0 = 128).
 */
public final class CameraFilterRenderer implements AutoCloseable {

	private final Shader shader = new Shader("camfilter.vert", "camfilter.frag");
	private final int vao = glGenVertexArrays();

	/** 3D 장면 뒤 · HUD 앞에 한 번. */
	public void draw(CameraFilter f, Camera cam) {
		if (f == null || !f.visible()) {
			return;
		}
		Vector3f fwd = new Vector3f(cam.target).sub(cam.eye);
		if (fwd.lengthSquared() < 1e-12f) {
			return;
		}
		fwd.normalize();
		float[] r = CameraFilter.screenRange(fwd.y, (float) Math.toRadians(cam.fovDeg));
		shader.use();
		shader.set("uRange", r[0], r[1]);
		shader.set("uPoints", f.points()[0], f.points()[1], f.points()[2]);
		float[][] c = f.colors();
		shader.set("uTop", c[0][0], c[0][1], c[0][2], c[0][3]);
		shader.set("uMid", c[1][0], c[1][1], c[1][2], c[1][3]);
		shader.set("uBottom", c[2][0], c[2][1], c[2][2], c[2][3]);
		boolean depth = glIsEnabled(GL_DEPTH_TEST);
		glDisable(GL_DEPTH_TEST);
		glDepthMask(false);
		glEnable(GL_BLEND);
		switch (f.blendMode()) {
			case CameraFilter.ADD -> {
				glBlendEquation(GL_FUNC_ADD);
				glBlendFunc(GL_SRC_ALPHA, GL_ONE);
			}
			case CameraFilter.SUB -> {
				glBlendEquation(GL_FUNC_REVERSE_SUBTRACT);
				glBlendFunc(GL_SRC_ALPHA, GL_ONE);
			}
			default -> {
				glBlendEquation(GL_FUNC_ADD);
				glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
			}
		}
		glBindVertexArray(vao);
		glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
		glBindVertexArray(0);
		glBlendEquation(GL_FUNC_ADD);
		glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
		glDisable(GL_BLEND);
		glDepthMask(true);
		if (depth) {
			glEnable(GL_DEPTH_TEST);
		}
	}

	@Override
	public void close() {
		glDeleteVertexArrays(vao);
		shader.close();
	}
}
