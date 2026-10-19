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
 * 0x64 u32[11] 섹션 오프셋: [0] kd 트리  [1..3] 표면(머티리얼) 레코드  [4] 비트필드 스키마("Ground","Water","Climbable"...)
 *                          [5] 리프 → 면 참조 u16[] (bit0 = 사각형, 번호 = v&gt;&gt;1)  [6] 삼각형 10B  [7] 사각형 12B
 *                          [8] 정점 vec3  [9] 끝   (실행 파일 로더 0x11D2B8 / 메시 0x16B2D0 의 오프셋 표와 일치)
 * kd 트리 레코드 8B: 노드 = u32 bit0=0, bit1-2 = 축(x,y,z), u16(+2) 먼 자식, f32(+4) 분할값 (가까운 자식 = 다음 레코드)
 *                   리프 = u32 (start&lt;&lt;1 | 1), u32 count → 면 참조[start .. start+count)
 * 삼각형 10B: u16 0, u16 surface(&amp;0xFFF), u16 v[3]
 * 사각형 12B: u16 0, u16 surface, u16 v[4]   — 로드 시 면마다 평면(n, -n·v0) 을 계산 (사각형은 대각선 외적)
 * 게임의 질의: 선분 캐스트(kd 트리 순회, 0x16BF98), 구 질의(추정 0x16C580 / 0x16DFD0)
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
