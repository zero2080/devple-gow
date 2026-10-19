package devple.gow.engine.render;

import org.lwjgl.system.MemoryUtil;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL41C.*;

/** RGBA8 2D 텍스처. */
public final class Texture implements AutoCloseable {

	private final int id;

	public Texture(int w, int h, ByteBuffer rgba, boolean repeat) {
		id = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, id);
		glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
		glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, rgba);
		int wrap = repeat ? GL_REPEAT : GL_CLAMP_TO_EDGE;
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, wrap);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, wrap);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glGenerateMipmap(GL_TEXTURE_2D);
	}

	public static Texture of(BufferedImage img, boolean repeat) {
		int w = img.getWidth();
		int h = img.getHeight();
		ByteBuffer buf = MemoryUtil.memAlloc(w * h * 4);
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int argb = img.getRGB(x, y);
				buf.put((byte) (argb >> 16)).put((byte) (argb >> 8)).put((byte) argb).put((byte) (argb >>> 24));
			}
		}
		Texture t = new Texture(w, h, buf.flip(), repeat);
		MemoryUtil.memFree(buf);
		return t;
	}

	public static Texture white() {
		ByteBuffer b = MemoryUtil.memAlloc(4).put(new byte[]{-1, -1, -1, -1}).flip();
		Texture t = new Texture(1, 1, b, true);
		MemoryUtil.memFree(b);
		return t;
	}

	public void bind(int unit) {
		glActiveTexture(GL_TEXTURE0 + unit);
		glBindTexture(GL_TEXTURE_2D, id);
	}

	@Override
	public void close() {
		glDeleteTextures(id);
	}
}
