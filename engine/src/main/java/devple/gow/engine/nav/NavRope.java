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
 * 원작은 질점-스프링 줄 시뮬레이션 (TWK Segment Mass / Spring Damping / Gravity) 이지만 여기서는 곧은 선분 [근사].
 */
public record NavRope(String owner, RopeDef.Type type, int template, float maxSwingDeg, Vector3f top, Vector3f end) {

	public float length() {
		return top.distance(end);
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
			out.add(new NavRope(p.instance().name(), d.type(), d.template(), d.angle(), top, end));
		}
		return out;
	}
}
