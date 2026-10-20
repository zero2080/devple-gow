package devple.gow.engine.render;

import devple.gow.engine.light.ShadowVolume;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL41C.*;

/**
 * 그림자 볼륨 그리기 (47 문서 §7.4): 원작은 볼륨 띠를 MAT_stencil (Servers_Create 0x17D7C0 합성 머티리얼: 색 0.4, 레이어 GS 0x44 알파 블렌드,
 * 텍스처 없음, rgba (2, 2, 2, 1)) 의 EE 프림 (0x162140: PRIM 0x44 = 삼각형 띠 + 알파 블렌드) 으로 보낸다. 합성 (GS 에 스텐실이 없다) 의 정확한 방식은
 * 미해석 [미해석] → 엔진은 GL 스텐실 z-fail (Carmack) 로 세고, 0 이 아닌 화소에 검정 알파 0.4 (= MAT_stencil 색) 를 덮는다 [근사].
 */
public final class ShadowRenderer implements AutoCloseable {

	/** 그림자 진하기: 화소 × (1 − 0.4) [근사: MAT_stencil 색 0.4 를 덮는 알파로 해석]. */
	public static final float DARKEN = 0.4f;

	private final Shader shader = new Shader("shadow.vert", "shadow.frag");
	private final int vao = glGenVertexArrays();
	private final int vbo = glGenBuffers();
	private final int screenVao = glGenVertexArrays();
	private FloatBuffer buf = MemoryUtil.memAllocFloat(9 * 1024);

	public ShadowRenderer() {
		glBindVertexArray(vao);
		glBindBuffer(GL_ARRAY_BUFFER, vbo);
		glEnableVertexAttribArray(0);
		glVertexAttribPointer(0, 3, GL_FLOAT, false, 12, 0);
		glBindVertexArray(0);
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
		// 1) 스텐실 세기 (z-fail): 뒷면 깊이 실패 +1, 앞면 깊이 실패 −1. 먼 덮개가 먼 면에 잘리지 않게 깊이 클램프
		glClearStencil(0);
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
		// 2) 스텐실 ≠ 0 화소를 어둡게
		glColorMask(true, true, true, true);
		glDisable(GL_DEPTH_TEST);
		glStencilFunc(GL_NOTEQUAL, 0, 0xFF);
		glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
		glEnable(GL_BLEND);
		glBlendEquation(GL_FUNC_ADD);
		glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
		shader.set("uScreen", 1);
		shader.set("uColor", new org.joml.Vector4f(0, 0, 0, DARKEN));
		glBindVertexArray(screenVao);
		glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
		glBindVertexArray(0);
		// 복원
		glDisable(GL_STENCIL_TEST);
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
		glDeleteBuffers(vbo);
		glDeleteVertexArrays(vao);
		glDeleteVertexArrays(screenVao);
		MemoryUtil.memFree(buf);
	}
}
