package devple.gow.engine.nav;

import devple.gow.tools.anim.Animation;
import devple.gow.tools.anim.AnimationSet;
import devple.gow.tools.anim.Mat4;
import devple.gow.tools.anim.Skeleton;
import devple.gow.tools.level.BallHull;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.level.LevelLayout;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 움직이는 발판·엘리베이터: Animator 엔티티가 애니를 트는 레벨 오브젝트의 CDV(동적 충돌, BallHull 0) hull 윗면이 바닥이 된다.
 * hull 은 자기 관절(BallHull off[6]) 로컬 좌표이고, 관절 월드 = 오브젝트 애니 포즈 × 인스턴스 행렬 (38 문서 §5).
 * 영웅이 그 위에 서 있으면 지지 관절의 이번 틱 이동만큼 같이 옮긴다 (carry).
 * 밟을 수 있는 hull = 평면 이름(재질)이 시트 표면처럼 ground/floor 인 것 (예 entities_groundRock) — 칼날·불·화살 함정의 DamageMat 등은 제외
 * (전 레벨 Animator 오브젝트 CDV 253 개 조사).
 */
public final class Platforms {

	/** 지지 바닥: 높이, 오브젝트, hull 관절. */
	public record Support(float y, Body body, int joint) {
	}

	/** 오브젝트 하나 (CDV hull + 애니). */
	public static final class Body {
		public final String owner;
		final Skeleton skeleton;
		final float[] inst;
		final AnimationSet anims;
		final BallHull hull;
		final boolean[] walkable;
		Animation anim;
		float time;
		boolean playing;
		/** 재생 속도 (음수 = 역재생), 반복 (0x2), 왕복 (0x4) — Animator 슬롯 1·2 (원작 0x1AB620). */
		float rate = 1;
		boolean loop, pingPong;
		float[][] joints;
		float[][] prev;
		/** 충돌 켜짐 — 스크립트 Vis Attr11_0 (1 켬 / 2 끔: "collisionOFF", "DoorCollOff") [추정: 슬롯 이름은 미확정, 데이터 용례로]. */
		public boolean solid = true;

		Body(String owner, Skeleton sk, float[] inst, AnimationSet anims, BallHull hull) {
			this.owner = owner;
			this.skeleton = sk;
			this.inst = inst;
			this.anims = anims;
			this.hull = hull;
			walkable = new boolean[hull == null ? 0 : hull.hulls.size()];
			for (int h = 0; h < walkable.length; h++) {
				int ni = hull.hulls.get(h).nameIndex();
				String n = ni >= 0 && ni < hull.names.size() ? hull.names.get(ni) : String.join(",", hull.names);
				walkable[h] = walkable(n);
			}
			joints = LevelLayout.jointWorld(sk, inst);
			prev = joints;
		}

		void pose() {
			prev = joints;
			if (anim == null) {
				return;
			}
			float[][] lw = anim.sample(skeleton, time, false).world(skeleton);
			float[][] w = new float[lw.length][];
			for (int i = 0; i < lw.length; i++) {
				w[i] = Mat4.mul(lw[i], inst);
			}
			joints = w;
		}

		public boolean playing() {
			return playing;
		}

		/** 한 번 재생 중 (반복·왕복이 아님) — 끝나기를 기다릴 수 있는 애니. */
		public boolean playingOnce() {
			return playing && !loop && !pingPong;
		}

		public float time() {
			return time;
		}

		public Animation anim() {
			return anim;
		}

		/** 현재 관절 월드 행렬 (Marker 위치 갱신용). */
		public float[][] joints() {
			return joints;
		}

		/** 현재 act 의 관절 숨김 (u8 가시성 트랙, 41 §1.3) — 애니가 없거나 트랙이 없으면 null. */
		public boolean[] hidden() {
			return anim == null ? null : anim.hiddenJoints(skeleton, time);
		}

		/** (x, z) 수직선이 hull 들과 만나는 구간의 윗면 중 y+step 이하·y-drop 이상에서 가장 높은 것. 없으면 NaN. hull 관절은 out[0]. */
		float top(float x, float y, float z, float drop, int[] jointOut) {
			float best = Float.NaN;
			for (int h = 0; h < walkable.length; h++) {
				BallHull.Hull hh = hull.hulls.get(h);
				if (!walkable[h] || hh.planes().size() < 4) {
					continue; // 평면 1 개 = 구 (ENV 전용 표기), 발판 아님
				}
				int j = Math.min(hull.hullJoint[h], joints.length - 1);
				float[] inv = Mat4.invertAffine(joints[j]);
				float[] p0 = new float[3];
				Mat4.transformPoint(inv, x, 0, z, p0);
				float[] p1 = new float[3];
				Mat4.transformPoint(inv, x, 1, z, p1);
				float dx = p1[0] - p0[0], dy = p1[1] - p0[1], dz = p1[2] - p0[2];
				float lo = -1e9f, hi = 1e9f;
				for (float[] pl : hh.planes()) {
					float a = pl[0] * dx + pl[1] * dy + pl[2] * dz;
					float b = -(pl[0] * p0[0] + pl[1] * p0[1] + pl[2] * p0[2] + pl[3]);
					if (Math.abs(a) < 1e-9f) {
						if (b < 0) {
							lo = 1e9f;
						}
					} else if (a > 0) {
						hi = Math.min(hi, b / a);
					} else {
						lo = Math.max(lo, b / a);
					}
				}
				if (lo <= hi && hi <= y + 1e-3f && hi >= y - drop && (Float.isNaN(best) || hi > best)) {
					best = hi;
					jointOut[0] = j;
				}
			}
			return best;
		}
	}

	private final List<Body> bodies = new ArrayList<>();

	/** CDV 재질 이름이 밟는 면인지 (시트 표면 이름 규칙: groundRock, groundWood, Ground, floor …). */
	public static boolean walkable(String material) {
		String l = material == null ? "" : material.toLowerCase(java.util.Locale.ROOT);
		return l.contains("ground") || l.contains("floor");
	}

	public Platforms() {
	}

	/**
	 * 레벨에서 발판 후보: CDV 볼륨이 있고 ANM 이 있으며 owners(스크립트 Animator 엔티티가 가리키는 GO) 에 든 오브젝트.
	 */
	public static Platforms of(LevelData lv, Collection<String> owners) {
		return of(lv, owners, java.util.Set.of());
	}

	/**
	 * posed = 충돌 볼륨이 없어도 자세만 갖는 오브젝트 (R1 레버·크랭크·문: 진행 시간으로 관절 자세 → Marker·렌더, {@link #poseAt}).
	 */
	public static Platforms of(LevelData lv, Collection<String> owners, Collection<String> posed) {
		Platforms ps = new Platforms();
		for (LevelLayout.Placement p : lv.layout.placements()) {
			String name = p.instance().name();
			if (!owners.contains(name) && !posed.contains(name) || p.object().skeleton() == null) {
				continue;
			}
			BallHull cdv = null;
			String anm = null;
			for (String r : p.object().refs()) {
				BallHull h = lv.hulls.get(r);
				if (h != null && h.kind == 0 && !h.hulls.isEmpty()) {
					cdv = h;
				}
				if (r.startsWith("ANM_")) {
					anm = r;
				}
			}
			if (cdv == null && !posed.contains(name)) {
				continue;
			}
			AnimationSet set = null;
			try {
				set = anm != null ? AnimationSet.load(lv.wad, anm) : null;
			} catch (RuntimeException ignored) {
				// 애니 없는 발판은 정지 상태로만
			}
			Body body = new Body(name, p.object().skeleton(), p.instance().matrix(), set, cdv);
			// 시작 자세 = 인스턴스 시작 act 를 시작 시간에 (원작 GO 리셋 0x1309C0, StartPose) — Marker·hull 도 렌더와 같은 자세
			devple.gow.tools.level.StartPose sp = devple.gow.tools.level.StartPose.of(p, set);
			if (sp.act() != null) {
				body.joints = sp.jointWorld();
				body.prev = body.joints;
			}
			ps.bodies.add(body);
		}
		return ps;
	}

	public List<Body> bodies() {
		return bodies;
	}

	public Body body(String owner) {
		for (Body b : bodies) {
			if (b.owner.equalsIgnoreCase(owner)) {
				return b;
			}
		}
		return null;
	}

	/**
	 * Animator 엔티티 (SCR AnimName): "!n" = 오브젝트 세트의 n 번째 act [추정: ATHN05D 엘리베이터 "!0" = 유일한 act], 그 밖에는 act 이름.
	 *
	 * @return 재생을 시작했으면 true
	 */
	public boolean play(String owner, String animName) {
		return play(owner, animName, 0, 1, 0);
	}

	/**
	 * Animator 효과 (원작 0x1AB0B8) [확실: 코드]: 슬롯 0 이름 ("!n" = n 번째 act, "$…" = 재질 애니 → 여기서는 무시), 슬롯 1 모드 비트,
	 * 슬롯 2 속도, 슬롯 3 시작 시간.
	 * <ul>
	 *   <li>0x10 = 일시 정지, 0x11 = 이어 재생 (그 밖의 설정 없음)</li>
	 *   <li>0x1 = 역재생 (속도 부호 반전, 시작 시간 ≤ 0 이면 애니 끝에서), 0x8 = 시간 유지 (지금 자리에서 이어서)</li>
	 *   <li>재생 방식 0x100A60: 0x4 → 4, 0x2 → 1, 그 밖 → 2 [추정: 4 왕복, 1 반복, 2 한 번]</li>
	 * </ul>
	 */
	public boolean play(String owner, String animName, int mode, float speed, float start) {
		Body b = body(owner);
		if (b == null || b.anims == null || animName != null && animName.startsWith("$")) {
			return false;
		}
		if (mode == 0x10 || mode == 0x11) {
			if (b.anim == null) {
				return false;
			}
			b.playing = mode == 0x11;
			return true;
		}
		Animation a = null;
		List<Animation> all = b.anims.all();
		if (animName != null && animName.startsWith("!")) {
			try {
				int i = Integer.parseInt(animName.substring(1));
				a = i >= 0 && i < all.size() ? all.get(i) : null;
			} catch (NumberFormatException ignored) {
				// 이름으로
			}
		}
		if (a == null && animName != null) {
			a = b.anims.find(animName);
		}
		if (a == null && !all.isEmpty()) {
			a = all.getFirst();
		}
		if (a == null) {
			return false;
		}
		boolean keep = (mode & 0x8) != 0 && b.anim == a;
		b.anim = a;
		b.rate = (mode & 0x1) != 0 ? -speed : speed;
		b.pingPong = (mode & 0x4) != 0;
		b.loop = !b.pingPong && (mode & 0x2) != 0;
		if (!keep) {
			b.time = (mode & 0x1) != 0 && start <= 0 ? a.duration : Math.min(start, a.duration);
		}
		b.playing = true;
		return true;
	}

	/**
	 * 진행 시간으로 자세 (R1 오브젝트: 원작 오브젝트 애니 시간 = 과제 진행 시간, 0x215928 / 0x2118E8). act = null → 가장 긴 act.
	 *
	 * @return 자세가 바뀌었으면 true
	 */
	public boolean poseAt(String owner, String act, float t) {
		Body b = body(owner);
		if (b == null || b.anims == null) {
			return false;
		}
		if (b.anim == null || act != null && !b.anim.name.equalsIgnoreCase(act)) {
			Animation a = act != null ? b.anims.find(act) : null;
			if (a == null) {
				for (Animation x : b.anims.all()) {
					if (a == null || x.duration > a.duration) {
						a = x;
					}
				}
			}
			if (a == null) {
				return false;
			}
			b.anim = a;
			b.time = -1;
		}
		if (Math.abs(b.time - t) < 1e-5f) {
			return false;
		}
		b.time = t;
		b.playing = false;
		b.pose();
		return true;
	}

	public void update(float dt) {
		for (Body b : bodies) {
			if (b.playing && b.anim != null) {
				float d = b.anim.duration;
				b.time += dt * b.rate;
				if (b.time > d || b.time < 0) {
					if (b.loop && d > 0) {
						b.time = ((b.time % d) + d) % d;
					} else if (b.pingPong && d > 0) {
						b.time = b.time > d ? 2 * d - b.time : -b.time;
						b.rate = -b.rate;
					} else {
						b.time = Math.max(0, Math.min(d, b.time));
						b.playing = false;
					}
				}
				b.pose();
			} else {
				b.prev = b.joints;
			}
		}
	}

	/**
	 * 구 밀어내기 (CDV 볼록 hull = 문·창살·회전방 벽): hull 관절 로컬로 옮겨 각 평면 n·x + d 의 부호 거리 중 최댓값이 반지름보다 작으면
	 * 그 평면 법선 방향으로 밀어낸다 (가장 얕게 파고든 면) [근사: 모서리·꼭짓점 영역은 면으로 처리]. skip = 붙잡는 오브젝트 (레버·크랭크·상자).
	 *
	 * @return 밀렸으면 true
	 */
	public boolean collideSphere(org.joml.Vector3f c, float r, Collection<String> skip) {
		boolean moved = false;
		for (int iter = 0; iter < 3; iter++) {
			boolean any = false;
			for (Body b : bodies) {
				if (b.solid && b.hull != null && !skip.contains(b.owner)) {
					any |= pushOut(b, c, r);
				}
			}
			moved |= any;
			if (!any) {
				break;
			}
		}
		return moved;
	}

	private static boolean pushOut(Body b, org.joml.Vector3f c, float r) {
		boolean moved = false;
		for (int h = 0; h < b.hull.hulls.size(); h++) {
			BallHull.Hull hh = b.hull.hulls.get(h);
			if (hh.planes().size() < 4) {
				continue;
			}
			float[] m = b.joints[Math.min(b.hull.hullJoint[h], b.joints.length - 1)];
			float[] inv = Mat4.invertAffine(m);
			float[] lp = new float[3];
			Mat4.transformPoint(inv, c.x, c.y, c.z, lp);
			float scale = (float) Math.sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2]);
			float lr = r / Math.max(1e-3f, scale);
			float best = -Float.MAX_VALUE;
			float[] bn = null;
			for (float[] pl : hh.planes()) {
				float len = (float) Math.sqrt(pl[0] * pl[0] + pl[1] * pl[1] + pl[2] * pl[2]);
				if (len < 1e-6f) {
					continue;
				}
				float sd = (pl[0] * lp[0] + pl[1] * lp[1] + pl[2] * lp[2] + pl[3]) / len;
				if (sd > best) {
					best = sd;
					bn = new float[]{pl[0] / len, pl[1] / len, pl[2] / len};
				}
			}
			if (bn != null && best < lr - 1e-3f) {
				float push = lr - best;
				float[] w = new float[3];
				Mat4.transformPoint(m, lp[0] + bn[0] * push, lp[1] + bn[1] * push, lp[2] + bn[2] * push, w);
				c.set(w[0], w[1], w[2]);
				moved = true;
			}
		}
		return moved;
	}

	/** 소유 GO 의 충돌 켜고 끄기 (스크립트 Vis Attr11_0). */
	public void setSolid(String owner, boolean on) {
		Body b = body(owner);
		if (b != null) {
			b.solid = on;
		}
	}

	/** 가장 높은 발판 윗면 (없으면 null). */
	public Support floor(float x, float y, float z, float drop) {
		Support best = null;
		int[] j = new int[1];
		for (Body b : bodies) {
			float t = b.top(x, y, z, drop, j);
			if (!Float.isNaN(t) && (best == null || t > best.y)) {
				best = new Support(t, b, j[0]);
			}
		}
		return best;
	}

	/** 지지 관절의 이번 틱 이동을 점에 적용 (발판에 실려 감). */
	public static void carry(Vector3f p, Support s) {
		if (s == null || s.body.prev == s.body.joints) {
			return;
		}
		float[] inv = Mat4.invertAffine(s.body.prev[s.joint]);
		float[] l = new float[3];
		Mat4.transformPoint(inv, p.x, p.y, p.z, l);
		float[] w = new float[3];
		Mat4.transformPoint(s.body.joints[s.joint], l[0], l[1], l[2], w);
		p.set(w[0], w[1], w[2]);
	}

	/**
	 * 도착점 아래 정적 바닥이 없을 때: 발판 애니 중 윗면이 점 바로 아래(±tol)에 오는 시각으로 자세를 맞춘다 [근사].
	 * 원작은 Warp 직전 레벨에서 엘리베이터가 그 위치에 있다 (ATHN05D WarpFrom4A: 엘리베이터 바닥 677 = 애니 끝).
	 *
	 * @return 맞춘 발판 (없으면 null)
	 */
	public Body settleUnder(Vector3f p, float tol) {
		int[] j = new int[1];
		for (Body b : bodies) {
			if (b.anims == null) {
				continue;
			}
			for (Animation a : b.anims.all()) {
				for (float t = a.duration; t >= 0; t -= Math.max(a.duration / 60f, 1 / 30f)) {
					b.anim = a;
					b.time = t;
					b.pose();
					b.prev = b.joints;
					float top = b.top(p.x, p.y + tol, p.z, 2 * tol, j);
					if (!Float.isNaN(top)) {
						b.playing = false;
						return b;
					}
				}
			}
			b.anim = null;
			b.joints = LevelLayout.jointWorld(b.skeleton, b.inst);
			b.prev = b.joints;
		}
		return null;
	}
}
