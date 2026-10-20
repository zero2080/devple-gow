package devple.gow.tools.anim;

import devple.gow.tools.wad.WadFile;
import devple.gow.tools.wad.WadTag;
import devple.gow.tools.wad.WadTagType;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 스켈레톤 = CXT 오브젝트 리소스 (매직 0x00040001, "v4").
 * <pre>
 * 0x00 u32 magic 0x00040001
 * 0x1C u32 jointCount
 * 0x20 u32 0
 * 0x24 u32 objectFlags
 * 0x28 u32 dataOffset          → 행렬/벡터 블록
 * 0x2C Joint[jointCount] 16B   { u32 flags, s16 firstChild, s16 nextSibling, s16 parent, s16 extIndex, f32 (-2^35 자리표시) }
 *      char name[jointCount][24]
 * dataOffset:
 *   +0x00 u32 localCount (= jointCount)   +0x04 u32 extOffset  +0x08 u32 extCount
 *   +0x0C u32 invOffset  +0x10 u32 invCount (= flags&0x80 관절 수)
 *   +0x20 u32 posOffset  +0x24 u32 rotOffset  +0x28 u32 scaleOffset  +0x2C u32 vec7Offset
 *   +0x30 Mat4 local[jointCount]   부모 기준 아이들(=바인드) 로컬 행렬, 행벡터 규약
 *   ext  Mat4[extCount]           flags&0x8 관절의 추가 회전 (extIndex 로 참조)
 *   inv  Mat4[invCount]           inverse bind (flags&0x80 관절 순서대로)
 *   pos  f32x4[jointCount]        아이들 로컬 이동
 *   rot  s32x4[jointCount]        아이들 로컬 회전 (flags&0x8000: 쿼터니언, 아니면 오일러 1회전=16384)
 *   scale f32x4[jointCount]
 * </pre>
 * 로컬 행렬 = Scale · Rot · Ext · Translate, 월드 = 로컬 · 부모월드 (CXT local / inverse bind 와 오차 1e-3 이내로 일치).
 */
public final class Skeleton {

	public static final int MAGIC = 0x00040001;

	/** 관절 플래그 비트. */
	public static final int F_EXT_ROT = 0x8;
	public static final int F_HAS_INV_BIND = 0x80;
	public static final int F_ROT_ANIM = 0x200;
	public static final int F_POS_ANIM = 0x400;
	public static final int F_SCALE_ANIM = 0x800;
	public static final int F_QUAT = 0x8000;

	/** 오일러 단위: 16384 = 1회전. */
	public static final float EULER_UNIT = (float) (2 * Math.PI / 16384.0);

	/** CXT 아이들 쿼터니언(노름 4096·2/π)을 애니메이션 쿼터니언 단위(노름 32768·2/π)로 맞추는 배율. */
	public static final float IDLE_QUAT_TO_ANIM = 8f;

	public record Joint(int index, String name, int flags, int firstChild, int nextSibling, int parent, int extIndex,
						int invIndex) {

		public boolean quaternion() {
			return (flags & F_QUAT) != 0;
		}

		public boolean hasInverseBind() {
			return invIndex >= 0;
		}
	}

	public final String name;
	public final int objectFlags;
	public final List<Joint> joints = new ArrayList<>();
	/** CXT 에 저장된 바인드 로컬 행렬. */
	public final float[][] bindLocal;
	public final float[][] ext;
	public final float[][] inverseBind;
	/** 아이들 포즈 성분 (애니메이션 기본값). rot 은 원시 정수 그대로. */
	public final float[][] idlePos;
	public final int[][] idleRot;
	public final float[][] idleScale;

	public Skeleton(String name, ByteBuffer d) {
		this.name = name;
		int n = d.getInt(0x1C);
		objectFlags = d.getInt(0x24);
		int dataOff = d.getInt(0x28);
		int inv = 0;
		for (int i = 0; i < n; i++) {
			int o = 0x2C + i * 16;
			int flags = d.getInt(o);
			int nmOff = 0x2C + n * 16 + i * 24;
			int len = 0;
			while (len < 24 && d.get(nmOff + len) != 0) {
				len++;
			}
			byte[] nb = new byte[len];
			d.get(nmOff, nb);
			joints.add(new Joint(i, new String(nb, StandardCharsets.ISO_8859_1), flags, d.getShort(o + 4), d.getShort(o + 6),
					d.getShort(o + 8), d.getShort(o + 10), (flags & F_HAS_INV_BIND) != 0 ? inv++ : -1));
		}
		int localCount = d.getInt(dataOff);
		int extOff = d.getInt(dataOff + 4), extCount = d.getInt(dataOff + 8);
		int invOff = d.getInt(dataOff + 12), invCount = d.getInt(dataOff + 16);
		int posOff = d.getInt(dataOff + 0x20), rotOff = d.getInt(dataOff + 0x24), scOff = d.getInt(dataOff + 0x28);
		if (localCount != n || invCount != inv) {
			throw new IllegalStateException("스켈레톤 개수 불일치 local=" + localCount + " n=" + n + " inv=" + invCount + "/" + inv);
		}
		bindLocal = mats(d, dataOff + 0x30, n);
		ext = mats(d, dataOff + extOff, extCount);
		inverseBind = mats(d, dataOff + invOff, invCount);
		idlePos = new float[n][3];
		idleRot = new int[n][4];
		idleScale = new float[n][3];
		for (int i = 0; i < n; i++) {
			for (int k = 0; k < 3; k++) {
				idlePos[i][k] = d.getFloat(dataOff + posOff + i * 16 + k * 4);
				idleScale[i][k] = d.getFloat(dataOff + scOff + i * 16 + k * 4);
			}
			for (int k = 0; k < 4; k++) {
				idleRot[i][k] = d.getInt(dataOff + rotOff + i * 16 + k * 4);
			}
		}
	}

	private static float[][] mats(ByteBuffer d, int at, int count) {
		float[][] r = new float[count][16];
		for (int i = 0; i < count; i++) {
			for (int k = 0; k < 16; k++) {
				r[i][k] = d.getFloat(at + i * 64 + k * 4);
			}
		}
		return r;
	}

	public int size() {
		return joints.size();
	}

	public int indexOf(String jointName) {
		for (Joint j : joints) {
			if (j.name.equals(jointName)) {
				return j.index;
			}
		}
		return -1;
	}

	/**
	 * 회전/이동/스케일 성분 → 로컬 행렬.
	 *
	 * @param rot 쿼터니언 관절은 (x,y,z,w) 임의 스케일, 오일러 관절은 (x,y,z) 를 16384=1회전 단위로
	 */
	public float[] localMatrix(int j, float[] rot, float[] pos, float[] scale) {
		float[] out = new float[16];
		localMatrixInto(j, rot, pos, scale, out, Work.get(0));
		return out;
	}

	/** {@link #localMatrix} 를 out 에 (같은 계산 순서·값, 작업 공간 wk — out 과 겹치면 안 됨). */
	void localMatrixInto(int j, float[] rot, float[] pos, float[] scale, float[] out, Work wk) {
		Joint jt = joints.get(j);
		boolean extRot = (jt.flags & F_EXT_ROT) != 0 && jt.extIndex >= 0 && jt.extIndex < ext.length;
		float[] r = extRot ? wk.r : out;
		if (jt.quaternion()) {
			Mat4.fromQuatInto(rot[0], rot[1], rot[2], rot[3], r);
		} else {
			Mat4.fromEulerInto(rot[0] * EULER_UNIT, rot[1] * EULER_UNIT, rot[2] * EULER_UNIT, r, wk.rx, wk.ry, wk.rz, wk.t);
		}
		if (extRot) {
			Mat4.mul3Into(r, ext[jt.extIndex], out);
		}
		if (scale != null) {
			for (int row = 0; row < 3; row++) {
				for (int c = 0; c < 3; c++) {
					out[row * 4 + c] *= scale[row];
				}
			}
		}
		out[12] = pos[0];
		out[13] = pos[1];
		out[14] = pos[2];
	}

	/** 로컬 행렬 배열 → 월드 행렬 (부모가 항상 앞 인덱스라는 성질 이용, 아니면 재귀). */
	public float[][] world(float[][] local) {
		float[][] w = new float[size()][16];
		worldInto(local, w, Work.get(size()).done);
		return w;
	}

	/** 월드 행렬을 미리 만든 w (관절마다 float[16]) 에 ({@link #world} 와 같은 값). done = 작업 공간 (길이 ≥ 관절 수). */
	void worldInto(float[][] local, float[][] w, boolean[] done) {
		int n = size();
		java.util.Arrays.fill(done, 0, n, false);
		for (int i = 0; i < n; i++) {
			worldOf(i, local, w, done);
		}
	}

	private void worldOf(int i, float[][] local, float[][] w, boolean[] done) {
		if (!done[i]) {
			int p = joints.get(i).parent;
			if (p < 0) {
				System.arraycopy(local[i], 0, w[i], 0, 16);
			} else {
				worldOf(p, local, w, done);
				Mat4.mulInto(local[i], w[p], w[i]);
			}
			done[i] = true;
		}
	}

	/**
	 * 자세 샘플용 스레드별 작업 공간 (48 문서 §9: 관절마다 로컬·회전 float[16] 할당 제거). 적재 스레드도 자세를 샘플하므로 스레드별.
	 */
	static final class Work {
		private static final ThreadLocal<Work> TL = ThreadLocal.withInitial(Work::new);
		final float[] r = new float[16], rx = new float[16], ry = new float[16], rz = new float[16], t = new float[16];
		float[][] local = new float[0][];
		boolean[] done = new boolean[0];

		static Work get(int joints) {
			Work w = TL.get();
			if (w.local.length < joints) {
				w.local = new float[joints][16];
				w.done = new boolean[joints];
			}
			return w;
		}
	}

	/** 월드 → 스키닝 행렬: inverse bind 가 있는 관절은 invBind · world, 없으면 world (정점이 관절 로컬). */
	public float[][] skin(float[][] world) {
		return skin(world, new float[size()][], null);
	}

	/**
	 * {@link #skin(float[][])} 를 호출자 버퍼에: out (길이 ≥ 관절 수) 의 칸 = rows[i] (inverse bind 관절, 덮어씀) 또는 world[i].
	 * rows 가 null 이거나 그 칸이 null 이면 새로 만든다. out 을 돌려준다.
	 */
	public float[][] skin(float[][] world, float[][] out, float[][] rows) {
		for (int i = 0; i < size(); i++) {
			Joint j = joints.get(i);
			if (j.hasInverseBind()) {
				float[] r = rows != null ? rows[i] : null;
				if (r == null) {
					r = new float[16];
					if (rows != null) {
						rows[i] = r;
					}
				}
				Mat4.mulInto(inverseBind[j.invIndex], world[i], r);
				out[i] = r;
			} else {
				out[i] = world[i];
			}
		}
		return out;
	}

	/** CXT 바인드 로컬 행렬 기준 월드 행렬. */
	public float[][] bindWorld() {
		return world(bindLocal);
	}

	/** 아이들 성분으로 만든 로컬 행렬 (bindLocal 과 일치해야 함). */
	public float[][] idleLocal() {
		float[][] l = new float[size()][];
		for (int i = 0; i < size(); i++) {
			int[] r = idleRot[i];
			l[i] = localMatrix(i, new float[]{r[0], r[1], r[2], r[3]}, idlePos[i], idleScale[i]);
		}
		return l;
	}

	/** 검증: |idleLocal - bindLocal| 최대값, |bindWorld·inverseBind - I| 최대값. */
	public float[] verify() {
		float[][] il = idleLocal();
		float maxLocal = 0;
		for (int i = 0; i < size(); i++) {
			maxLocal = Math.max(maxLocal, Mat4.diff(il[i], bindLocal[i]));
		}
		float[][] w = bindWorld();
		float maxInv = 0;
		for (Joint j : joints) {
			if (j.hasInverseBind()) {
				maxInv = Math.max(maxInv, Mat4.diff(Mat4.mul(w[j.index], inverseBind[j.invIndex]), Mat4.identity()));
			}
		}
		return new float[]{maxLocal, maxInv};
	}

	/** WAD 의 모든 0x40001 CXT. */
	public static List<Skeleton> loadAll(WadFile wad) {
		List<Skeleton> out = new ArrayList<>();
		for (WadTag t : wad.resources()) {
			if (t.magic() == MAGIC) {
				out.add(new Skeleton(t.name(), t.data()));
			}
		}
		return out;
	}

	/** CXT 와 같은 그룹 안에 있는 리소스 이름들 (ANM_x, MDL_x 참조 등). */
	public static List<String> groupRefs(WadFile wad, String cxtName) {
		List<String> out = new ArrayList<>();
		List<WadTag> tags = wad.tags();
		for (int i = 0; i < tags.size(); i++) {
			WadTag t = tags.get(i);
			if (t.tag() != WadTagType.RESOURCE || !t.hasData() || t.magic() != MAGIC || !t.name().equals(cxtName)) {
				continue;
			}
			for (int k = i + 1; k < tags.size(); k++) {
				WadTag s = tags.get(k);
				if (s.depth() < t.depth()) {
					break;
				}
				if (s.depth() == t.depth() && s.tag() == WadTagType.RESOURCE) {
					if (s.hasData() && s.magic() == MAGIC) {
						break;
					}
					out.add(s.name());
				}
			}
			break;
		}
		return out;
	}
}
