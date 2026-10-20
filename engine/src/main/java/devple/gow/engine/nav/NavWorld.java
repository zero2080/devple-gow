package devple.gow.engine.nav;

import devple.gow.engine.level.LevelCollision;
import devple.gow.tools.level.SurfaceAttr;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * 영웅 이동이 보는 세계: 정적 충돌 시트 (현재 + 다른 슬롯 레벨) + 움직이는 발판 + 줄.
 * 원작은 두 레벨 슬롯의 충돌·오브젝트가 함께 상주한다 (36 문서 §2).
 */
public final class NavWorld {

	/** 바닥: 높이, 표면 속성 마스크, 발판 지지 (정적 바닥이면 null). */
	public record Floor(float y, long attrs, Platforms.Support platform) {

		public boolean has(SurfaceAttr a) {
			return (attrs & a.bit()) != 0;
		}
	}

	private final List<LevelCollision> statics = new ArrayList<>();
	private Platforms platforms = new Platforms();
	/** 다른 슬롯 레벨의 움직이는 오브젝트 (이웃 스크립트 Animator 가 움직임, 43 §13.8) — 바닥·벽 모두 이 레벨 발판과 같은 규칙. */
	private Platforms neighbour = new Platforms();
	private final List<NavRope> ropes = new ArrayList<>();

	public NavWorld(LevelCollision... cols) {
		for (LevelCollision c : cols) {
			if (c != null) {
				statics.add(c);
			}
		}
	}

	public List<LevelCollision> statics() {
		return statics;
	}

	/** 다른 슬롯 레벨 충돌을 바꾼다 (index 1). */
	public void setOther(LevelCollision other) {
		while (statics.size() > 1) {
			statics.removeLast();
		}
		if (other != null) {
			statics.add(other);
		}
	}

	public Platforms platforms() {
		return platforms;
	}

	public void setPlatforms(Platforms p) {
		platforms = p != null ? p : new Platforms();
	}

	public Platforms neighbourPlatforms() {
		return neighbour;
	}

	/** 다른 슬롯 레벨 발판 (원작은 두 슬롯 오브젝트가 같은 충돌 질의에 든다 — 36 문서 §2). */
	public void setNeighbourPlatforms(Platforms p) {
		neighbour = p != null ? p : new Platforms();
	}

	public List<NavRope> ropes() {
		return ropes;
	}

	/** 가장 높은 바닥 (정적 + 발판). 없으면 null. */
	public Floor floor(float x, float y, float z, float drop) {
		Floor best = null;
		for (LevelCollision c : statics) {
			LevelCollision.Floor f = c.floorAt(x, y, z, drop);
			if (f != null && (best == null || f.y() > best.y)) {
				best = new Floor(f.y(), f.tri().attrs(), null);
			}
		}
		for (Platforms ps : new Platforms[]{platforms, neighbour}) {
			Platforms.Support s = ps.floor(x, y, z, drop);
			if (s != null && (best == null || s.y() > best.y)) {
				best = new Floor(s.y(), SurfaceAttr.GROUND.bit(), s);
			}
		}
		return best;
	}

	public float groundHeight(float x, float y, float z, float drop) {
		Floor f = floor(x, y, z, drop);
		return f == null ? Float.NaN : f.y;
	}

	/** 가장 가까운 벽 (영웅 충돌 면). */
	public LevelCollision.Hit wallcast(Vector3f o, Vector3f dir, float dist) {
		LevelCollision.Hit best = null;
		for (LevelCollision c : statics) {
			LevelCollision.Hit h = c.wallcast(o, dir, dist);
			if (h != null && (best == null || h.distance() < best.distance())) {
				best = h;
			}
		}
		return best;
	}

	/**
	 * 선분 질의 (원작 sub_0011E190 = 레벨 충돌 선분 캐스트): from → to 가 처음 만나는 영웅 충돌 면 (바닥·벽 모두). 없으면 null.
	 * 결과 면의 표면 속성은 호출한 쪽이 본다 (0x1E46B0 의 마스크 검사).
	 */
	public LevelCollision.Hit segment(Vector3f from, Vector3f to) {
		Vector3f d = new Vector3f(to).sub(from);
		float len = d.length();
		if (len < 1e-5f) {
			return null;
		}
		LevelCollision.Hit best = null;
		for (LevelCollision c : statics) {
			LevelCollision.Hit h = c.raycast(from, d, len, LevelCollision.Tri::solidForPlayer);
			if (h != null && (best == null || h.distance() < best.distance())) {
				best = h;
			}
		}
		return best;
	}

	/** 구 벽 밀어내기 (모든 정적 충돌). */
	public boolean collideSphere(Vector3f c, float r) {
		boolean any = false;
		for (LevelCollision col : statics) {
			any |= col.collideSphere(c, r);
		}
		return any;
	}

	/**
	 * 움직이는 오브젝트 CDV hull (문·창살·회전방·발판) 로 구 밀어내기 — 이 레벨 (skip = 붙잡는 오브젝트 제외) + 다른 슬롯 레벨 (이웃 Animator 자세).
	 */
	public boolean collideBodies(Vector3f c, float r, java.util.Collection<String> skip) {
		boolean own = platforms.collideSphere(c, r, skip);
		return neighbour.collideSphere(c, r, java.util.Set.of()) | own;
	}

	/** 줄 선분까지 거리 r 안의 가장 가까운 줄 (없으면 null). */
	public NavRope nearestRope(Vector3f p, float r) {
		NavRope best = null;
		float bd = r;
		for (NavRope rope : ropes) {
			float d = rope.distance(p);
			if (d < bd) {
				bd = d;
				best = rope;
			}
		}
		return best;
	}
}
