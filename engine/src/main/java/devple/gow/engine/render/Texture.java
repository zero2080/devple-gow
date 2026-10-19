package devple.gow.engine.render;

import org.lwjgl.system.MemoryUtil;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL41C.*;

/** RGBA8 2D 텍스처. */
public final class Texture implements AutoCloseable {

	private final int id;
	private final int width, height;

	public Texture(int w, int h, ByteBuffer rgba, boolean repeat) {
		width = w;
		height = h;
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

	/**
	 * GS 텍스처는 TEX0 TW/TH = 2 의 거듭제곱 크기로 샘플링되고 UV 도 그 크기 기준이다. 데이터가 그보다 작으면(예 FLP_Athn01E 의
	 * 히드라 미터 그림 TXR_Athn01E003 512×72 → 512×128) 오른쪽·아래를 투명으로 채워 같은 UV 가 같은 텍셀을 가리키게 한다.
	 */
	public static Texture of(BufferedImage img, boolean repeat) {
		int iw = img.getWidth();
		int ih = img.getHeight();
		int w = Integer.bitCount(iw) == 1 ? iw : Integer.highestOneBit(iw) << 1;
		int h = Integer.bitCount(ih) == 1 ? ih : Integer.highestOneBit(ih) << 1;
		ByteBuffer buf = MemoryUtil.memAlloc(w * h * 4);
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int argb = x < iw && y < ih ? img.getRGB(x, y) : 0;
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

	/** 업로드 크기 (GS TW/TH 거듭제곱). */
	public int width() {
		return width;
	}

	public int height() {
		return height;
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
