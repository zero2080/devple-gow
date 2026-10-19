package devple.gow.engine.nav;

import devple.gow.tools.level.LevelData;
import devple.gow.tools.level.LevelLayout;
import devple.gow.tools.level.RopeDef;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * 줄 하나 (월드 좌표 선분). {@link RopeDef} + 줄 오브젝트 관절:
 * <ul>
 *   <li>HANG: 관절 0 (매단 점) → 마지막 관절 (바인드 자세에서 수직으로 늘어진 끝)</li>
 *   <li>TRAVERSE / ZIPLINE: 관절 0 → SCR 끝점 (미터 ×16)</li>
 * </ul>
 * 판정·잡기는 곧은 선분. 매단 줄 (HANG) 에 매달린 동안은 {@link RopeSim} (원작 질점-스프링 0x217A40) 이 줄 모양을 움직인다.
 * 양끝 고정 줄 (TRAVERSE / ZIPLINE) 은 곧은 선분 그대로 [근사: 원작은 같은 시뮬레이션, 매달림 최대 무게 10].
 *
 * @param lengthM SCR +0x28 = 줄 길이 (미터) — 원작 +0x60 (0x2170C0, 매단 줄 시뮬레이션 길이) [확실: 전 레벨 매단 줄 = 관절 길이 / 16]
 * @param joints 줄 오브젝트 관절 수 (시뮬레이션 노드 수 [추정: 원작은 사슬 관절 수])
 * @param axis   줄 오브젝트 행렬 z 축 (수평, 원작 줄 +0x20 = 오브젝트 행렬 +0x40 행 — 0x216D2C) — 매단 줄 위 몸 방향·뛰어내리기 방향의 기준
 * @param dirs   매단 줄 방향 제한 = 템플릿 +0xB8 (#b38919fb, 0x216AB8): 0 = 축 4 방향, 1 = ±z (RopeNorthSouth), 2 = ±(z × 위) (RopeEastWest)
 */
public record NavRope(String owner, RopeDef.Type type, int template, float lengthM, Vector3f top, Vector3f end, int joints, Vector3f axis,
					  int dirs) {

	/** 관절 수를 모를 때 (시험) 9 노드 = 8 칸, 축 +z, 4 방향. */
	public NavRope(String owner, RopeDef.Type type, int template, float lengthM, Vector3f top, Vector3f end) {
		this(owner, type, template, lengthM, top, end, 9, new Vector3f(0, 0, 1), 0);
	}

	/** 템플릿 방향 제한 해시 (#b38919fb, short) [확실: 0x216AB8 등록 · 0x1C56B0 / 0x1C68E8 읽기]. */
	public static final int DIRS_HASH = 0xB38919FB;

	/**
	 * 매단 줄 위 몸 뒤쪽 후보 (원작 0x1C689C / 0x1C5664): [−z, −(z × 위), z, z × 위] 중 dirs 가 허락하는 것.
	 * 0 → 4 개 (간격 1), 1 → 0·2, 2 → 1·3 (간격 2). 돌려준 배열은 항상 4 개, first/step 은 {@link #dirFirst()} / {@link #dirStep()}.
	 */
	public Vector3f[] backDirs() {
		return backDirs(axis);
	}

	/** 줄 축 z (수평) 의 뒤쪽 후보 [−z, −(z × 위), z, z × 위] (0x1C689C). */
	public static Vector3f[] backDirs(Vector3f axis) {
		Vector3f z = new Vector3f(axis.x, 0, axis.z);
		if (z.lengthSquared() < 1e-6f) {
			z.set(0, 0, 1);
		}
		z.normalize();
		Vector3f x = new Vector3f(z).cross(0, 1, 0);
		return new Vector3f[] {new Vector3f(z).negate(), new Vector3f(x).negate(), z, x};
	}

	public int dirFirst() {
		return dirFirst(dirs);
	}

	public int dirStep() {
		return dirStep(dirs);
	}

	/** 방향 제한 → 첫 후보 번호 (0 / 1 → 0, 2 → 1, 0x1C68E8). */
	public static int dirFirst(int dirs) {
		return dirs == 0 || dirs == 1 ? 0 : 1;
	}

	/** 방향 제한 → 후보 간격 (0 → 1, 그 밖 2). */
	public static int dirStep(int dirs) {
		return dirs == 0 ? 1 : 2;
	}

	/** 후보 중 v 와 내적이 가장 큰 것의 번호 (원작: 처음 최댓값 −1, 같으면 먼저 것). */
	public int bestDir(Vector3f v) {
		return bestDir(axis, dirs, v);
	}

	public static int bestDir(Vector3f axis, int dirs, Vector3f v) {
		Vector3f[] d = backDirs(axis);
		int best = dirFirst(dirs);
		float m = -1;
		for (int i = dirFirst(dirs); i < 4; i += dirStep(dirs)) {
			float dot = d[i].dot(v);
			if (dot > m) {
				m = dot;
				best = i;
			}
		}
		return best;
	}

	public float length() {
		return top.distance(end);
	}

	/** 시뮬레이션 길이 (월드): 매단 줄 = SCR +0x28 × 16 (0x2170C0), 양끝 고정 = |끝 − 매단 점| (0x2171C8). */
	public float simLength() {
		return type == RopeDef.Type.HANG && lengthM > 0 ? lengthM * RopeDef.UNITS_PER_METER : length();
	}

	/** 선분 위 매개변수 t ∈ [0, 1] 의 점 (0 = top). */
	public Vector3f point(float t, Vector3f out) {
		return out.set(top).lerp(end, Math.max(0, Math.min(1, t)));
	}

	/** 점에서 가장 가까운 선분 매개변수. */
	public float closest(Vector3f p) {
		Vector3f d = new Vector3f(end).sub(top);
		float l2 = d.lengthSquared();
		if (l2 < 1e-6f) {
			return 0;
		}
		return Math.max(0, Math.min(1, new Vector3f(p).sub(top).dot(d) / l2));
	}

	public float distance(Vector3f p) {
		return point(closest(p), new Vector3f()).distance(p);
	}

	/** 레벨의 모든 줄. */
	public static List<NavRope> of(LevelData lv) {
		List<NavRope> out = new ArrayList<>();
		devple.gow.engine.game.Tweaks tw = new devple.gow.engine.game.Tweaks();
		tw.load(lv.wad);
		for (LevelLayout.Placement p : lv.layout.placements()) {
			RopeDef d = lv.ropes.get(p.instance().name());
			if (d == null) {
				continue;
			}
			float[][] jw = p.jointWorld();
			Vector3f top = new Vector3f(jw[0][12], jw[0][13], jw[0][14]);
			int l = jw.length - 1;
			Vector3f end = d.type() == RopeDef.Type.HANG ? new Vector3f(jw[l][12], jw[l][13], jw[l][14])
					: new Vector3f(d.end()[0], d.end()[1], d.end()[2]);
			float[] m = p.instance().matrix();
			Vector3f axis = new Vector3f(m[8], 0, m[10]);
			axis = axis.lengthSquared() < 1e-6f ? new Vector3f(0, 0, 1) : axis.normalize();
			out.add(new NavRope(p.instance().name(), d.type(), d.template(), d.lengthM(), top, end, jw.length, axis, dirs(tw, d.template())));
		}
		return out;
	}

	/** 템플릿 /TweakTemplates/Rope/&lt;n&gt;/ 의 방향 제한 (없으면 0 = 4 방향, 0x216B84 기본값). */
	static int dirs(devple.gow.engine.game.Tweaks tw, int template) {
		for (String node : tw.nodes("/TweakTemplates/Rope/")) {
			String tail = node.substring("/TweakTemplates/Rope/".length()).replace("/", "");
			try {
				if (Integer.parseInt(tail) == template) {
					java.util.OptionalInt v = tw.rawHash(node, DIRS_HASH);
					return v.isPresent() ? (short) v.getAsInt() : 0;
				}
			} catch (NumberFormatException e) {
				// 템플릿 번호가 아닌 노드
			}
		}
		return 0;
	}
}
