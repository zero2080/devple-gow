package devple.gow.engine.render;

import org.lwjgl.system.MemoryUtil;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;

import static org.lwjgl.opengl.GL41C.*;

/** 현재 백버퍼를 PNG 로 저장 (자동 검증용). */
public final class Screenshot {

	private Screenshot() {
	}

	public static void save(int w, int h, Path out) throws IOException {
		save(w, h, out, "png");
	}

	public static void save(int w, int h, Path out, String format) throws IOException {
		ByteBuffer buf = MemoryUtil.memAlloc(w * h * 4);
		glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, buf);
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		int[] row = new int[w];
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int i = ((h - 1 - y) * w + x) * 4;
				row[x] = (buf.get(i) & 0xFF) << 16 | (buf.get(i + 1) & 0xFF) << 8 | (buf.get(i + 2) & 0xFF);
			}
			img.setRGB(0, y, w, 1, row, 0, w);
		}
		MemoryUtil.memFree(buf);
		ImageIO.write(img, format, out.toFile());
	}
}
