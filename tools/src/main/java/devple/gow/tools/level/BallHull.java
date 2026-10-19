package devple.gow.tools.level;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 볼록 볼륨 "BallHull" (COL 0x11). 소유 오브젝트 관절 로컬 좌표.
 * {@code CDV_} 동적 충돌(kind 0), {@code CMV_} 카메라 볼륨(1), {@code ENV_} 환경 트리거(2), {@code SEV_} 사운드 영역(3).
 * <pre>
 * 0x00 u32 0x11   0x04 u32 kind   0x08 "BallHull"   0x10 u32 크기   0x14 u32 ballCount   0x18 u32 hullCount
 * 0x1C vec4 경계구 (x, y, z, r)   0x2C u32 ?   0x30 u32 ? (CDV 0x1F)   0x34 u32 nameCount   0x38 u32 ? (평면 수가 아님: ENV_LoadCheckB 는 0 인데 평면 6개)
 * 0x3C u32[11] 오프셋: [0] 재질 엔트리[nameCount] (0x38 = 엔트리 크기, CDV 0x40): char 이름[..], +0x1C u32 ?, +0x2C u32 ?(MFX 추정),
 *                         +0x30 u32 PartType (exe 프로퍼티 "PartType" 0x1BED34 — 공격 판정 부위 번호, 10 문서 §5.4)
 *                     [1] u8 ball별 관절 번호 (0x13AAA8: 소유 오브젝트 관절 행렬로 포즈)  [2] u8 ball별 ?(접촉 +0x38)
 *                     [3] u8 ball별 재질 엔트리 번호  [4] vec4 ball (x, y, z, r)
 *                     [5] u8 hull별 평면 수  [6][7] 미상  [8] vec4 hull 중심
 *                     [9] u8 평면별 이름 번호(CMV: 활성화할 카메라 RCM_x)  [10] vec4 평면 (n, d)
 * 내부 판정: ball 안 (|p - c| &lt;= r) 이거나, hull 의 모든 평면에 대해 n·p + d &lt;= 0
 *   (n 은 관절 스케일이 반영된 비정규 벡터일 수 있음 — 부호 판정이므로 로컬 좌표에서 그대로 쓴다)
 * </pre>
 */
public final class BallHull {

	public record Hull(float[] center, List<float[]> planes, int nameIndex) {
	}

	public final String name;
	public final int kind;
	public final float[] sphere = new float[4];
	public final List<String> names = new ArrayList<>();
	public final List<Hull> hulls = new ArrayList<>();
	/** 구 볼륨 (x, y, z, r). */
	public final List<float[]> balls = new ArrayList<>();
	/** ball 별 관절 번호 (off[1]) — 구 중심은 이 관절 로컬 좌표. */
	public final int[] ballJoint;
	/** ball 별 재질 엔트리 번호 (off[3]). */
	public final int[] ballMaterial;
	/** ball / hull 별 영역 번호 (off[2] / off[7] u8). ENV 에서는 진입·이탈 센서 엔티티의 영역 ID(원본 0x4A)와 짝 (13 문서 §1, 36 문서 §2). */
	public final int[] ballRegion, hullRegion;
	/** 재질 엔트리별 PartType (+0x30, 엔트리 크기 0x34 미만이면 0). 히어로 몸 1, 오른발 7, extra1 9 / 블레이드 2, 쌍 11. */
	public final int[] materialPart;

	public BallHull(String name, ByteBuffer d) {
		this.name = name;
		kind = d.getInt(4);
		int ballCount = d.getInt(0x14);
		int hullCount = d.getInt(0x18);
		for (int k = 0; k < 4; k++) {
			sphere[k] = d.getFloat(0x1C + k * 4);
		}
		int nameCount = d.getInt(0x34);
		int nameStride = d.getInt(0x38) > 0 ? d.getInt(0x38) : 24;
		int[] off = new int[11];
		for (int i = 0; i < 11; i++) {
			off[i] = d.getInt(0x3C + i * 4);
		}
		for (int i = 0; i < nameCount; i++) {
			byte[] b = new byte[24];
			d.get(off[0] + i * nameStride, b);
			int len = 0;
			while (len < 24 && b[len] != 0) {
				len++;
			}
			names.add(new String(b, 0, len, StandardCharsets.ISO_8859_1));
		}
		materialPart = new int[nameCount];
		for (int i = 0; i < nameCount; i++) {
			if (nameStride >= 0x34 && off[0] + i * nameStride + 0x34 <= d.limit()) {
				materialPart[i] = d.getInt(off[0] + i * nameStride + 0x30);
			}
		}
		ballJoint = new int[ballCount];
		ballMaterial = new int[ballCount];
		ballRegion = new int[ballCount];
		hullRegion = new int[hullCount];
		for (int b = 0; b < ballCount; b++) {
			ballRegion[b] = off[2] + b < d.limit() ? d.get(off[2] + b) & 0xFF : 0;
		}
		for (int h = 0; h < hullCount; h++) {
			hullRegion[h] = off[7] + h < d.limit() ? d.get(off[7] + h) & 0xFF : 0;
		}
		for (int b = 0; b < ballCount; b++) {
			if (off[1] + b < d.limit()) {
				ballJoint[b] = d.get(off[1] + b) & 0xFF;
			}
			if (off[3] + b < d.limit()) {
				ballMaterial[b] = d.get(off[3] + b) & 0xFF;
			}
		}
		for (int b = 0; b < ballCount; b++) {
			int o = off[4] + b * 16;
			balls.add(new float[]{d.getFloat(o), d.getFloat(o + 4), d.getFloat(o + 8), d.getFloat(o + 12)});
		}
		int plane = 0;
		for (int h = 0; h < hullCount; h++) {
			int n = d.get(off[5] + h) & 0xFF;
			float[] c = {d.getFloat(off[8] + h * 16), d.getFloat(off[8] + h * 16 + 4), d.getFloat(off[8] + h * 16 + 8)};
			List<float[]> ps = new ArrayList<>();
			// 평면 수 = hull 별 평면 수의 합 (0x38 은 평면 수가 아니다)
			int nameIndex = off[9] + plane < d.limit() ? d.get(off[9] + plane) & 0xFF : 0;
			for (int k = 0; k < n && off[10] + plane * 16 + 16 <= d.limit(); k++, plane++) {
				int o = off[10] + plane * 16;
				ps.add(new float[]{d.getFloat(o), d.getFloat(o + 4), d.getFloat(o + 8), d.getFloat(o + 12)});
			}
			hulls.add(new Hull(c, ps, nameIndex));
		}
	}

	/** ball b 의 PartType (재질 엔트리 +0x30). */
	public int ballPart(int b) {
		int m = ballMaterial[b];
		return m < materialPart.length ? materialPart[m] : 0;
	}

	public static boolean is(ByteBuffer d) {
		return d.limit() > 0x68 && d.getInt(0) == 0x11 && d.get(8) == 'B' && d.get(12) == 'H';
	}

	/** 로컬 좌표 p 가 볼륨 안인지 (ball 또는 hull). */
	public boolean contains(float x, float y, float z) {
		for (float[] b : balls) {
			float dx = x - b[0], dy = y - b[1], dz = z - b[2];
			if (dx * dx + dy * dy + dz * dz <= b[3] * b[3]) {
				return true;
			}
		}
		return find(x, y, z) >= 0;
	}

	/** 로컬 좌표 p 를 포함하는 ball/hull 의 영역 번호들 (밖이면 빈 집합). */
	public java.util.Set<Integer> regionsAt(float x, float y, float z) {
		java.util.Set<Integer> out = new java.util.TreeSet<>();
		for (int i = 0; i < balls.size(); i++) {
			float[] b = balls.get(i);
			float dx = x - b[0], dy = y - b[1], dz = z - b[2];
			if (dx * dx + dy * dy + dz * dz <= b[3] * b[3]) {
				out.add(ballRegion[i]);
			}
		}
		for (int h = 0; h < hulls.size(); h++) {
			if (insideHull(h, x, y, z)) {
				out.add(hullRegion[h]);
			}
		}
		return out;
	}

	/** 영역 번호가 이 볼륨에 있는지. */
	public boolean hasRegion(int region) {
		for (int r : ballRegion) {
			if (r == region) {
				return true;
			}
		}
		for (int r : hullRegion) {
			if (r == region) {
				return true;
			}
		}
		return false;
	}

	/** 로컬 좌표 p 를 포함하는 첫 hull 번호, 없으면 -1. */
	public int find(float x, float y, float z) {
		for (int h = 0; h < hulls.size(); h++) {
			if (insideHull(h, x, y, z)) {
				return h;
			}
		}
		return -1;
	}

	/**
	 * hull h 안인지. 평면이 1개뿐인 hull 은 평면이 아니라 구 (x, y, z, r) 다 — 그 값이 hull 중심과 같다
	 * (PAND00B ENV_Warpto00A 의 WarpVol: 중심 (768, -256, -128), r 512). 평면으로 읽으면 반공간이 되어 볼륨 한쪽 전체가 안으로 잡힌다 (36 문서 §5).
	 */
	public boolean insideHull(int h, float x, float y, float z) {
		List<float[]> ps = hulls.get(h).planes;
		if (ps.size() == 1) {
			float[] b = ps.getFirst();
			float dx = x - b[0], dy = y - b[1], dz = z - b[2];
			return dx * dx + dy * dy + dz * dz <= b[3] * b[3];
		}
		if (ps.isEmpty()) {
			return false;
		}
		for (float[] p : ps) {
			if (p[0] * x + p[1] * y + p[2] * z + p[3] > 0) {
				return false;
			}
		}
		return true;
	}

	/** hull 이 가리키는 이름 (CMV 의 경우 "RCM_" + 카메라 이름). */
	public String nameOf(int hull) {
		int i = hulls.get(hull).nameIndex;
		return i < names.size() ? names.get(i) : null;
	}

	public String kindName() {
		return switch (kind) {
			case 0 -> "collision";
			case 1 -> "camera";
			case 2 -> "environment";
			case 3 -> "sound";
			default -> "kind" + kind;
		};
	}
}
