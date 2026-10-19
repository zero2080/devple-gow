package devple.gow.tools.model;

import java.util.ArrayList;
import java.util.List;

/**
 * VIF 배치 하나를 정점 배열로 해석한 결과.
 * UNPACK 포맷으로 속성을 구분한다 (GoW 메시에서 관찰된 고정 규칙):
 * <pre>
 * V4-16  위치 x,y,z (+STROW 오프셋) / w = bit15 ADC(킥 안 함) | 하위 15비트 스키닝 가중치(4096=1.0)
 * V2-16  UV, 4096 = 1.0 (VU ITOF12)
 * V2-32  UV, float 비트
 * V3-8   법선, 127 = 1.0
 * V4-8   정점 색 RGBA (부호 없음, 0x80 = 1.0)
 * V4-32  GIF 태그 / 바운딩 구(VU 주소 0xF4) 등 파라미터 쿼드워드
 * </pre>
 * 다층(멀티패스) 오브젝트는 배치마다 "지오메트리 REF + 레이어 REF(UV, 색)" 가 같은 VU 주소에 덮어쓰므로
 * 같은 주소의 나중 UNPACK 을 사용한다.
 */
public final class VertexBatch {

	/** 위치 정수 → 월드 단위. VU 가 ITOF4(÷16) 로 변환한다고 가정 (스켈레톤 본 위치와 일치). */
	public static final float POS_SCALE = 1f / 16f;

	public final int count;
	public final int[][] pos;
	public final float[][] uv;
	public final float[][] normal;
	public final int[][] color;
	/** 위치 UNPACK 에 더해진 STROW ÷ 16 (x, y, z). 없으면 0. 레벨 배치 시 빼야 한다 (12 문서 §2). */
	public final float[] posRow = new float[3];
	/** V4-32 쿼드워드들 (GIF 태그 등). VU 주소와 함께. */
	public final List<int[]> qwords = new ArrayList<>();
	public final List<Integer> qwordAddr = new ArrayList<>();
	/** 속성 개수 불일치 등 경고. */
	public final List<String> warnings = new ArrayList<>();

	public VertexBatch(VifParser.Batch b) {
		VifUnpack p = null;
		VifUnpack t = null;
		VifUnpack n = null;
		VifUnpack c = null;
		for (VifUnpack u : b.unpacks()) {
			if (u.is(3, VifUnpack.VL_16) || u.is(2, VifUnpack.VL_16) || u.is(2, VifUnpack.VL_32)) {
				p = pick(p, u, "위치");
			} else if (u.is(1, VifUnpack.VL_16) || u.is(1, VifUnpack.VL_32)) {
				t = pick(t, u, "UV");
			} else if (u.is(2, VifUnpack.VL_8)) {
				n = pick(n, u, "법선");
			} else if (u.is(3, VifUnpack.VL_8)) {
				c = pick(c, u, "색");
			} else if (u.is(3, VifUnpack.VL_32)) {
				for (int i = 0; i < u.count(); i++) {
					qwords.add(new int[]{u.get(i, 0), u.get(i, 1), u.get(i, 2), u.get(i, 3)});
					qwordAddr.add(u.addr() + i);
				}
			} else {
				warnings.add("미처리 UNPACK " + u.format());
			}
		}
		count = p == null ? 0 : p.count();
		if (p != null && p.row() != null && p.vl() != VifUnpack.VL_32) {
			for (int k = 0; k < 3; k++) {
				posRow[k] = p.row()[k] * POS_SCALE;
			}
		}
		pos = new int[count][4];
		uv = t == null ? null : new float[count][2];
		normal = n == null ? null : new float[count][3];
		color = c == null ? null : new int[count][4];
		for (int i = 0; i < count; i++) {
			for (int k = 0; k < p.comps(); k++) {
				pos[i][k] = p.get(i, k);
			}
			if (uv != null && i < t.count()) {
				if (t.vl() == VifUnpack.VL_32) {
					uv[i][0] = t.getFloat(i, 0);
					uv[i][1] = t.getFloat(i, 1);
				} else {
					uv[i][0] = t.get(i, 0) / 4096f;
					uv[i][1] = t.get(i, 1) / 4096f;
				}
			}
			if (normal != null && i < n.count()) {
				for (int k = 0; k < 3; k++) {
					normal[i][k] = n.get(i, k) / 127f;
				}
			}
			if (color != null && i < c.count()) {
				for (int k = 0; k < 4; k++) {
					color[i][k] = c.get(i, k) & 0xFF;
				}
			}
		}
		checkCount(t, "UV");
		checkCount(n, "법선");
		checkCount(c, "색");
	}

	/** 같은 VU 주소에 다시 쓰면 나중 것이 이긴다 (VU 메모리 덮어쓰기). 주소가 다르면 첫 번째 유지 + 경고. */
	private VifUnpack pick(VifUnpack old, VifUnpack u, String what) {
		if (old != null && old.addr() == u.addr()) {
			return u;
		}
		if (old != null) {
			warnings.add(what + " UNPACK 중복");
			return old;
		}
		return u;
	}

	private void checkCount(VifUnpack u, String what) {
		if (u != null && u.count() != count) {
			warnings.add(what + " 개수 " + u.count() + " != 위치 " + count);
		}
	}

	/** 위치 w 의 bit15 = GS XYZ3 ADC (삼각형을 그리지 않고 스트립 창에만 넣음). */
	public boolean adc(int i) {
		return (pos[i][3] & 0x8000) != 0;
	}

	/** 스키닝 가중치 (w 하위 15비트, 4096 = 1.0). 정적 메시는 0. */
	public int weight(int i) {
		return pos[i][3] & 0x7FFF;
	}

	public float x(int i) {
		return pos[i][0] * POS_SCALE;
	}

	public float y(int i) {
		return pos[i][1] * POS_SCALE;
	}

	public float z(int i) {
		return pos[i][2] * POS_SCALE;
	}

	/**
	 * 트라이앵글 스트립 → 삼각형 인덱스 (배치 내부 인덱스, 3개씩).
	 * ADC 가 꺼진 정점마다 직전 두 정점과 삼각형을 만든다. 감김 방향은 스트립 시작(연속 ADC 2개 이상) 기준 짝/홀로 교대.
	 * 단, 실제 데이터는 스트립마다 감김이 제각각이다(GS 는 컬링하지 않음). 법선이 있으면 {@link TriMesh} 가 법선 기준으로 다시 맞춘다.
	 */
	public int[] triangles() {
		int[] out = new int[Math.max(0, count - 2) * 3];
		int n = 0;
		int start = 0;
		for (int i = 0; i < count; i++) {
			if (adc(i)) {
				if (i + 1 < count && adc(i + 1)) {
					start = i;
				}
				continue;
			}
			if (i < 2) {
				continue;
			}
			int a = i - 2;
			int b = i - 1;
			if (((i - start) & 1) == 1) {
				int tmp = a;
				a = b;
				b = tmp;
			}
			out[n++] = a;
			out[n++] = b;
			out[n++] = i;
		}
		return java.util.Arrays.copyOf(out, n);
	}
}
