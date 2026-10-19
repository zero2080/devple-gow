package devple.gow.tools.ui;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * FLP_ 리소스(매직 0x21) 파서. 플래시(SWF) 를 PS2 용으로 변환한 UI 포맷.
 * <p>
 * 헤더(0x60) 뒤는 <b>스트림</b>: 각 배열은 "고정 크기 레코드 전부 → 각 레코드의 가변 하위 데이터" 순서로 이어지고,
 * 4바이트 정렬이 곳곳에 들어간다. 헤더/레코드 안의 포인터 필드는 툴 머신의 메모리 주소(쓰레기값)라 쓰지 않는다.
 * 문자열은 끝의 문자열 섹션에 모여 있고 레코드는 섹션 내 u16 오프셋으로 참조한다.
 * 구조는 mogaika/god_of_war_browser(pack/wad/flp) 의 분석을 실데이터로 재검증한 것. docs/07 참고.
 * <pre>
 * 0x00 u32 magic 0x21        0x04 u32,0x08 u32 미상(모든 FLP 동일)
 * 0x0c (u32 count, ptr) × 7 : [0]핸들러 [1]메시참조 [2]폰트 [3]정적라벨 [4]동적라벨 [5]Data6(버튼 스프라이트) [6]Data7(무비클립)
 * 0x44 ptr                  : Data8 (루트 타임라인, 1개)
 * 0x48 u16 변환 수, 0x50 u16 블렌드 컬러 수, 0x58 u16 문자열 섹션 크기 (상위 16비트는 쓰레기값)
 * </pre>
 */
public final class FlpFile {

	public static final int MAGIC = 0x21;
	public static final int HEADER_SIZE = 0x60;

	/** 핸들러 타입 = 헤더 배열 번호 + 1 (실측: 1 메시, 3 폰트, 5 동적라벨, 6 Data6, 7 Data7). */
	public static final String[] HANDLER_TYPES = {"none", "mesh", "?2", "font", "static", "dynamic", "sprite", "clip"};

	/** 전역 핸들러: 타임라인 키프레임/라벨이 요소를 가리키는 간접 인덱스. */
	public record Handler(int type, int index) {
		@Override
		public String toString() {
			return (type < HANDLER_TYPES.length ? HANDLER_TYPES[type] : "?" + type) + "#" + index;
		}
	}

	/** 머티리얼 슬롯: 블렌드 컬러(RGBA) + 텍스처 이름(TXR_*). */
	public record Material(int color, String texture) {
	}

	/** MDL_&lt;이름&gt; 모델의 메시 파트 참조. 이미지/글리프는 3D 메시 파트로 그린다. */
	public record MeshRef(int meshPart, List<Material> materials) {
	}

	/**
	 * 폰트. 글리프 i → 메시 파트 / 폭 / 유니코드(UCS-2) 코드.
	 * flags&amp;1 이면 charMap 은 0x100 개짜리 (문자→글리프) 테이블, 아니면 글리프 수만큼의 (글리프→문자) 테이블.
	 */
	public record Font(int glyphCount, int unk04, int size, int unk08, int unk0a, int flags, float scale,
					   List<MeshRef> glyphMeshes, short[] widths, char[] charMap) {

		/** 유니코드 문자 → 글리프 인덱스 (없으면 -1). */
		public int glyphOf(char c) {
			if ((flags & 1) != 0) {
				return c < charMap.length ? charMap[c] : -1;
			}
			for (int i = 0; i < charMap.length; i++) {
				if (charMap[i] == c) {
					return i;
				}
			}
			return -1;
		}

		/** 글리프 인덱스 → 유니코드 문자. */
		public char charOf(int glyph) {
			if ((flags & 1) == 0) {
				return glyph >= 0 && glyph < charMap.length ? charMap[glyph] : '�';
			}
			for (int i = 0; i < charMap.length; i++) {
				if (charMap[i] == glyph) {
					return (char) i;
				}
			}
			return '�';
		}
	}

	/** 정적 라벨 렌더 명령: 폰트/스케일/색/오프셋 변경 + 글리프 나열. */
	public record TextRun(int flags, int fontHandler, float fontScale, int color, float x, float y, List<int[]> glyphs) {
	}

	public record StaticLabel(Transform transform, List<TextRun> runs) {
	}

	/**
	 * 동적 라벨(플래시 EditText 대응). 변수 이름(대개 {@code PS2_<메시지ID>})의 값을 폰트로 출력.
	 * 0x0c 이후 필드 의미는 SWF DefineEditText 와의 대응으로 추정. rect = (x, y, w, h) 1/16 픽셀, align 0 왼쪽 1 오른쪽? 2 가운데.
	 */
	public record DynamicLabel(String variable, String placeholder, int fontHandler, int fontHeight, int color,
							   int maxLength, int[] margins, int leading, int[] rect, int align, int flags) {
	}

	/** 키프레임: 이 프레임까지 handler 요소를 transform/color 로 표시. name 은 인스턴스 이름. */
	public record KeyFrame(int untilFrame, int handler, int transform, int color, String name) {
	}

	/** 요소 애니메이션 (= 플래시 depth 레이어 하나). */
	public record Layer(int frames, List<KeyFrame> keys) {
	}

	/** 프레임 라벨 + 해당 프레임에 실행되는 액션 스크립트들. */
	public record FrameLabel(int frame, String name, List<byte[]> scripts) {
	}

	/** 타임라인(스프라이트/무비클립/루트). bounds 는 추정. */
	public record Timeline(int totalFrames, int[] bounds, List<Layer> layers, List<FrameLabel> labels) {
	}

	/** 입력 이벤트 핸들러 (버튼 마스크 + 스크립트). */
	public record InputHandler(int keyMask, int unk, byte[] script) {
	}

	/** Data6: 입력 핸들러를 가진 스프라이트(메뉴 화면/버튼). */
	public record Sprite(Timeline timeline, List<InputHandler> inputs) {
	}

	/** 2D 변환: 16.16 고정소수 2x2 행렬 + 1/16 픽셀 오프셋. */
	public record Transform(float a, float b, float c, float d, float x, float y) {
	}

	public final int unk04, unk08;
	public final List<Handler> handlers = new ArrayList<>();
	public final List<MeshRef> meshRefs = new ArrayList<>();
	public final List<Font> fonts = new ArrayList<>();
	public final List<StaticLabel> staticLabels = new ArrayList<>();
	public final List<DynamicLabel> dynamicLabels = new ArrayList<>();
	public final List<Sprite> sprites = new ArrayList<>();
	public final List<Timeline> clips = new ArrayList<>();
	public final Timeline root;
	public final List<Transform> transforms = new ArrayList<>();
	/** RGBA 각 u16 (0..256 범위, 256 = 1.0). */
	public final List<int[]> colors = new ArrayList<>();
	/** 문자열 섹션 원본. */
	public final byte[] strings;
	/** 파싱 후 위치가 문자열 섹션 시작과 정확히 맞았는지 (구조 검증용). */
	public final boolean consistent;

	private final ByteBuffer b;
	private final int strBase;
	private int pos;

	public FlpFile(ByteBuffer data) {
		this.b = data.duplicate().order(ByteOrder.LITTLE_ENDIAN);
		if (b.getInt(0) != MAGIC) {
			throw new IllegalArgumentException("not FLP: magic=" + Integer.toHexString(b.getInt(0)));
		}
		unk04 = b.getInt(4);
		unk08 = b.getInt(8);
		int nHandlers = cnt(0x0c), nMesh = cnt(0x14), nFont = cnt(0x1c), nStatic = cnt(0x24), nDyn = cnt(0x2c);
		int nSprite = cnt(0x34), nClip = cnt(0x3c), nXf = cnt(0x48), nColor = cnt(0x50), strSize = cnt(0x58);
		strBase = b.limit() - strSize;
		strings = new byte[strSize];
		b.get(strBase, strings);

		pos = HEADER_SIZE;
		for (int i = 0; i < nHandlers; i++) {
			handlers.add(new Handler(u16(), u16()));
		}
		meshRefs.addAll(meshRefs(nMesh));

		int[][] fh = new int[nFont][];
		float[] fscale = new float[nFont];
		for (int i = 0; i < nFont; i++) {
			int p = pos;
			fh[i] = new int[]{b.getInt(p), u16at(p + 4), (short) u16at(p + 6), u16at(p + 8), u16at(p + 10), u16at(p + 12)};
			fscale[i] = b.getFloat(p + 0x20);
			pos += 0x24;
		}
		for (int i = 0; i < nFont; i++) {
			int n = fh[i][0], flags = fh[i][5];
			List<MeshRef> gm = (flags & 6) != 0 ? meshRefs(n) : List.of();
			short[] w = new short[n];
			for (int j = 0; j < n; j++) {
				w[j] = (short) u16();
			}
			align();
			char[] map = new char[(flags & 1) != 0 ? 0x100 : n];
			for (int j = 0; j < map.length; j++) {
				map[j] = (char) u16();
			}
			align();
			fonts.add(new Font(n, fh[i][1], fh[i][2], fh[i][3], fh[i][4], flags, fscale[i], gm, w, map));
		}

		int[] slLen = new int[nStatic];
		Transform[] slXf = new Transform[nStatic];
		for (int i = 0; i < nStatic; i++) {
			slXf[i] = transformAt(pos);
			slLen[i] = b.getInt(pos + 0x14);
			pos += 0x24;
		}
		for (int i = 0; i < nStatic; i++) {
			staticLabels.add(new StaticLabel(slXf[i], textRuns(pos, slLen[i])));
			pos += slLen[i];
			align();
		}

		for (int i = 0; i < nDyn; i++) {
			int p = pos;
			dynamicLabels.add(new DynamicLabel(str(u16at(p)), str(u16at(p + 2)), u16at(p + 4), u16at(p + 6), b.getInt(p + 8),
					u16at(p + 0xc), new int[]{u16at(p + 0xe), u16at(p + 0x10), u16at(p + 0x12)}, u16at(p + 0x14),
					new int[]{(short) u16at(p + 0x16), (short) u16at(p + 0x18), (short) u16at(p + 0x1a), (short) u16at(p + 0x1c)},
					b.get(p + 0x1e) & 0xFF, b.get(p + 0x1f) & 0xFF));
			pos += 0x20;
		}

		int[] nInputs = new int[nSprite];
		for (int i = 0; i < nSprite; i++) {
			nInputs[i] = u16at(pos + 8);
			pos += 0xc;
		}
		for (int i = 0; i < nSprite; i++) {
			align();
			Timeline t = timeline();
			int[][] ih = new int[nInputs[i]][];
			for (int j = 0; j < ih.length; j++) {
				ih[j] = new int[]{b.getInt(pos), b.getInt(pos + 8), u16at(pos + 0xc)};
				pos += 0x10;
			}
			List<InputHandler> ins = new ArrayList<>();
			for (int[] h : ih) {
				ins.add(new InputHandler(h[1], h[2], bytes(h[0])));
			}
			sprites.add(new Sprite(t, ins));
		}

		align();
		List<int[]> clipHeads = new ArrayList<>();
		for (int i = 0; i < nClip; i++) {
			clipHeads.add(timelineHead());
		}
		for (int[] h : clipHeads) {
			clips.add(timelineBody(h));
		}
		align();
		root = timeline();
		align();
		for (int i = 0; i < nXf; i++) {
			transforms.add(transformAt(pos));
			pos += 0x14;
		}
		for (int i = 0; i < nColor; i++) {
			colors.add(new int[]{u16(), u16(), u16(), u16()});
		}
		consistent = pos == strBase;
	}

	private int cnt(int off) {
		return u16at(off);
	}

	private int u16at(int p) {
		return Short.toUnsignedInt(b.getShort(p));
	}

	private int u16() {
		int v = u16at(pos);
		pos += 2;
		return v;
	}

	private void align() {
		pos = (pos + 3) & ~3;
	}

	private byte[] bytes(int n) {
		align();
		byte[] out = new byte[n];
		b.get(pos, out);
		pos += n;
		return out;
	}

	/** 문자열 섹션 오프셋 → 문자열 (0xFFFF = 없음). */
	public String str(int off) {
		if (off == 0xFFFF || off < 0 || off >= strings.length) {
			return null;
		}
		int end = off;
		while (end < strings.length && strings[end] != 0) {
			end++;
		}
		return new String(strings, off, end - off, StandardCharsets.ISO_8859_1);
	}

	/** 스크립트용: 오프셋이 문자열 경계가 아니면 '!' 를 붙여 구조 오류를 드러낸다. */
	public String scriptStr(int off) {
		String s = str(off);
		return off > 0 && off < strings.length && strings[off - 1] != 0 ? "!" + s : s;
	}

	private List<MeshRef> meshRefs(int n) {
		int[][] h = new int[n][];
		for (int i = 0; i < n; i++) {
			h[i] = new int[]{(short) u16at(pos), u16at(pos + 2)};
			pos += 8;
		}
		List<MeshRef> out = new ArrayList<>();
		for (int[] r : h) {
			List<Material> mats = new ArrayList<>();
			for (int j = 0; j < r[1]; j++) {
				int tex = b.getInt(pos + 4);
				mats.add(new Material(b.getInt(pos), tex == -1 ? null : str(tex & 0xFFFF)));
				pos += 8;
			}
			out.add(new MeshRef(r[0], mats));
		}
		return out;
	}

	private Transform transformAt(int p) {
		return new Transform(b.getInt(p) / 65536f, b.getInt(p + 4) / 65536f, b.getInt(p + 8) / 65536f, b.getInt(p + 12) / 65536f,
				b.getShort(p + 0x10) / 16f, b.getShort(p + 0x12) / 16f);
	}

	/** 정적 라벨 명령 스트림: 0x80|flags 헤더(8 폰트, 4 색, 2 X, 1 Y) 또는 n(&lt;0x80)개 (glyph u16, advance s16/16). */
	private List<TextRun> textRuns(int p, int len) {
		List<TextRun> out = new ArrayList<>();
		int end = p + len;
		int flags = 0, font = -1, color = 0;
		float scale = 0, x = 0, y = 0;
		List<int[]> glyphs = null;
		while (p < end) {
			int op = b.get(p++) & 0xFF;
			if ((op & 0x80) != 0) {
				if (glyphs != null) {
					out.add(new TextRun(flags, font, scale, color, x, y, glyphs));
				}
				flags = op & 0x7F;
				glyphs = new ArrayList<>();
				if ((op & 8) != 0) {
					font = u16at(p);
					scale = b.getShort(p + 2) / 1024f;
					p += 4;
				}
				if ((op & 4) != 0) {
					color = b.getInt(p);
					p += 4;
				}
				if ((op & 2) != 0) {
					x = b.getShort(p) / 16f;
					p += 2;
				}
				if ((op & 1) != 0) {
					y = b.getShort(p) / 16f;
					p += 2;
				}
			} else {
				if (glyphs == null) {
					glyphs = new ArrayList<>();
				}
				for (int j = 0; j < op; j++) {
					glyphs.add(new int[]{u16at(p), b.getShort(p + 2)});
					p += 4;
				}
			}
		}
		if (glyphs != null) {
			out.add(new TextRun(flags, font, scale, color, x, y, glyphs));
		}
		return out;
	}

	/** 타임라인 헤더(0x18): frames, layer 수, label 수, bounds(s16×4, 추정). */
	private int[] timelineHead() {
		int p = pos;
		pos += 0x18;
		return new int[]{u16at(p), u16at(p + 2), u16at(p + 4),
				(short) u16at(p + 6), (short) u16at(p + 8), (short) u16at(p + 10), (short) u16at(p + 12)};
	}

	private Timeline timeline() {
		return timelineBody(timelineHead());
	}

	private Timeline timelineBody(int[] h) {
		align();
		int[][] lh = new int[h[1]][];
		for (int i = 0; i < lh.length; i++) {
			lh[i] = new int[]{u16at(pos), u16at(pos + 2)};
			pos += 8;
		}
		List<Layer> layers = new ArrayList<>();
		for (int[] l : lh) {
			align();
			List<KeyFrame> keys = new ArrayList<>();
			for (int k = 0; k < l[1]; k++) {
				keys.add(new KeyFrame(u16at(pos), u16at(pos + 2), u16at(pos + 4), u16at(pos + 6), str(u16at(pos + 8))));
				pos += 10;
			}
			layers.add(new Layer(l[0], keys));
		}
		align();
		int[][] fl = new int[h[2]][];
		for (int i = 0; i < fl.length; i++) {
			fl[i] = new int[]{u16at(pos), u16at(pos + 2), u16at(pos + 8)};
			pos += 0xc;
		}
		List<FrameLabel> labels = new ArrayList<>();
		for (int[] f : fl) {
			align();
			int[] lens = new int[f[1]];
			for (int j = 0; j < lens.length; j++) {
				lens[j] = b.getInt(pos);
				pos += 8;
			}
			List<byte[]> scripts = new ArrayList<>();
			for (int len : lens) {
				scripts.add(bytes(len));
			}
			labels.add(new FrameLabel(f[0], str(f[2]), scripts));
		}
		return new Timeline(h[0], new int[]{h[3], h[4], h[5], h[6]}, layers, labels);
	}

	/** 핸들러 인덱스 → 사람이 읽을 이름. */
	public String handlerName(int idx) {
		if (idx < 0 || idx >= handlers.size()) {
			return "?" + idx;
		}
		Handler h = handlers.get(idx);
		String s = h.toString();
		if (h.type() == 5 && h.index() < dynamicLabels.size()) {
			s += "(" + dynamicLabels.get(h.index()).variable() + ")";
		}
		return s;
	}

	/** 정적 라벨을 폰트 매핑으로 유니코드 문자열로 복원. */
	public String staticText(StaticLabel sl) {
		StringBuilder sb = new StringBuilder();
		for (TextRun r : sl.runs()) {
			Font f = fontOf(r.fontHandler());
			for (int[] g : r.glyphs()) {
				sb.append(f != null ? f.charOf(g[0]) : '?');
			}
			if ((r.flags() & 1) != 0 && !sb.isEmpty()) {
				sb.append('\n');
			}
		}
		return sb.toString().strip();
	}

	public Font fontOf(int handlerIdx) {
		if (handlerIdx >= 0 && handlerIdx < handlers.size()) {
			Handler h = handlers.get(handlerIdx);
			if (h.type() == 3 && h.index() < fonts.size()) {
				return fonts.get(h.index());
			}
		}
		return fonts.size() == 1 ? fonts.get(0) : null;
	}
}
