package devple.gow.tools.video;

import org.jcodec.codecs.mpeg12.MPEGDecoder;
import org.jcodec.common.model.Picture;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.function.Consumer;

/**
 * MPEG-2 비디오 ES → 표시 순서 {@link VideoFrame}. 디코딩은 JCodec {@link MPEGDecoder} (순수 Java, 33 문서 §2).
 * <p>
 * JCodec 은 "픽처 하나 분량" 버퍼를 받아 디코딩 순서대로 돌려주므로, 여기서
 * (1) ES 를 시퀀스/GOP/픽처 헤더 경계로 잘라 액세스 유닛을 만들고 (필드 픽처는 두 필드를 한 유닛으로),
 * (2) I/P 는 다음 I/P 가 올 때까지 붙잡아 두는 방식으로 표시 순서로 재정렬한다.
 */
public final class Mpeg2Video {

	private static final double[] FPS = {0, 24000 / 1001.0, 24, 25, 30000 / 1001.0, 30, 50, 60000 / 1001.0, 60};

	private final MPEGDecoder dec = new MPEGDecoder();
	private final Consumer<VideoFrame> out;
	private byte[] es = new byte[1 << 20];
	private int esLen;
	private int scan;
	private int unitStart = -1;
	private boolean sawSlice;
	private int fieldsInUnit;
	private boolean fieldUnit;
	private byte[][] work;
	private VideoFrame held;
	private int shown;
	private int errors;
	private long decodeNanos;
	private int decoded;

	private int width, height, aspectCode, rateCode;

	public Mpeg2Video(Consumer<VideoFrame> out) {
		this.out = out;
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}

	/** aspect_ratio_information: 2 = 4:3, 3 = 16:9. */
	public int aspectCode() {
		return aspectCode;
	}

	public double fps() {
		return rateCode > 0 && rateCode < FPS.length ? FPS[rateCode] : 30000 / 1001.0;
	}

	public int errors() {
		return errors;
	}

	public int decoded() {
		return decoded;
	}

	public long decodeNanos() {
		return decodeNanos;
	}

	/** ES 바이트를 넣는다. 완성된 유닛은 즉시 디코딩되어 out 으로 나간다. */
	public void feed(byte[] src, int off, int len) {
		if (esLen + len > es.length) {
			es = Arrays.copyOf(es, Math.max(es.length * 2, esLen + len));
		}
		System.arraycopy(src, off, es, esLen, len);
		esLen += len;
		int i = Math.max(scan, 0);
		while (i + 3 < esLen) {
			if (es[i + 2] > 1) {
				i += 3;
				continue;
			}
			if (es[i] != 0 || es[i + 1] != 0 || es[i + 2] != 1) {
				i++;
				continue;
			}
			if (i + 7 >= esLen) {
				break;   // 헤더 바이트가 아직 덜 들어옴
			}
			int code = es[i + 3] & 0xFF;
			boolean unitHead = code == 0xB3 || code == 0xB8 || code == 0x00;
			if (unitHead && sawSlice && !(code == 0x00 && fieldUnit && fieldsInUnit == 1)) {
				emit(unitStart, i);
				unitStart = -1;
			}
			if (unitHead && unitStart < 0) {
				unitStart = i;
				sawSlice = false;
				fieldsInUnit = 0;
				fieldUnit = false;
			}
			if (code == 0x00) {
				fieldsInUnit++;
				sawSlice = false;
			} else if (code == 0xB3) {
				width = ((es[i + 4] & 0xFF) << 4) | ((es[i + 5] & 0xFF) >> 4);
				height = ((es[i + 5] & 0x0F) << 8) | (es[i + 6] & 0xFF);
				aspectCode = (es[i + 7] & 0xFF) >> 4;
				rateCode = es[i + 7] & 0x0F;
			} else if (code == 0xB5 && ((es[i + 4] & 0xFF) >> 4) == 8) {
				fieldUnit = (es[i + 6] & 3) != 3;   // picture_structure: 3 = 프레임
			} else if (code >= 0x01 && code <= 0xAF) {
				sawSlice = true;
			} else if (code == 0xB7 && sawSlice) {
				emit(unitStart, i);
				unitStart = -1;
			}
			i += 4;
		}
		// 처리 끝난 앞부분 버리기
		int keep = unitStart >= 0 ? unitStart : Math.max(0, i - 3);
		if (keep > 0) {
			System.arraycopy(es, keep, es, 0, esLen - keep);
			esLen -= keep;
			i -= keep;
			if (unitStart >= 0) {
				unitStart -= keep;
			}
		}
		scan = i;
	}

	/** 스트림 끝: 남은 유닛과 붙잡아 둔 기준 프레임을 내보낸다. */
	public void flush() {
		if (unitStart >= 0 && sawSlice) {
			emit(unitStart, esLen);
		}
		unitStart = -1;
		esLen = 0;
		scan = 0;
		if (held != null) {
			show(held);
			held = null;
		}
	}

	private void emit(int from, int to) {
		int type = 0;
		for (int i = from; i + 5 < to; i++) {
			if (es[i] == 0 && es[i + 1] == 0 && es[i + 2] == 1 && es[i + 3] == 0) {
				type = ((es[i + 5] & 0xFF) >> 3) & 7;
				break;
			}
		}
		if (width == 0 || type == 0) {
			return;
		}
		int cw = (width + 15) & ~15;
		int ch = (height + 31) & ~31;
		if (work == null || work[0].length < cw * ch) {
			work = new byte[][]{new byte[cw * ch], new byte[cw * ch], new byte[cw * ch]};   // JCodec 은 평면마다 전체 크기를 요구
		}
		Picture pic;
		long t0 = System.nanoTime();
		try {
			pic = dec.decodeFrame(ByteBuffer.wrap(es, from, to - from).slice(), work);
		} catch (RuntimeException e) {
			errors++;   // 열린 GOP 첫 B 프레임(참조 없음) 등
			return;
		} finally {
			decodeNanos += System.nanoTime() - t0;
		}
		if (pic == null) {
			errors++;
			return;
		}
		decoded++;
		VideoFrame f = convert(pic);
		if (type == 3) {
			show(f);
		} else {
			if (held != null) {
				show(held);
			}
			held = f;
		}
	}

	private VideoFrame convert(Picture pic) {
		int w = width, h = height;
		byte[] y = new byte[w * h], u = new byte[w * h / 4], v = new byte[w * h / 4];
		copy(pic.getPlaneData(0), pic.getPlaneWidth(0), y, w, h);
		copy(pic.getPlaneData(1), pic.getPlaneWidth(1), u, w / 2, h / 2);
		copy(pic.getPlaneData(2), pic.getPlaneWidth(2), v, w / 2, h / 2);
		return new VideoFrame(-1, 0, w, h, y, u, v);
	}

	/** JCodec 픽셀은 부호 있는 값(-128..127) → ^0x80 로 0..255. */
	private static void copy(byte[] src, int stride, byte[] dst, int w, int h) {
		for (int r = 0; r < h; r++) {
			int s = r * stride, d = r * w;
			for (int c = 0; c < w; c++) {
				dst[d + c] = (byte) (src[s + c] ^ 0x80);
			}
		}
	}

	private void show(VideoFrame f) {
		int n = shown++;
		out.accept(new VideoFrame(n, n / fps(), f.width(), f.height(), f.y(), f.u(), f.v()));
	}
}
