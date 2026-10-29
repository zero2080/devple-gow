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
 *                     [5] u8 hull별 평면 수  [6] u8 hull별 관절  [7] u8 hull별 영역  [8] vec4 hull 중심
 *                     [9] u8 평면별 이름 번호(CMV: 활성화할 카메라 RCM_x)  [10] vec4 평면 (n, d)
 * 내부 판정: ball 안 (|p - c| &lt;= r) 이거나, hull 의 모든 평면에 대해 n·p + d &lt;= 0
 *   (n 은 관절 스케일이 반영된 비정규 벡터일 수 있음 — 부호 판정이므로 로컬 좌표에서 그대로 쓴다)
 * </pre>
 */
public final class BallHull {

	/**
	 * @param nameIndex      첫 평면의 이름 (재질 엔트리) 번호
	 * @param planeMaterials 평면별 재질 엔트리 번호 (off[9] u8) — CDV 는 평면마다 표면 속성이 다르다 (충돌 질의 0x139218 이 맞은 평면의
	 *                       재질 +0x18 bitfield0 을 질의 제외 마스크와 비교)
	 */
	public record Hull(float[] center, List<float[]> planes, int nameIndex, int[] planeMaterials) {
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
	/** hull 별 관절 번호 (off[6] u8) — hull 평면·중심은 이 관절 로컬 좌표 (예 ATHN05D 엘리베이터 CDV: hull 0 = 관절 2, hull 1 = 관절 1). */
	public final int[] hullJoint;
	/** 재질 엔트리별 PartType (+0x30, 엔트리 크기 0x34 미만이면 0). 히어로 몸 1, 오른발 7, extra1 9 / 블레이드 2, 쌍 11. */
	public final int[] materialPart;
	/**
	 * 재질 엔트리별 SheetFX (+0x2C) = MFX 템플릿 번호 (exe 속성 "SheetFX", 오프셋 표 0x3376FC; 0x203450 이 피격 구의 이 값으로
	 * /TweakTemplates/MFX/&lt;n&gt;/ 을 찾는다). 히어로 몸 501 "Hero Flesh", 미노타우로스 510, 키클롭스 506, 0 = 없음.
	 */
	public final int[] materialFx;
	/**
	 * 재질 엔트리별 표면 속성 ({@link SurfaceAttr} 마스크). 재질 엔트리 = 충돌 시트 표면 레코드와 같은 배치 (이름 24B + 데이터 40B):
	 * +0x18 bitfield0 (Ground 0 … NoCSMCollision 29), +0x1C bitfield1 (TakeDamage 0 … NoShadows 8), +0x28 doubleSided,
	 * +0x2C SheetFX, +0x30 PartType — 스키마는 전 시트 공통 배치 그대로 (38 문서 §1).
	 */
	public final long[] materialAttrs;
	/** 재질 엔트리 원값 (bitfield0, bitfield1) — 검증·조사용. */
	public final int[][] materialRaw;
	/**
	 * 재질 엔트리별 접촉 피해 "Damage" (+0x38, 영웅) / "AIDamage" (+0x3C, 적) — 표면 레코드 +0x20 / +0x24 (스키마 0x1BECA0 → 오프셋 0x337708 /
	 * 0x33770C, 엔트리 크기 0x40 미만이면 0). 읽는 곳은 접촉 0x1E1DF0 하나 (+0x174 비트 31 = 플레이어면 Damage, 아니면 AIDamage) [확실].
	 * 예 PAND03C 굴러오는 불덩이 CDV_LeftRock1 20 / 1024, PAND03E 바람개비 날 10 / 0, HDES01C 죽음 굴림대 10 / 10.
	 */
	public final int[] materialDamage, materialAiDamage;

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
		materialFx = new int[nameCount];
		materialAttrs = new long[nameCount];
		materialRaw = new int[nameCount][2];
		materialDamage = new int[nameCount];
		materialAiDamage = new int[nameCount];
		for (int i = 0; i < nameCount; i++) {
			if (nameStride >= 0x40 && off[0] + i * nameStride + 0x40 <= d.limit()) {
				int e = off[0] + i * nameStride;
				materialDamage[i] = d.getInt(e + 0x38);
				materialAiDamage[i] = d.getInt(e + 0x3C);
			}
			if (nameStride >= 0x34 && off[0] + i * nameStride + 0x34 <= d.limit()) {
				int e = off[0] + i * nameStride;
				materialPart[i] = d.getInt(e + 0x30);
				materialFx[i] = d.getInt(e + 0x2C);
				materialRaw[i][0] = d.getInt(e + 0x18);
				materialRaw[i][1] = d.getInt(e + 0x1C);
				materialAttrs[i] = SurfaceAttr.fromRecord(materialRaw[i][0], materialRaw[i][1], d.getInt(e + 0x28));
			}
		}
		ballJoint = new int[ballCount];
		ballMaterial = new int[ballCount];
		ballRegion = new int[ballCount];
		hullRegion = new int[hullCount];
		hullJoint = new int[hullCount];
		for (int b = 0; b < ballCount; b++) {
			ballRegion[b] = off[2] + b < d.limit() ? d.get(off[2] + b) & 0xFF : 0;
		}
		for (int h = 0; h < hullCount; h++) {
			hullRegion[h] = off[7] + h < d.limit() ? d.get(off[7] + h) & 0xFF : 0;
			hullJoint[h] = off[6] + h < d.limit() ? d.get(off[6] + h) & 0xFF : 0;
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
			int[] pm = new int[n];
			int k = 0;
			for (; k < n && off[10] + plane * 16 + 16 <= d.limit(); k++, plane++) {
				int o = off[10] + plane * 16;
				ps.add(new float[]{d.getFloat(o), d.getFloat(o + 4), d.getFloat(o + 8), d.getFloat(o + 12)});
				pm[k] = off[9] + plane < d.limit() ? d.get(off[9] + plane) & 0xFF : 0;
			}
			hulls.add(new Hull(c, ps, nameIndex, java.util.Arrays.copyOf(pm, k)));
		}
	}

	/** hull h 의 평면 k 의 표면 속성 마스크 (재질이 없으면 0). */
	public long planeAttrs(int h, int k) {
		int[] pm = hulls.get(h).planeMaterials;
		int m = k < pm.length ? pm[k] : -1;
		return m >= 0 && m < materialAttrs.length ? materialAttrs[m] : 0;
	}

	/** ball b 의 SheetFX = MFX 템플릿 (재질 엔트리 +0x2C, 0 = 없음). */
	public int ballFx(int b) {
		int m = ballMaterial[b];
		return m < materialFx.length ? materialFx[m] : 0;
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
		float[] p = {x, y, z};
		return contains(j -> p);
	}

	/**
	 * 조각마다 그 조각 관절 공간의 점 ({@code local.apply(관절)}) 으로 — 관절 0 이 아닌 조각이 있는 볼륨 ({@link LevelData.PlacedVolume#partInverse}).
	 */
	public boolean contains(java.util.function.IntFunction<float[]> local) {
		for (int i = 0; i < balls.size(); i++) {
			if (inBall(i, local.apply(jointOfBall(i)))) {
				return true;
			}
		}
		return find(local) >= 0;
	}

	/** 로컬 좌표 p 를 포함하는 ball/hull 의 영역 번호들 (밖이면 빈 집합). */
	public java.util.Set<Integer> regionsAt(float x, float y, float z) {
		float[] p = {x, y, z};
		return regionsAt(j -> p);
	}

	/** {@link #regionsAt(float, float, float)} 의 조각 관절별 점 판. */
	public java.util.Set<Integer> regionsAt(java.util.function.IntFunction<float[]> local) {
		java.util.Set<Integer> out = new java.util.TreeSet<>();
		for (int i = 0; i < balls.size(); i++) {
			if (inBall(i, local.apply(jointOfBall(i)))) {
				out.add(ballRegion[i]);
			}
		}
		for (int h = 0; h < hulls.size(); h++) {
			float[] p = local.apply(jointOfHull(h));
			if (insideHull(h, p[0], p[1], p[2])) {
				out.add(hullRegion[h]);
			}
		}
		return out;
	}

	private boolean inBall(int i, float[] p) {
		float[] b = balls.get(i);
		float dx = p[0] - b[0], dy = p[1] - b[1], dz = p[2] - b[2];
		return dx * dx + dy * dy + dz * dz <= b[3] * b[3];
	}

	/** 조각이 붙은 관절 (off[6], 없으면 0). */
	public int jointOfHull(int h) {
		return h < hullJoint.length ? Math.max(0, hullJoint[h]) : 0;
	}

	public int jointOfBall(int b) {
		return b < ballJoint.length ? Math.max(0, ballJoint[b]) : 0;
	}

	/** 서로 다른 영역 ID 개수. */
	public int regionCount() {
		java.util.Set<Integer> s = new java.util.HashSet<>();
		for (int r : ballRegion) {
			s.add(r);
		}
		for (int r : hullRegion) {
			s.add(r);
		}
		return s.size();
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

	/** 로컬 좌표 p 가 영역 region 의 ball/hull 안인지. */
	public boolean contains(float x, float y, float z, int region) {
		float[] p = {x, y, z};
		return contains(j -> p, region);
	}

	/**
	 * {@link #contains(float, float, float, int)} 의 조각 관절별 점 판. 평면 1 개 hull 은 구 ({@link #insideHull}) — 예전 영역 판정은 그 값을 평면으로
	 * 읽어 반공간이 되었다 (영역 질의만, regionsAt 은 이미 구).
	 */
	public boolean contains(java.util.function.IntFunction<float[]> local, int region) {
		for (int i = 0; i < balls.size(); i++) {
			if (ballRegion[i] == region && inBall(i, local.apply(jointOfBall(i)))) {
				return true;
			}
		}
		for (int h = 0; h < hulls.size(); h++) {
			if (hullRegion[h] != region) {
				continue;
			}
			float[] p = local.apply(jointOfHull(h));
			if (insideHull(h, p[0], p[1], p[2])) {
				return true;
			}
		}
		return false;
	}

	/** 로컬 좌표 p 를 포함하는 첫 hull 번호, 없으면 -1. */
	public int find(float x, float y, float z) {
		float[] p = {x, y, z};
		return find(j -> p);
	}

	/** {@link #find(float, float, float)} 의 조각 관절별 점 판. */
	public int find(java.util.function.IntFunction<float[]> local) {
		for (int h = 0; h < hulls.size(); h++) {
			float[] p = local.apply(jointOfHull(h));
			if (insideHull(h, p[0], p[1], p[2])) {
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
