package devple.gow.engine.render;

import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL41C.*;

/**
 * 스키닝 메시. 정점 레이아웃: pos(3) normal(3) uv(2) color(4) joints(2) weight(1) = 15 float.
 * 관절 인덱스는 float 로 넣고 셰이더에서 int 변환 (GL 4.1 에서 정수 속성도 가능하지만 레이아웃 단순화).
 */
public final class SkinnedMesh implements AutoCloseable {

	public static final int STRIDE = 15;

	private final int vao;
	private final int vbo;
	private final int ebo;
	private final int count;

	public SkinnedMesh(float[] vertices, int[] indices) {
		vao = GlStats.vao(glGenVertexArrays());
		glBindVertexArray(vao);
		vbo = GlStats.buf(glGenBuffers());
		glBindBuffer(GL_ARRAY_BUFFER, vbo);
		FloatBuffer fb = MemoryUtil.memAllocFloat(vertices.length).put(vertices).flip();
		glBufferData(GL_ARRAY_BUFFER, fb, GL_STATIC_DRAW);
		MemoryUtil.memFree(fb);
		int stride = STRIDE * Float.BYTES;
		int[][] attrs = {{3, 0}, {3, 3}, {2, 6}, {4, 8}, {2, 12}, {1, 14}};
		for (int i = 0; i < attrs.length; i++) {
			glEnableVertexAttribArray(i);
			glVertexAttribPointer(i, attrs[i][0], GL_FLOAT, false, stride, (long) attrs[i][1] * Float.BYTES);
		}
		ebo = GlStats.buf(glGenBuffers());
		glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
		IntBuffer ib = MemoryUtil.memAllocInt(indices.length).put(indices).flip();
		glBufferData(GL_ELEMENT_ARRAY_BUFFER, ib, GL_STATIC_DRAW);
		MemoryUtil.memFree(ib);
		count = indices.length;
		glBindVertexArray(0);
	}

	public void draw() {
		glBindVertexArray(vao);
		glDrawElements(GL_TRIANGLES, count, GL_UNSIGNED_INT, 0);
	}

	@Override
	public void close() {
		GlStats.delBuf(vbo);
		GlStats.delBuf(ebo);
		GlStats.delVao(vao);
	}
}
