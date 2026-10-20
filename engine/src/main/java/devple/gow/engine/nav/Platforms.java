package devple.gow.engine.nav;

import devple.gow.tools.anim.Animation;
import devple.gow.tools.anim.AnimationSet;
import devple.gow.tools.anim.Mat4;
import devple.gow.tools.anim.Skeleton;
import devple.gow.tools.level.BallHull;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.level.LevelLayout;
import devple.gow.tools.level.SurfaceAttr;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 움직이는 발판·엘리베이터: Animator 엔티티가 애니를 트는 레벨 오브젝트의 CDV(동적 충돌, BallHull 0) hull 윗면이 바닥이 된다.
 * hull 은 자기 관절(BallHull off[6]) 로컬 좌표이고, 관절 월드 = 오브젝트 애니 포즈 × 인스턴스 행렬 (38 문서 §5).
 * 영웅이 그 위에 서 있으면 지지 관절의 이번 틱 이동만큼 같이 옮긴다 (carry).
 * 바닥 = 수직 선분이 hull 로 들어가는 평면의 재질 (평면별 off[9] → 재질 엔트리 = 시트 표면 레코드와 같은 배치, +0x18 bitfield0) 이
 * 영웅 질의 제외 마스크 {@link #HERO_EXCLUDE} 에 걸리지 않는 것 [확실: 0x1E3298 / 0x1E2100 → 0x139218 의 (재질 bitfield0 &amp; 0x3309F4) 거름].
 * 이전의 이름 규칙 (재질 이름에 ground/floor) 은 폐기 — PAND04AA 엘리베이터 바닥 {@code EntitiesPand04AA_SheetM} 은 Ground 인데 빠졌다 (43 §13.10).
 */
public final class Platforms {

	/** 지지 바닥: 높이, 오브젝트, hull 관절. */
	public record Support(float y, Body body, int joint, long attrs) {
	}

	/** 오브젝트 하나 (CDV hull + 애니). */
	public static final class Body {
		public final String owner;
		final Skeleton skeleton;
		final float[] inst;
		final AnimationSet anims;
		final BallHull hull;
		/** hull 별 평면별 영웅 질의에 걸리는지 (재질이 제외 마스크에 걸리지 않음). */
		final boolean[][] heroPlane;
		/** hull 별 평면별 표면 속성 마스크. */
		final long[][] planeAttrs;
		Animation anim;
		float time;
		boolean playing;
		/** 재생 속도 (음수 = 역재생), 반복 (0x2), 왕복 (0x4) — Animator 슬롯 1·2 (원작 0x1AB620). */
		float rate = 1;
		boolean loop, pingPong;
		float[][] joints;
		float[][] prev;
		/** 스크립트 Animator·R1 오브젝트 (false = 정지 CDV 오브젝트: 도착 자세 맞추기 대상 아님). */
		public boolean scripted = true;
		/** 충돌 켜짐 — 스크립트 Vis Attr11_0 (1 켬 / 2 끔: "collisionOFF", "DoorCollOff") [추정: 슬롯 이름은 미확정, 데이터 용례로]. */
		public boolean solid = true;

		Body(String owner, Skeleton sk, float[] inst, AnimationSet anims, BallHull hull) {
			this.owner = owner;
			this.skeleton = sk;
			this.inst = inst;
			this.anims = anims;
			this.hull = hull;
			int hn = hull == null ? 0 : hull.hulls.size();
			heroPlane = new boolean[hn][];
			planeAttrs = new long[hn][];
			for (int h = 0; h < hn; h++) {
				int np = hull.hulls.get(h).planes().size();
				heroPlane[h] = new boolean[np];
				planeAttrs[h] = new long[np];
				for (int k = 0; k < np; k++) {
					planeAttrs[h][k] = hull.planeAttrs(h, k);
					heroPlane[h][k] = heroSolid(planeAttrs[h][k]);
				}
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

		/**
		 * hull h 가 영웅 몸을 막는지 — 평면 하나라도 {@link #WALL_EXCLUDE} 에 안 걸리면. 몸 질의 0x1E2100 의 고정 마스크는 0x10042 (바닥 0x1E3298 의
		 * 0x1010042 와 달리 GeneralGuide 없음) 이고 호출 마스크 (0x1EA200 0x8020000 / 0x28020000, 0x1EA5F0 0x20000 | 0x8000000 | 0x20000000) 에도 없다
		 * → GeneralGuide 면 (ATHN01B goplayerblock "guideMat" 등) 은 몸을 막는다 [확실] (38 §18.4).
		 */
		boolean wallHull(int h) {
			for (long a : planeAttrs[h]) {
				if ((a & WALL_EXCLUDE) == 0) {
					return true;
				}
			}
			return false;
		}

		/**
		 * (x, z) 수직선이 hull 들과 만나는 구간의 윗면 중 y+step 이하·y-drop 이상에서 가장 높은 것. 없으면 NaN. hull 관절은 out[0],
		 * 윗면 평면의 표면 속성은 attrsOut[0]. 윗면 평면 (아래로 내리는 선분이 처음 맞는 면) 의 재질이 영웅 질의 제외면 그 hull 은 바닥이 아니다.
		 */
		float top(float x, float y, float z, float drop, int[] jointOut) {
			return top(x, y, z, drop, jointOut, null);
		}

		float top(float x, float y, float z, float drop, int[] jointOut, long[] attrsOut) {
			float best = Float.NaN;
			if (!solid) {
				return best; // 충돌 끔 (Vis Attr11_0 = 2) — 충돌 세계에서 빠짐
			}
			for (int h = 0; h < heroPlane.length; h++) {
				BallHull.Hull hh = hull.hulls.get(h);
				if (hh.planes().size() < 4) {
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
				int topPlane = -1;
				List<float[]> planes = hh.planes();
				for (int k = 0; k < planes.size(); k++) {
					float[] pl = planes.get(k);
					float a = pl[0] * dx + pl[1] * dy + pl[2] * dz;
					float b = -(pl[0] * p0[0] + pl[1] * p0[1] + pl[2] * p0[2] + pl[3]);
					if (Math.abs(a) < 1e-9f) {
						if (b < 0) {
							lo = 1e9f;
						}
					} else if (a > 0) {
						if (b / a < hi) {
							hi = b / a;
							topPlane = k;
						}
					} else {
						lo = Math.max(lo, b / a);
					}
				}
				if (topPlane < 0 || !heroPlane[h][topPlane]) {
					continue;
				}
				if (lo <= hi && hi <= y + 1e-3f && hi >= y - drop && (Float.isNaN(best) || hi > best)) {
					best = hi;
					jointOut[0] = j;
					if (attrsOut != null) {
						attrsOut[0] = planeAttrs[h][topPlane];
					}
				}
			}
			return best;
		}
	}

	private final List<Body> bodies = new ArrayList<>();

	/**
	 * 영웅 충돌 질의의 면 제외 마스크 [확실]: 바닥 0x1E3298 / 몸 0x1E2100 이 0x3309F4 = 호출 마스크 | 0x1010042 | (영웅 +0x258 &amp; 0x4300000) 로 둔다.
	 * 0x1010042 = Water·Death·AIBlock·GeneralGuide, 영웅 +0x258 = 0x500000 (Player 생성 0x1D63E0) → NoPlayerCollision,
	 * 바닥 호출 마스크 0x8020000 (0x1C58D8 등) = ClimbGuide·CombatGuide. 면 (시트 삼각형·CDV 평면) 재질 bitfield0 이 하나라도 걸리면 그 면은 건너뛴다.
	 */
	public static final long HERO_EXCLUDE = SurfaceAttr.WATER.bit() | SurfaceAttr.DEATH.bit() | SurfaceAttr.AI_BLOCK.bit()
			| SurfaceAttr.GENERAL_GUIDE.bit() | SurfaceAttr.NO_PLAYER_COLLISION.bit() | SurfaceAttr.CLIMB_GUIDE.bit() | SurfaceAttr.COMBAT_GUIDE.bit();

	/**
	 * 벽 (구 밀어내기) 제외 마스크 = {@link #HERO_EXCLUDE} − GeneralGuide [확실: 0x1E2224 고정 0x10042]. ClimbGuide 는 벽 상태가 아닐 때만,
	 * CombatGuide 는 플레이어 전투 기술 (기술 +0x13 = 2) 이 아닐 때만 빠진다 (0x1EA6B4 / 0x1EA6C0) — 엔진은 늘 뺌 [근사].
	 */
	public static final long WALL_EXCLUDE = HERO_EXCLUDE & ~SurfaceAttr.GENERAL_GUIDE.bit();

	/** 표면 속성의 면이 영웅 바닥 질의에 걸리는지 ({@link #HERO_EXCLUDE} 에 하나도 안 걸림). */
	public static boolean heroSolid(long attrs) {
		return (attrs & HERO_EXCLUDE) == 0;
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
	 * 그 밖에 CDV 가 있는 배치 GO 도 모두 넣는다 ({@link #staticOwner}) — 원작은 GO 를 만들 때 CDV 리소스마다 BallHull 인스턴스를 만들어
	 * 충돌 세계 0x29C978 에 올린다 (리소스 vtable 0x2BF75C → 0x11D498 → 0x138E70 → 0x138FD0) [추정: 생성 경로]. 예 ATHN02A 정박한 배
	 * goshipgroup 갑판 = ATHN01F Warp 도착점 바로 아래 (43 §13.10).
	 */
	public static Platforms of(LevelData lv, Collection<String> owners, Collection<String> posed) {
		Platforms ps = new Platforms();
		for (LevelLayout.Placement p : lv.layout.placements()) {
			String name = p.instance().name();
			boolean scripted = owners.contains(name) || posed.contains(name);
			if (!scripted && !staticOwner(lv, p) || p.object().skeleton() == null) {
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
			body.scripted = scripted;
			// 시작 자세 = 인스턴스 시작 act 를 시작 시간에 (원작 GO 리셋 0x1309C0, StartPose) — Marker·hull 도 렌더와 같은 자세
			devple.gow.tools.level.StartPose sp = devple.gow.tools.level.StartPose.of(p, set);
			if (sp.act() != null) {
				body.joints = sp.jointWorld();
				body.prev = body.joints;
				if (!scripted && sp.act().looping() && sp.act().duration > 0 && sp.act().hasSkeletalData()) {
					// 정지 CDV 오브젝트의 반복 시작 act (렌더의 반복 재생 배치와 같은 규칙, LevelScene) — 충돌도 그 자세를 따른다 (HDES01C 죽음 굴림대 등)
					float d = sp.act().duration;
					body.anim = sp.act();
					body.time = ((sp.time() % d) + d) % d;
					body.loop = true;
					body.playing = true;
				}
			}
			ps.bodies.add(body);
		}
		return ps;
	}

	public List<Body> bodies() {
		return bodies;
	}

	/**
	 * 스크립트가 움직이지 않는 CDV 오브젝트 (정지 충돌): 평면 hull 이 있는 CDV 를 갖고, 다른 시스템이 맡는 비헤이비어 GO (깨지는 물건·상자·밀기·크랭크 등,
	 * {@code LevelData.behaviors}) 가 아닌 것.
	 */
	static boolean staticOwner(LevelData lv, LevelLayout.Placement p) {
		String name = p.instance().name();
		for (String b : lv.behaviors.keySet()) {
			if (b.equalsIgnoreCase(name)) {
				return false;
			}
		}
		for (String r : p.object().refs()) {
			BallHull h = lv.hulls.get(r);
			if (h != null && h.kind == 0) {
				for (BallHull.Hull hh : h.hulls) {
					if (hh.planes().size() >= 4) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/** 오브젝트 애니 상태 (이음 넘기기: 떠나온 레벨은 원작에서 다른 슬롯에 그대로 남는다, 43 §13.8). */
	public record State(String owner, String anim, float time, float rate, boolean playing, boolean loop, boolean pingPong, boolean solid) {
	}

	/** 애니가 한 번이라도 걸렸거나 충돌이 꺼진 (스크립트 Vis Attr11_0 "DoorCDOff" 등) 오브젝트의 상태. */
	public List<State> states() {
		List<State> out = new ArrayList<>();
		for (Body b : bodies) {
			if (b.anim != null || !b.solid) {
				out.add(new State(b.owner, b.anim != null ? b.anim.name : null, b.time, b.rate, b.playing, b.loop, b.pingPong, b.solid));
			}
		}
		return out;
	}

	/** {@link #states()} 를 되살린다 (같은 레벨의 새 Platforms). */
	public void restore(List<State> states) {
		for (State s : states) {
			Body b = body(s.owner());
			if (b == null) {
				continue;
			}
			b.solid = s.solid();
			if (b.anims == null || s.anim() == null) {
				continue;
			}
			Animation a = b.anims.find(s.anim());
			if (a == null) {
				continue;
			}
			b.anim = a;
			b.time = s.time();
			b.rate = s.rate();
			b.playing = s.playing();
			b.loop = s.loop();
			b.pingPong = s.pingPong();
			b.pose();
		}
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
			if (hh.planes().size() < 4 || !b.wallHull(h)) {
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
		long[] at = new long[1];
		for (Body b : bodies) {
			float t = b.top(x, y, z, drop, j, at);
			if (!Float.isNaN(t) && (best == null || t > best.y)) {
				best = new Support(t, b, j[0], at[0]);
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
		// 생성 스크립트가 이미 재생 중인 발판 (PAND05B 시작 엘리베이터: 생성 센서 → Animator "!0", t=0 윗면 281 = Start 282) 은 그 자세가
		// 도착점을 받치면 그대로 둔다 — 시간을 맞춰 멈추면 원작 하강 연출이 사라진다 (43 §13.8)
		for (Body b : bodies) {
			if (b.playing && b.anim != null && !Float.isNaN(b.top(p.x, p.y + tol, p.z, 2 * tol, j))) {
				return b;
			}
		}
		for (Body b : bodies) {
			if (!b.scripted && !Float.isNaN(b.top(p.x, p.y + tol, p.z, 2 * tol, j))) {
				return b; // 정지 CDV 오브젝트 바닥 (ATHN02A 정박한 배 갑판)
			}
		}
		for (Body b : bodies) {
			if (b.anims == null || b.playing || !b.scripted) {
				continue;
			}
			Animation a0 = b.anim; // 맞지 않으면 원래 상태로 (이음으로 넘겨받은 열린 문 등, 43 §13.8)
			float t0 = b.time;
			boolean p0 = b.playing;
			float[][] j0 = b.joints;
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
			b.anim = a0;
			b.time = t0;
			b.playing = p0;
			b.joints = a0 == null ? LevelLayout.jointWorld(b.skeleton, b.inst) : j0;
			b.prev = b.joints;
		}
		return null;
	}
}
