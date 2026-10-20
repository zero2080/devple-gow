package devple.gow.engine.light;

import devple.gow.tools.wad.WadFile;
import devple.gow.tools.wad.WadTag;
import devple.gow.tools.wad.WadTagType;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * 그림자 지오메트리 SHG_ (매직 0x27, renShadowServer 타입 0x27, 47 문서 §7) [확실: 디스어셈블 0x162338 / 0x162FB8].
 * <pre>
 * 헤더  +0x00 u32 0x27, +0x04 "ShdowHdr07", +0x10 u32 조각 수, +0x14 u32 조각 오프셋 표 (데이터 기준)
 * 조각  +0x00 vec4  구 (중심 xyz, 반경 w — 관절 공간)
 *       +0x10 u8    모델 번호 (GO 의 모델 컴포넌트 목록 0x1628E0), +0x11 u8 관절 (0x16356C: 관절 가시성 비트 +0x64 검사, 관절 행렬 × 16)
 *       +0x12 u16   정점 수, +0x16 u16 모서리 수, +0x18/+0x1A u16 면 모서리 참조 수, +0x1C u16 면 수
 *       +0x1E u16   정점 (vec4, w = 1) 오프셋 (조각 기준)
 *       +0x20 u16   면 법선 (vec4, w = 0)
 *       +0x22 u16   모서리 (u16 v0, u16 v1)
 *       +0x24 u16   면 꼭짓점 (u16, 띠 순서 — 덮개 그리기)
 *       +0x26 u16   면 모서리 참조 (u16 모서리, u16 방향: 0 = v0→v1, 1 = v1→v0; 면 고리는 법선 기준 반시계)
 *       +0x28 u16   면 (u16 개수, u16 시작) — 꼭짓점·모서리 참조 둘 다 같은 범위
 * </pre>
 * 조각 하나는 관절 하나에 붙은 닫힌 껍질 (영웅 19 개, 블레이드 1 개, 적 …).
 */
public final class ShadowGeometry {

	public static final int MAGIC = 0x27;

	/** 조각 (관절 하나에 붙은 닫힌 껍질). faces[i] = {개수, 시작}, edges[i] = {v0, v1}, refs[i] = {모서리, 방향}. */
	public record Piece(float[] sphere, int model, int joint, float[][] verts, float[][] normals, int[][] edges, int[] corners,
						int[][] refs, int[][] faces) {
	}

	public final String name;
	public final List<Piece> pieces;

	private ShadowGeometry(String name, List<Piece> pieces) {
		this.name = name;
		this.pieces = pieces;
	}

	public static ShadowGeometry parse(String name, ByteBuffer src) {
		ByteBuffer d = src.duplicate().order(ByteOrder.LITTLE_ENDIAN);
		int base = d.position();
		if ((d.getInt(base) & 0xFFFF) != MAGIC) {
			throw new IllegalArgumentException("SHG 매직 아님: " + name);
		}
		int n = d.getInt(base + 0x10), table = d.getInt(base + 0x14);
		List<Piece> out = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			int p = base + d.getInt(base + table + 4 * i);
			float[] sph = {d.getFloat(p), d.getFloat(p + 4), d.getFloat(p + 8), d.getFloat(p + 12)};
			int model = d.get(p + 0x10) & 0xFF, joint = d.get(p + 0x11) & 0xFF;
			int nv = u16(d, p + 0x12), ne = u16(d, p + 0x16), nf = u16(d, p + 0x1C);
			int ov = p + u16(d, p + 0x1E), on = p + u16(d, p + 0x20), oe = p + u16(d, p + 0x22);
			int oc = p + u16(d, p + 0x24), or = p + u16(d, p + 0x26), of = p + u16(d, p + 0x28);
			float[][] v = new float[nv][];
			for (int k = 0; k < nv; k++) {
				v[k] = new float[]{d.getFloat(ov + 16 * k), d.getFloat(ov + 16 * k + 4), d.getFloat(ov + 16 * k + 8)};
			}
			float[][] nm = new float[nf][];
			for (int k = 0; k < nf; k++) {
				nm[k] = new float[]{d.getFloat(on + 16 * k), d.getFloat(on + 16 * k + 4), d.getFloat(on + 16 * k + 8)};
			}
			int[][] e = new int[ne][];
			for (int k = 0; k < ne; k++) {
				e[k] = new int[]{u16(d, oe + 4 * k), u16(d, oe + 4 * k + 2)};
			}
			int[][] f = new int[nf][];
			int corners = 0;
			for (int k = 0; k < nf; k++) {
				f[k] = new int[]{u16(d, of + 4 * k), u16(d, of + 4 * k + 2)};
				corners = Math.max(corners, f[k][0] + f[k][1]);
			}
			int[] c = new int[corners];
			int[][] r = new int[corners][];
			for (int k = 0; k < corners; k++) {
				c[k] = u16(d, oc + 2 * k);
				r[k] = new int[]{u16(d, or + 4 * k), u16(d, or + 4 * k + 2)};
			}
			out.add(new Piece(sph, model, joint, v, nm, e, c, r, f));
		}
		return new ShadowGeometry(name, out);
	}

	private static int u16(ByteBuffer d, int o) {
		return d.getShort(o) & 0xFFFF;
	}

	/** WAD 의 모든 SHG_ (리소스 태그, 매직 0x27, 서버 선언 0x80000027 제외). */
	public static List<ShadowGeometry> load(WadFile wad) {
		List<ShadowGeometry> out = new ArrayList<>();
		for (WadTag t : wad.tags()) {
			if (t.tag() == WadTagType.RESOURCE && t.hasData() && t.size() > 0x20 && t.magic() == MAGIC) {
				out.add(parse(t.name(), t.data()));
			}
		}
		return out;
	}

	/** 모델 MDL_x 의 그림자 SHG_x (대소문자 무시, 없으면 null) — 원작은 같은 GO 그룹에 들어 있다. */
	public static ShadowGeometry forModel(WadFile wad, String modelName) {
		String key = "SHG_" + (modelName.regionMatches(true, 0, "MDL_", 0, 4) ? modelName.substring(4) : modelName);
		for (WadTag t : wad.tags()) {
			if (t.tag() == WadTagType.RESOURCE && t.hasData() && t.size() > 0x20 && t.magic() == MAGIC && t.name().equalsIgnoreCase(key)) {
				return parse(t.name(), t.data());
			}
		}
		return null;
	}
}
