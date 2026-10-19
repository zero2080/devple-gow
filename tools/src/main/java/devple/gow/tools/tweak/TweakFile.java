package devple.gow.tools.tweak;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * TWK_ 튜닝 테이블(WAD 태그 0x71) 파서. 텍스트 .twk 파일을 토큰화한 바이너리 형태다.
 * <pre>
 * [u32 0xFEDCBA98, u32 count]   선택적 헤더 (실행 파일 0x17ecb8: 매직이 있으면 8바이트 건너뜀)
 * 반복:
 *   0x80 cstr            절대 경로 (현재 루트, 예 "/Animation/goHero/")
 *   0x10 cstr            하위 그룹 시작 (이름 원문)
 *   0x40                 그룹 끝
 *   0x20 u32 hash u32 v  숫자 값 (float 또는 int; 형식은 실행 파일 쪽 변수 타입이 결정)
 *   0x2N u32 hash u8[k]  N=1..7: 문자열/블록 값, k = 32,64,256,512,1024,2048,4096 (NUL 뒤는 쓰레기 바이트)
 * </pre>
 * 키 이름은 {@link #hash(String)} 로 해시되어 있다.
 */
public final class TweakFile {

	public static final int MAGIC = 0xFEDCBA98;

	/** 0x2N 레코드의 값 크기 (실행 파일 0x2b4250 점프 테이블: N → 크기). */
	static final int[] VALUE_SIZE = {4, 0x20, 0x40, 0x100, 0x200, 0x400, 0x800, 0x1000};

	public enum Kind { PATH, GROUP, END, NUMBER, STRING, UNKNOWN }

	/** 레코드. NUMBER 는 raw 에 32비트 값, STRING/PATH/GROUP 은 text 에 문자열. */
	public record Entry(int offset, Kind kind, int depth, int hash, int raw, String text) {
	}

	private final int count;
	private final List<Entry> entries;

	private TweakFile(int count, List<Entry> entries) {
		this.count = count;
		this.entries = entries;
	}

	/**
	 * 키 해시. 실행 파일 sub_001758a0: {@code h = h * 127 + (signed char) c}, 초기값 0, 대소문자 구분.
	 * (sub_001758e0 은 소문자를 대문자로 바꾼 뒤 같은 연산을 하는 변형)
	 */
	public static int hash(String s) {
		int h = 0;
		for (byte b : s.getBytes(StandardCharsets.ISO_8859_1)) {
			h = h * 127 + b;
		}
		return h;
	}

	/** 대소문자 무시 변형 (sub_001758e0). */
	public static int hashUpper(String s) {
		int h = 0;
		for (byte b : s.getBytes(StandardCharsets.ISO_8859_1)) {
			if (b >= 'a' && b <= 'z') {
				b -= 0x20;
			}
			h = h * 127 + b;
		}
		return h;
	}

	public static TweakFile parse(ByteBuffer src) {
		ByteBuffer b = src.duplicate().order(ByteOrder.LITTLE_ENDIAN);
		int p = 0, count = -1, depth = 0;
		if (b.limit() >= 8 && b.getInt(0) == MAGIC) {
			count = b.getInt(4);
			p = 8;
		}
		List<Entry> out = new ArrayList<>();
		while (p < b.limit()) {
			int t = b.get(p) & 0xFF;
			switch (t) {
				case 0x80, 0x10 -> {
					String s = cstr(b, p + 1, b.limit() - p - 1);
					out.add(new Entry(p, t == 0x80 ? Kind.PATH : Kind.GROUP, depth, 0, 0, s));
					if (t == 0x10) {
						depth++;
					}
					p += 2 + s.length();
				}
				case 0x40 -> {
					depth--;
					out.add(new Entry(p, Kind.END, depth, 0, 0, null));
					p++;
				}
				case 0x20 -> {
					out.add(new Entry(p, Kind.NUMBER, depth, b.getInt(p + 1), b.getInt(p + 5), null));
					p += 9;
				}
				case 0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27 -> {
					int len = VALUE_SIZE[t & 7];
					out.add(new Entry(p, Kind.STRING, depth, b.getInt(p + 1), len, cstr(b, p + 5, len)));
					p += 5 + len;
				}
				case 0x00 -> p++; // 끝 패딩
				default -> {
					out.add(new Entry(p, Kind.UNKNOWN, depth, 0, t, null));
					p = b.limit();
				}
			}
		}
		return new TweakFile(count, out);
	}

	private static String cstr(ByteBuffer b, int off, int max) {
		int n = 0;
		while (n < max && off + n < b.limit() && b.get(off + n) != 0) {
			n++;
		}
		byte[] t = new byte[n];
		b.get(off, t);
		return new String(t, StandardCharsets.ISO_8859_1);
	}

	/** 헤더의 count (헤더가 없으면 -1). */
	public int count() {
		return count;
	}

	public List<Entry> entries() {
		return entries;
	}
}
