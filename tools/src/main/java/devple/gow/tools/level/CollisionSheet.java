package devple.gow.tools.level;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * 정적 충돌 메시 "SheetHdr" (COL 서버 0x11, 매직 0x00000011, 보통 {@code RIB_sheet} 하나). 정점은 <b>월드 좌표</b>.
 * <pre>
 * 0x00 u32 0x11            0x04 "SheetHdr"        0x0C u32 크기       0x10 u32 0x1F   0x14 u8[4] 01 02 14 02
 * 0x18 vec4 AABB min       0x28 vec4 AABB max
 * 0x38 u16 ?, u16 ?
 * 0x3C u16 treeCount       0x3E u16 leafCount     0x40 u32 indexCount
 * 0x44 u16 ?               0x46 u16 triCount      0x48 u16 quadCount  0x4A u16 vertexCount
 * 0x4C u16 64, u16 64      0x50 u16 surfaceCount  0x52 u16 40         0x54 u32 ?  ...  0x60 u32 해시
 * 0x5C u16 가시 칸 수
 * 0x64 u32[11] 섹션 오프셋: [0] kd 트리  [1] 가시 레코드 u16  [2] 가시 컨텍스트 이름 0x18 B  [3] 표면(머티리얼) 레코드
 *                          [4] 비트필드 스키마("Ground","Water","Climbable"...)
 *                          [5] 리프 → 면 참조 u16[] (bit0 = 사각형, 번호 = v&gt;&gt;1)  [6] 삼각형 10B  [7] 사각형 12B
 *                          [8] 정점 vec3  [9] 끝  [10] 가시 칸 0x1C B   (실행 파일 로더 0x11D2B8 / 메시 0x16B2D0 의 오프셋 표와 일치)
 * kd 트리 레코드 8B: 노드 = u32 bit0=0, bit1-2 = 축(x,y,z), u16(+2) 먼 자식, f32(+4) 분할값 (가까운 자식 = 다음 레코드)
 *                   리프 = u32 (start&lt;&lt;1 | 1), u32 count → 면 참조[start .. start+count)
 * 삼각형 10B: u16 0, u16 surface(&amp;0xFFF), u16 v[3]
 * 사각형 12B: u16 0, u16 surface, u16 v[4]   — 로드 시 면마다 평면(n, -n·v0) 을 계산 (사각형은 대각선 외적)
 * 게임의 질의: 선분 캐스트(kd 트리 순회, 0x16BF98), 구 질의(추정 0x16C580 / 0x16DFD0)
 * 표면 레코드 [3]: 64B × surfaceCount(0x50) = char[24] 이름 + 데이터 40B(0x52) — 배치는 스키마 [4] (엔트리 0x4C B) 로 해석 ({@link SheetSurface})
 * </pre>
 */
public final class CollisionSheet {

	/** 면 (삼각형은 v[3] == v[2]). 삼각형이 앞, 사각형이 뒤 (면 참조 번호 순서와 같음). */
	public record Poly(int surface, int[] v) {
		public boolean triangle() {
			return v[3] == v[2];
		}
	}

	public final String name;
	public final float[] min = new float[3];
	public final float[] max = new float[3];
	public final float[][] vertices;
	public final List<Poly> polys = new ArrayList<>();
	public final int treeCount;
	public final int leafCount;
	public final int[] offsets = new int[11];
	/** 표면(머티리얼) 레코드 — 면의 surface 번호로 찾는다. */
	public final List<SheetSurface> surfaces = new ArrayList<>();

	public CollisionSheet(String name, ByteBuffer d) {
		this.name = name;
		for (int k = 0; k < 3; k++) {
			min[k] = d.getFloat(0x18 + k * 4);
			max[k] = d.getFloat(0x28 + k * 4);
		}
		treeCount = d.getShort(0x3C) & 0xFFFF;
		leafCount = d.getShort(0x3E) & 0xFFFF;
		int triCount = d.getShort(0x46) & 0xFFFF;
		int quadCount = d.getShort(0x48) & 0xFFFF;
		int vertCount = d.getShort(0x4A) & 0xFFFF;
		for (int i = 0; i < 11; i++) {
			offsets[i] = d.getInt(0x64 + i * 4);
		}
		vertices = new float[vertCount][3];
		for (int i = 0; i < vertCount; i++) {
			for (int k = 0; k < 3; k++) {
				vertices[i][k] = d.getFloat(offsets[8] + i * 12 + k * 4);
			}
		}
		for (int i = 0; i < triCount; i++) {
			int o = offsets[6] + i * 10;
			int[] v = new int[4];
			for (int k = 0; k < 3; k++) {
				v[k] = d.getShort(o + 4 + k * 2) & 0xFFFF;
			}
			v[3] = v[2];
			polys.add(new Poly(d.getShort(o + 2) & 0xFFF, v));
		}
		for (int i = 0; i < quadCount; i++) {
			int o = offsets[7] + i * 12;
			int[] v = new int[4];
			for (int k = 0; k < 4; k++) {
				v[k] = d.getShort(o + 4 + k * 2) & 0xFFFF;
			}
			polys.add(new Poly(d.getShort(o + 2) & 0xFFF, v));
		}
		parseSurfaces(d);
		parseVis(d);
	}

	/**
	 * 가시 칸 (43 문서 §11): 카메라 위치를 포함하는 AABB 칸의 레코드가 컨텍스트 (CXT_bv…) 를 켜거나 끈다.
	 * min/max 는 경계 포함, set/clear = 컨텍스트 번호 ({@link #visContexts}) — 레코드 형식 2 / 3.
	 */
	public record VisCell(float[] min, float[] max, int[] set, int[] clear) {
		public boolean contains(float x, float y, float z) {
			return min[0] <= x && x <= max[0] && min[1] <= y && y <= max[1] && min[2] <= z && z <= max[2];
		}
	}

	/** 가시 컨텍스트 이름 (섹션 [2], 0x18 B, 실행 파일 0x16D9C0 이 레지스트리에서 이 이름으로 찾는다). */
	public final List<String> visContexts = new ArrayList<>();
	/** 가시 칸 (0x5C 개, 섹션 [10] 0x1C B: min, max, u32 레코드 번호 → 섹션 [1] u16 단위). */
	public final List<VisCell> visCells = new ArrayList<>();

	/**
	 * 0x16B2D0 (+0x10 = [1], +0x14 = [2], +0x30 = [10], 이름 수 +0x42 = ([3] − [2]) / 0x18) · 0x16D240 레코드:
	 * u16 머리 (bit0 = 한 번만, bit1 = 이미 씀), u16 항목 수, 항목 = u16 형식, u16 n, 형식 0..3 → u16 × n (0/1 = 비트맵 켬/끔 — 전 WAD 비어 있음,
	 * 2/3 = 컨텍스트 켬/끔), 4 → 0x1A × n, 5..7 → 0x18 × n (0x16D1E8 건너뛰기).
	 */
	private void parseVis(ByteBuffer d) {
		int nctx = Math.max(0, (offsets[3] - offsets[2]) / 0x18);
		for (int i = 0; i < nctx; i++) {
			visContexts.add(cstr(d, offsets[2] + i * 0x18, 0x18));
		}
		int cells = d.getShort(0x5C) & 0xFFFF;
		for (int c = 0; c < cells; c++) {
			int o = offsets[10] + c * 0x1C;
			if (o + 0x1C > d.limit()) {
				break;
			}
			float[] mn = {d.getFloat(o), d.getFloat(o + 4), d.getFloat(o + 8)};
			float[] mx = {d.getFloat(o + 12), d.getFloat(o + 16), d.getFloat(o + 20)};
			int p = offsets[1] + d.getInt(o + 0x18) * 2;
			int n = d.getShort(p + 2) & 0xFFFF;
			p += 4;
			List<Integer> set = new ArrayList<>(), clear = new ArrayList<>();
			for (int e = 0; e < n && p + 4 <= d.limit(); e++) {
				int type = d.getShort(p) & 0xFFFF, cnt = d.getShort(p + 2) & 0xFFFF;
				p += 4;
				if (type <= 3) {
					for (int k = 0; k < cnt; k++) {
						int v = d.getShort(p + k * 2) & 0xFFFF;
						if (type == 2) {
							set.add(v);
						} else if (type == 3) {
							clear.add(v);
						}
					}
					p += cnt * 2;
				} else {
					p += cnt * (type == 4 ? 0x1A : 0x18);
				}
			}
			visCells.add(new VisCell(mn, mx, set.stream().mapToInt(Integer::intValue).toArray(), clear.stream().mapToInt(Integer::intValue).toArray()));
		}
	}

	/**
	 * 0x16DC60: 점을 포함하는 칸마다 두 번 — 먼저 형식 3 (끔), 다음 형식 0/1/2 (켬). 마스크는 매 프레임 0 에서 시작 (0x16D6F0) 하므로
	 * 뷰 하나면 켬만 남는다. bit i = {@link #visContexts} i 가 보임.
	 */
	public long visibleContextMask(float x, float y, float z, long mask) {
		for (VisCell c : visCells) {
			if (c.contains(x, y, z)) {
				for (int v : c.clear()) {
					mask &= ~(1L << v);
				}
			}
		}
		for (VisCell c : visCells) {
			if (c.contains(x, y, z)) {
				for (int v : c.set()) {
					mask |= 1L << v;
				}
			}
		}
		return mask;
	}

	/** 0x16DE18 (안개 GO 의 컨텍스트 판정): 점을 포함하는 칸 중 하나가 형식 2 로 그 컨텍스트를 켜는지. */
	public boolean cellListsContext(float x, float y, float z, String context) {
		int idx = -1;
		for (int i = 0; i < visContexts.size(); i++) {
			if (visContexts.get(i).equalsIgnoreCase(context)) {
				idx = i;
			}
		}
		if (idx < 0) {
			return false;
		}
		for (VisCell c : visCells) {
			if (c.contains(x, y, z)) {
				for (int v : c.set()) {
					if (v == idx) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/** 스키마 엔트리 하나: 비트 속성이면 (바이트 오프셋, 레코드 기준 비트 번호, 폭). */
	private record Field(String name, int type, int byteOffset, int bit, int width) {
	}

	private void parseSurfaces(ByteBuffer d) {
		int count = d.getShort(0x50) & 0xFFFF;
		int dataSize = d.getShort(0x52) & 0xFFFF;
		int recSize = 24 + dataSize;
		List<Field> schema = new ArrayList<>();
		for (int o = offsets[4]; o + 0x4C <= offsets[5] && o + 0x4C <= d.limit(); o += 0x4C) {
			schema.add(new Field(cstr(d, o, 32), d.getInt(o + 0x20), d.getInt(o + 0x3C), d.getInt(o + 0x44), d.getInt(o + 0x48)));
		}
		for (int i = 0; i < count; i++) {
			int o = offsets[3] + i * recSize;
			if (dataSize < 40 || o + recSize > d.limit()) {
				break;
			}
			int data = o + 24;
			long attrs = 0;
			for (Field f : schema) {
				SurfaceAttr a = SurfaceAttr.bySchemaName(f.name);
				if (a == null || f.type != 2 || f.byteOffset + 4 > dataSize) {
					continue;
				}
				int word = d.getInt(data + f.byteOffset);
				boolean on = f.width == 0 ? word != 0 : (word >>> (f.bit - f.byteOffset * 8) & 1) != 0;
				if (on) {
					attrs |= a.bit();
				}
			}
			surfaces.add(new SheetSurface(cstr(d, o, 24), attrs, d.getInt(data + 0x14), d.getInt(data + 0x18), d.getFloat(data + 0x1C),
					d.getInt(data + 0x20), d.getInt(data + 0x24)));
		}
	}

	private static String cstr(ByteBuffer d, int at, int max) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < max && at + i < d.limit(); i++) {
			int c = d.get(at + i) & 0xFF;
			if (c == 0) {
				break;
			}
			sb.append((char) c);
		}
		return sb.toString();
	}

	/** surface 번호의 표면 (범위 밖이면 null). */
	public SheetSurface surface(int index) {
		return index >= 0 && index < surfaces.size() ? surfaces.get(index) : null;
	}

	public static boolean is(ByteBuffer d) {
		return d.limit() > 0x90 && d.getInt(0) == 0x11 && d.get(4) == 'S' && d.get(5) == 'h' && d.get(9) == 'H';
	}

	/** 삼각형 목록 (사각형은 v0 v1 v2 + v0 v2 v3 로 분할). 원소 = {a, b, c, surface}. */
	public List<int[]> triangles() {
		List<int[]> t = new ArrayList<>();
		for (Poly p : polys) {
			int[] v = p.v;
			t.add(new int[]{v[0], v[1], v[2], p.surface});
			if (!p.triangle()) {
				t.add(new int[]{v[0], v[2], v[3], p.surface});
			}
		}
		return t;
	}
}
