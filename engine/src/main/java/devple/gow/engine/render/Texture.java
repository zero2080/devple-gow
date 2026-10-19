package devple.gow.engine.render;

import org.lwjgl.system.MemoryUtil;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL41C.*;

/** RGBA8 2D 텍스처. */
public final class Texture implements AutoCloseable {

	private final int id;
	private final int width, height;
	private final boolean repeat;
	/** 노멀맵 출처 (44 문서, 선택 기능): 캐시 키·원본 크기·원본 ARGB 공급자. null = 노멀맵 없음. */
	private String nmKey;
	private int nmSrcW, nmSrcH;
	private java.util.function.Supplier<int[]> nmSource;
	private Texture normalMap;
	private boolean nmBuilt;

	public Texture(int w, int h, ByteBuffer rgba, boolean repeat) {
		width = w;
		height = h;
		this.repeat = repeat;
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

	/** ARGB 배열 (w×h, 2 의 거듭제곱) 그대로 업로드. */
	public static Texture ofArgb(int w, int h, int[] argb, boolean repeat) {
		ByteBuffer buf = MemoryUtil.memAlloc(w * h * 4);
		for (int c : argb) {
			buf.put((byte) (c >> 16)).put((byte) (c >> 8)).put((byte) c).put((byte) (c >>> 24));
		}
		Texture t = new Texture(w, h, buf.flip(), repeat);
		MemoryUtil.memFree(buf);
		return t;
	}

	/**
	 * 노멀맵 출처 등록 (생성은 {@link #normalMap()} 첫 호출까지 미룬다 — 기능이 꺼져 있으면 아무 일도 없다).
	 *
	 * @param key    텍스처 캐시 키 (덮어쓰기 파일 이름)
	 * @param sw/sh  원본 크기 (업로드 크기보다 작으면 패딩)
	 * @param source 원본 ARGB (행 우선) 공급자
	 */
	public Texture withNormalSource(String key, int sw, int sh, java.util.function.Supplier<int[]> source) {
		nmKey = key;
		nmSrcW = sw;
		nmSrcH = sh;
		nmSource = source;
		return this;
	}

	/** 노멀맵 텍스처 (처음 부를 때 만들어 캐시). 출처가 없으면 null. GL 스레드에서만. */
	public Texture normalMap() {
		if (!nmBuilt) {
			nmBuilt = true;
			if (nmSource != null) {
				normalMap = NormalMaps.build(nmKey, width, height, nmSrcW, nmSrcH, repeat, nmSource);
			}
			nmSource = null;
		}
		return normalMap;
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
		if (normalMap != null) {
			normalMap.close();
			normalMap = null;
		}
	}
}
