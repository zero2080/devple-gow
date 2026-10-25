package devple.gow.tools.level;

import devple.gow.tools.wad.WadFile;
import devple.gow.tools.wad.WadTag;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 웨이포인트 그래프 — WAD 자원 형 0x14 "WYP_&lt;이름&gt;" (원작 클라이언트 0x187BC8 이 "CLI_WYP_%s" 로 등록, 0x187E60 이 이름으로 찾음).
 * 적의 순찰 경로 (SpawnEnemy 슬롯 9 "patrolN" → "Patrol%d", 0x1D17B8) 와 이동 자리 그래프 (슬롯 5 → "Site%d", 0x1A4E88) 가 같은 꼴이다 [확실: 디스어셈블].
 * <pre>
 * 자료 +0 u32 형 (0x14), +4 머리 ("WypHeadr") — 표 위치는 머리 기준 오프셋 (0x187C18 – 0x187C98: 머리 + 값):
 *   머리 +0x08 u32 국소 (≠ 0 이면 노드 = 소유 GO 기준, 0x187D30 이 GO 관절 행렬을 곱함 — ARENA14 의 한 노드 Site 8 개뿐)
 *   머리 +0x0C 노드 표   +0x14 간선 표   +0x1C 속성 자료   +0x20 속성 표
 *   머리 +0x2C 노드 수   +0x40 속성 수   +0x4C 노드당 속성 자료 크기
 * 노드 24 바이트: +0 x, +4 y, +8 z (f32), +0x0C s16 나가는 간선 수, +0x10 s16 들어오는 간선 수, +0x12 s16 나가는 간선 첫 칸, +0x16 s16 들어오는
 *   간선 첫 칸 (0x1D0348 이 0x23D790 / 0x23D7A8 로 읽음)
 * 간선 4 바이트: +0 u16 이웃 노드 번호 (0x1D05AC)
 * 속성 0x4C 바이트: +0 이름 (예 "Radius"), +0x3C 노드 자료 안 위치 (0x1D18E0 – 0x1D1958 이 이름을 strcmp 로 찾음)
 * </pre>
 * 순찰 경로는 한 방향 고리다 (예 DEST03 Patrol1030: 노드 50 · 나가는 간선 하나씩 0→1→3→…→48→47→…→2→49→0), 자리 그래프는 양방향 (낮은 번호 쪽의 나가는 간선 +
 * 높은 번호 쪽의 들어오는 간선).
 */
public final class WaypointGraph {

	/** 자원 이름에서 "WYP_" 를 뺀 것 (대소문자 그대로, 예 "Patrol1030"). */
	public final String name;
	/** 머리 +0x08: 노드 좌표가 소유 GO 기준 (0x187D30). */
	public final boolean local;
	private final float[] xyz;
	private final int[][] out, in;
	private final Map<String, float[]> attrs;

	private WaypointGraph(String name, boolean local, float[] xyz, int[][] out, int[][] in, Map<String, float[]> attrs) {
		this.name = name;
		this.local = local;
		this.xyz = xyz;
		this.out = out;
		this.in = in;
		this.attrs = attrs;
	}

	/** 노드 수 (머리 +0x2C). */
	public int size() {
		return out.length;
	}

	public float x(int n) {
		return xyz[3 * n];
	}

	public float y(int n) {
		return xyz[3 * n + 1];
	}

	public float z(int n) {
		return xyz[3 * n + 2];
	}

	/** 나가는 간선의 이웃들 (노드 +0x0C 개, +0x12 부터). */
	public int[] out(int n) {
		return out[n].clone();
	}

	/** 들어오는 간선의 이웃들 (노드 +0x10 개, +0x16 부터). */
	public int[] in(int n) {
		return in[n].clone();
	}

	/** 노드 속성 값 (없으면 def). 순찰 도착 반경 = "Radius" × 16 (0x1D0504 – 0x1D0518). */
	public float attr(String attr, int n, float def) {
		float[] v = attrs.get(attr);
		return v != null && n >= 0 && n < v.length ? v[n] : def;
	}

	/** "Radius" 속성 (m). 순찰 노드 도착 거리 · 첫 노드 흩뿌림 반경 = 이것 × 16 (0x1D0518 / 0x1D196C). */
	public float radius(int n) {
		return attr("Radius", n, 0f);
	}

	public java.util.Set<String> attributes() {
		return Collections.unmodifiableSet(attrs.keySet());
	}

	/** 점에서 가장 가까운 노드 (3 차원 거리, 0x1D1854 – 0x1D18CC). 노드가 없으면 −1. */
	public int nearest(float px, float py, float pz) {
		int best = -1;
		float bd = Float.MAX_VALUE;
		for (int i = 0; i < size(); i++) {
			float dx = x(i) - px, dy = y(i) - py, dz = z(i) - pz;
			float d = dx * dx + dy * dy + dz * dz;
			if (d < bd) {
				bd = d;
				best = i;
			}
		}
		return best;
	}

	/**
	 * 자원 자료 (형 u32 부터) 를 읽는다.
	 *
	 * @throws IllegalArgumentException 머리가 "WypHeadr" 가 아니거나 표가 자료 밖
	 */
	public static WaypointGraph parse(String name, ByteBuffer src) {
		ByteBuffer d = src.duplicate().order(ByteOrder.LITTLE_ENDIAN);
		final int h = 4;
		if (d.limit() < h + 0x50 || d.get(h) != 'W' || d.get(h + 1) != 'y' || d.get(h + 2) != 'p') {
			throw new IllegalArgumentException("WYP 머리 아님: " + name);
		}
		boolean local = d.getInt(h + 0x08) != 0;
		int nodeOff = h + d.getInt(h + 0x0C), edgeOff = h + d.getInt(h + 0x14);
		int attrData = h + d.getInt(h + 0x1C), attrTab = h + d.getInt(h + 0x20);
		int n = d.getInt(h + 0x2C), nAttr = d.getInt(h + 0x40), stride = d.getInt(h + 0x4C);
		if (n < 0 || nodeOff + 24L * n > d.limit()) {
			throw new IllegalArgumentException("WYP 노드 표가 자료 밖: " + name);
		}
		float[] xyz = new float[3 * n];
		int[][] out = new int[n][], in = new int[n][];
		for (int i = 0; i < n; i++) {
			int r = nodeOff + 24 * i;
			xyz[3 * i] = d.getFloat(r);
			xyz[3 * i + 1] = d.getFloat(r + 4);
			xyz[3 * i + 2] = d.getFloat(r + 8);
			out[i] = edges(d, edgeOff, d.getShort(r + 0x12), d.getShort(r + 0x0C), n);
			in[i] = edges(d, edgeOff, d.getShort(r + 0x16), d.getShort(r + 0x10), n);
		}
		Map<String, float[]> attrs = new LinkedHashMap<>();
		for (int a = 0; a < nAttr; a++) {
			int r = attrTab + 0x4C * a;
			if (r + 0x40 > d.limit()) {
				break;
			}
			StringBuilder sb = new StringBuilder();
			for (int k = 0; k < 0x3C && d.get(r + k) != 0; k++) {
				sb.append((char) (d.get(r + k) & 0xFF));
			}
			int at = d.getInt(r + 0x3C);
			float[] v = new float[n];
			for (int i = 0; i < n; i++) {
				int o = attrData + stride * i + at;
				v[i] = o >= 0 && o + 4 <= d.limit() ? d.getFloat(o) : 0f;
			}
			attrs.put(sb.toString(), v);
		}
		return new WaypointGraph(name, local, xyz, out, in, attrs);
	}

	private static int[] edges(ByteBuffer d, int edgeOff, int first, int count, int n) {
		if (count <= 0) {
			return new int[0];
		}
		int[] e = new int[count];
		int k = 0;
		for (int i = 0; i < count; i++) {
			int o = edgeOff + 4 * (first + i);
			if (o >= 0 && o + 2 <= d.limit()) {
				int v = d.getShort(o) & 0xFFFF;
				if (v < n) {
					e[k++] = v;
				}
			}
		}
		return k == count ? e : java.util.Arrays.copyOf(e, k);
	}

	/** WAD 의 모든 웨이포인트 그래프: 키 = "WYP_" 를 뺀 이름의 소문자 (원작 레지스트리는 이름을 대문자로 해시 — 대소문자 무시). */
	public static Map<String, WaypointGraph> all(WadFile wad) {
		Map<String, WaypointGraph> m = new LinkedHashMap<>();
		for (WadTag t : wad.tags()) {
			if (!t.hasData() || t.name() == null || t.magic() != 0x14 || !t.name().regionMatches(true, 0, "WYP_", 0, 4)) {
				continue;
			}
			try {
				WaypointGraph g = parse(t.name().substring(4), t.data());
				m.putIfAbsent(g.name.toLowerCase(Locale.ROOT), g);
			} catch (RuntimeException ex) {
				// 형이 다른 자원은 건너뜀
			}
		}
		return m;
	}

	/**
	 * SpawnEnemy 슬롯 9 순찰 문자열 → 순찰 번호: 원작 0x1AC9B8 – 0x1AC9E8 은 앞 6 글자를 "patrol" 과 대소문자 무시로 견주어 (0x28DFD0) 같으면 그 뒤를
	 * 10 진수로 (0x291D58) 생성 기록 +0xA 에, 아니면 −1 [확실]. 예 "Patrol1030" → 1030, "patrol3" → 3, "PATROL1" → 1.
	 */
	public static int patrolNumber(String slot) {
		if (slot == null || slot.length() < 6 || !slot.regionMatches(true, 0, "patrol", 0, 6)) {
			return -1;
		}
		int i = 6, v = 0;
		boolean any = false;
		while (i < slot.length() && Character.isDigit(slot.charAt(i))) {
			v = v * 10 + (slot.charAt(i) - '0');
			i++;
			any = true;
		}
		return any ? v & 0xFFFF : 0;
	}
}
