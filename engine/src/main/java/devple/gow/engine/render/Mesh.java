package devple.gow.engine.render;

import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL41C.*;

/**
 * GPU 메시. 정점 레이아웃: pos(3) normal(3) uv(2) color(4) = 12 float.
 */
public final class Mesh implements AutoCloseable {

	public static final int STRIDE = 12;

	private final int vao;
	private final int vbo;
	private final int ebo;
	private final int count;
	private final int mode;

	public Mesh(float[] vertices, int[] indices, int mode) {
		this.mode = mode;
		vao = GlStats.vao(glGenVertexArrays());
		glBindVertexArray(vao);
		vbo = GlStats.buf(glGenBuffers());
		glBindBuffer(GL_ARRAY_BUFFER, vbo);
		FloatBuffer fb = MemoryUtil.memAllocFloat(vertices.length).put(vertices).flip();
		glBufferData(GL_ARRAY_BUFFER, fb, GL_STATIC_DRAW);
		MemoryUtil.memFree(fb);
		int stride = STRIDE * Float.BYTES;
		attrib(0, 3, stride, 0);
		attrib(1, 3, stride, 3);
		attrib(2, 2, stride, 6);
		attrib(3, 4, stride, 8);
		if (indices != null) {
			ebo = GlStats.buf(glGenBuffers());
			glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
			IntBuffer ib = MemoryUtil.memAllocInt(indices.length).put(indices).flip();
			glBufferData(GL_ELEMENT_ARRAY_BUFFER, ib, GL_STATIC_DRAW);
			MemoryUtil.memFree(ib);
			count = indices.length;
		} else {
			ebo = 0;
			count = vertices.length / STRIDE;
		}
		glBindVertexArray(0);
	}

	private static void attrib(int idx, int size, int stride, int offFloats) {
		glEnableVertexAttribArray(idx);
		glVertexAttribPointer(idx, size, GL_FLOAT, false, stride, (long) offFloats * Float.BYTES);
	}

	public void draw() {
		glBindVertexArray(vao);
		if (ebo != 0) {
			glDrawElements(mode, count, GL_UNSIGNED_INT, 0);
		} else {
			glDrawArrays(mode, 0, count);
		}
	}

	@Override
	public void close() {
		GlStats.delBuf(vbo);
		if (ebo != 0) {
			GlStats.delBuf(ebo);
		}
		GlStats.delVao(vao);
	}
}
