package devple.gow.engine.combat;

import devple.gow.engine.level.LevelCollision;
import devple.gow.engine.nav.HeroNavigator;
import devple.gow.engine.nav.NavRope;
import devple.gow.engine.nav.NavWorld;
import devple.gow.engine.nav.RopeRide;
import devple.gow.engine.nav.RopeSim;
import devple.gow.tools.level.SurfaceAttr;
import org.joml.Vector3f;

/**
 * 적 자신의 이동 상태 (원작 owner+0x174 하위 11 비트 — 영웅과 같은 공용 내비게이션 0x1EBD78, 32 문서 §적 자신의 벽·줄 이동).
 * <ul>
 *   <li>지상(1) / 공중(4·8) / 벽(0x20) / 줄(0x400). 결정 트리 조건 4 ({@link #conditionState()}) 와 전투 분기 상태 마스크
 *       ({@link #combatMask()}, Combat_StateMask 0x207DC0) 가 이 값을 본다.</li>
 *   <li>잡기: 공중에서 능력 Climb(AI1 0x38 &amp; 1) 이면 0x1C7828 벽 잡기, Rope|Pole|Zipline|0x800 이면 손에서 32 안의 줄 (0x1C5C20) [확실: 흐름·반경 /
 *       근사: 벽 판정 기하는 영웅 {@code HeroNavigator} 와 같은 정면 캐스트]. 벽·줄 옆에 스폰된 해골 -C 는 첫 프레임에 공중 → 잡기.</li>
 *   <li>지상 속도: 목표 = Sold+0x178 × |스틱| (m/s ×16), 가속 +0x16C / 감속 +0x170 (0x1EAEC8) [확실]. 스틱 = AI 이동 의도 (0x1CF9D0: AI1 0x68 %).</li>
 *   <li>벽: AI 목표를 벽 평면(좌우·상하)에 투영 (0x1CF5D0), 좌우 Sold+0x10C / 상하 +0x110 (0x1C8D34). 더 못 가면 AI1 0x8A ms 뒤 점프(놓기)
 *       (0x1CF894 → sub_001CF1C8 = 입력 +0x30 비트 1) [추정: 조건]. 아래 끝이 바닥이면 지상, 위 끝은 WallPullup 능력이면 올라섬.</li>
 *   <li>줄: 줄 방향으로 목표 쪽 이동 (Sold+0x158). 놓기 (0x1CFCE0–0x1CFEF4 → sub_001CF1C8): 수평 줄·짚라인은 목표가 16 넘게 아래이고 45° 보다 가파르면,
 *       매단 줄은 스틱 0 (흔들지 않음), 목표가 아래이고 정면 45° 안 + 매단 점에서 수평 16 넘게 떨어지면 점프 (영웅과 같은 0x1C5488 — 전역 0x29DBF8 &gt; 0.5 일 때만,
 *       위 Sold+0x140 · 앞 +0x144), 아니면 회전. 짚라인은 스틱 무관 낮은 끝으로 Sold+0x160/+0x164 (0x1C60F8), × 없음.
 *       손 = 줄 오브젝트 시뮬레이션 위 점 (영웅과 공용 {@link NavWorld#sim}, 양끝 고정 줄은 처짐). 분기 마스크 0x80 은 하위 0x20000 (수평 줄) 만 [확실].</li>
 *   <li>생물끼리 밀어내기 ({@link BodyPush}, 0x1E2DD8): 지상·공중·벽 이동 뒤, 벽 충돌 앞. 줄 위는 없음.</li>
 * </ul>
 * 충돌 세계가 없으면(헤드리스 AI 테스트) 지상 이동만 한다.
 */
public final class EnemyNav {

	public enum Mode { GROUND, AIR, WALL, ROPE }

	/** 원작 owner+0x174 상태 비트 (조건 4 = 0x1A1480, 상태 마스크 = 0x207DC0). */
	public static final int ST_GROUND = 0x1, ST_AIR = 0x4, ST_WALL = 0x20, ST_ROPE = 0x400;

	/** 줄 잡기 반경 (0x1C5CF0 lui 0x4200 = 32) [확실]. */
	public static final float ROPE_GRAB = 32f;
	/** 벽에서 몸 중심까지 / 가슴 높이 / 벽 탐색 거리 (영웅 HeroNavigator 와 같은 값) [근사]. */
	public static final float WALL_OFF = 10f, CHEST = 20f, WALL_REACH = 34f;
	/** 중력 = 원작 전역 /GlobGame/ "Gravity" 50 m/s² (영웅과 같은 값, 0x1EBC0C, 38 §2.2). */
	public static final float STEP = 14f, GRAVITY = devple.gow.engine.nav.NavTuning.GLOBAL_GRAVITY * devple.gow.engine.nav.NavTuning.UNITS_PER_METER;
	/** 줄에서 놓는 거리 (0x1CFED0 lui 0x4180 = 16) [확실: 값]. */
	public static final float ROPE_DROP = 16f;
	/** 같은 벽·줄 위 목표(영웅) 앞에서 멈추는 거리 — 벽·줄 공격 거리(DTre 0..56 / 16..48) 안 [근사]. */
	public static final float KEEP = 24f;

	/** 벽 캐스트 결과. normal 은 적 쪽을 향한 수평 법선. */
	public record WallHit(Vector3f point, Vector3f normal, boolean climbable) {
	}

	/** 줄 하위 상태 (owner+0x174, 0x1C5C20 → 0x1C5DA4: 줄 객체 +0x374 플래그) [확실]. */
	public static final int SUB_CHAIN = 0x10000, SUB_TRAVERSE = 0x20000, SUB_ZIPLINE = 0x40000;

	/**
	 * 줄 선분 (a → b) 과 잡았을 때의 하위 상태 sub. 줄 초기화 0x216DC4 가 SCR_RopeSkinned 플래그로 +0x374 를 정한다:
	 * f3 → 4, 아니면 f1(끝점 고정) → 1 (0x218488), f2 → |2. 잡기 0x1C5DA4: 4 → 0x10000, 1&amp;2 → 0x40000 (짚라인), 1 → 0x20000 (양끝 고정 수평 줄),
	 * 그 밖(매단 줄) → 하위 비트 없음 (+0x2D8 |= 0x2004000) [확실].
	 */
	public record Rope(Vector3f a, Vector3f b, int sub, Vector3f axis, int dirs, NavRope src) {
		/** 줄 오브젝트 없음 (데이터 없는 테스트용 — 곧은 선분). */
		public Rope(Vector3f a, Vector3f b, int sub, Vector3f axis, int dirs) {
			this(a, b, sub, axis, dirs, null);
		}

		/** 하위 상태 미지정: 수평이면 수평 줄(0x20000), 아니면 매단 줄 (데이터 없는 테스트용). */
		public Rope(Vector3f a, Vector3f b) {
			this(a, b, horizontal(a, b) ? SUB_TRAVERSE : 0);
		}

		/** 줄 축 +z, 4 방향 (데이터 없는 테스트용). */
		public Rope(Vector3f a, Vector3f b, int sub) {
			this(a, b, sub, new Vector3f(0, 0, 1), 0);
		}

		private static boolean horizontal(Vector3f a, Vector3f b) {
			Vector3f d = new Vector3f(b).sub(a);
			return Math.abs(d.y) < 0.5f * d.length();
		}

		public boolean horizontal() {
			Vector3f d = new Vector3f(b).sub(a);
			return Math.abs(d.y) < 0.5f * d.length();
		}

		float closest(Vector3f p) {
			Vector3f d = new Vector3f(b).sub(a);
			float l2 = d.lengthSquared();
			return l2 < 1e-6f ? 0 : Math.max(0, Math.min(1, new Vector3f(p).sub(a).dot(d) / l2));
		}

		Vector3f point(float t) {
			return new Vector3f(a).lerp(b, Math.max(0, Math.min(1, t)));
		}

		float length() {
			return a.distance(b);
		}
	}

	/** 적 이동에 필요한 충돌 질의. */
	public interface World {
		/** 점 (x, y, z) 아래 drop 안의 바닥 높이 (없으면 NaN). */
		float ground(float x, float y, float z, float drop);

		/** 반직선이 처음 만나는 벽 (없으면 null). */
		WallHit wall(Vector3f origin, Vector3f dir, float dist);

		/** 점에서 r 안의 가장 가까운 줄 (없으면 null). */
		Rope rope(Vector3f p, float r);

		/** 구 (중심 c, 반지름 r) 벽 밀어내기 — c 를 고치고 밀렸으면 true (원작 이동 단계의 벽 충돌 0x1E2100). 기본 = 없음. */
		default boolean collide(Vector3f c, float r) {
			return false;
		}

		/** {@link #collide(Vector3f, float)} 를 몸 질의 면 제외 마스크로 ({@link NavWorld#bodyExclude}). 기본 = 마스크 무시. */
		default boolean collide(Vector3f c, float r, long exclude) {
			return collide(c, r);
		}

		/** 줄 오브젝트의 질점 시뮬레이션 (영웅과 같은 것, {@link NavWorld#sim}) — 없으면 곧은 선분. */
		default RopeSim sim(NavRope r) {
			return null;
		}

		/** 원작 전역 0x29DBF8 (줄 잡은 뒤 시간 — 플레이어의 줄 갱신만 더함, {@link NavWorld#ropeClock}). 기본 0 = 영웅이 줄에 없음. */
		default float ropeClock() {
			return 0;
		}

		/** 줄을 잡음 → 0x29DBF8 = 0 (0x1C5D98, 누가 잡든). */
		default void ropeGrabbed() {
		}
	}

	/** 게임 충돌 세계 (정적 충돌 + 발판 + 줄) 어댑터. 벽 = 영웅 충돌 면, Climbable = 표면 비트 3 (38 문서 §1). */
	public static World of(NavWorld nw) {
		return new World() {
			public float ground(float x, float y, float z, float drop) {
				return nw.groundHeight(x, y, z, drop);
			}

			public WallHit wall(Vector3f o, Vector3f dir, float dist) {
				LevelCollision.Hit h = nw.wallcast(o, dir, dist);
				if (h == null) {
					return null;
				}
				Vector3f n = new Vector3f(h.tri().n()).setComponent(1, 0);
				if (n.lengthSquared() < 1e-6f) {
					n.set(dir).negate().setComponent(1, 0);
				}
				n.normalize();
				if (n.dot(dir) > 0) {
					n.negate();
				}
				return new WallHit(h.point(), n, h.tri().has(SurfaceAttr.CLIMBABLE));
			}

			public Rope rope(Vector3f p, float r) {
				NavRope nr = nw.nearestRope(p, r);
				if (nr == null) {
					return null;
				}
				// RopeDef 분류 = 원작 +0x374: ZIPLINE (f2) → 0x40000, TRAVERSE (f1 만) → 0x20000, HANG → 0
				// (f3 짧은 사슬 → 0x10000 은 NavRope 에 플래그가 없어 HANG 과 같이 0 [근사: 분기 마스크·AI 분기는 같음])
				int sub = switch (nr.type()) {
					case ZIPLINE -> SUB_ZIPLINE;
					case TRAVERSE -> SUB_TRAVERSE;
					case HANG -> 0;
				};
				return new Rope(nr.top(), nr.end(), sub, nr.axis(), nr.dirs(), nr);
			}

			public boolean collide(Vector3f c, float r) {
				return collide(c, r, bodyExclude(false, -1));
			}

			public boolean collide(Vector3f c, float r, long exclude) {
				return nw.collideSphere(c, r, exclude);
			}

			public RopeSim sim(NavRope r) {
				return nw.sim(r);
			}

			public float ropeClock() {
				return nw.ropeClock();
			}

			public void ropeGrabbed() {
				nw.ropeGrabbed();
			}
		};
	}

	private final int caps;
	private final AiData.Motion motion;
	private final int jumpDelayMs;
	private AiData.Body body = AiData.Body.HERO;
	private float scale = 1f;
	private AiData.Ride ride = AiData.Ride.DEFAULT;
	/** 생물끼리 밀어내기 (0x1E2DD8): 자기 몸과 주변 생물 (null = 없음). */
	private BodyPush.Body self;
	private java.util.function.Supplier<? extends Iterable<? extends BodyPush.Body>> crowd;
	private World world;
	private Mode mode = Mode.GROUND;
	private float speed;
	private final Vector3f heading = new Vector3f(0, 0, -1);
	private float vy;
	private final Vector3f wallN = new Vector3f();
	private Rope rope;
	private float ropeT;
	/** 매단 줄 위 몸 방향 (yaw) — AI 회전 입력 (1,0,0) 으로 대상 쪽으로 돈다 (0x1CFEFC) [추정]. */
	private float ropeYaw;
	/** 매단 줄 위 몸 방향 번호 (NavRope.backDirs) 와 보간 가중치 (+0x150) — 영웅과 같은 코드 0x1C6858 / 0x1E0DC0. */
	private int ropeDir;
	private float turnBlend = 1;
	/** 붙은 줄 시뮬레이션 (영웅과 공용, null = 곧은 선분) 과 매단 줄 흔들기 상태 (0x1C6BC8 — 적은 잡을 때 창의 한 번만). */
	private RopeSim sim;
	private final RopeRide hangRide = new RopeRide();
	/** 짚라인: 진행 방향 (+1 = a → b) / 속도 (월드 단위/s). */
	private float zipDir, zipSpeed;
	private float stuckMs;
	/** 벽·줄을 놓은 뒤 착지할 때까지 다시 잡지 않음 [근사: 원작 놓기 직후 상태 미분석]. */
	private boolean noCatch;
	/** 이번 틱 결과: 재생할 이동 애니 키 ("idle", "move", "ANIClimbIdle", ...) / 바라볼 yaw (NaN = AI 에 맡김) / 벽·줄 위 이동 방향 (−1..1). */
	private float faceYaw = Float.NaN;
	private float moveX, moveY;
	private boolean released;

	/**
	 * @param caps        AI1 0x38 이동 능력
	 * @param motion      Sold 속도 (null = 기본)
	 * @param jumpDelayMs AI1 0x8A
	 */
	public EnemyNav(int caps, AiData.Motion motion, int jumpDelayMs) {
		this.caps = caps;
		this.motion = motion != null ? motion : AiData.Motion.DEFAULT;
		this.jumpDelayMs = jumpDelayMs;
	}

	/** 몸 상자 (Sold +0x234/+0x238/+0x23C) 와 개체 스케일 (owner+0x168) [추정: 스케일 = AI1 Scale Center/Range]. */
	public void setBody(AiData.Body b, float scale) {
		if (b != null) {
			body = b;
			noPush = b.noPush();
		}
		this.scale = scale;
	}

	/**
	 * owner+0x2D8 비트 31 — 자기 이동에서 생물 밀어내기 0x1E2DD8 을 부르지 않음 (0x1EA664 / 0x1ECCD8 / 0x1ECDF8) [확실].
	 * 출처: Sold +0x1C 비트 1 (초기화 0x1E77F0, {@link AiData.Body#noPush}) 또는 클래스 갱신 (BMI Think 0x1C0A48 이 매 프레임 세움).
	 * 다른 생물에게는 여전히 단단하다 (콜백 0x1E28B0 은 상대의 이 비트를 보지 않음).
	 */
	private boolean noPush;

	public void setNoPush(boolean v) {
		noPush = v;
	}

	public boolean noPush() {
		return noPush;
	}

	/** 줄·짚라인·점프 Sold 필드. */
	public void setRide(AiData.Ride r) {
		if (r != null) {
			ride = r;
		}
	}

	/** 생물끼리 밀어내기 대상: self = 이 적의 몸, others = 주변 생물 (자신 포함 가능 — 건너뜀). */
	public void setCrowd(BodyPush.Body self, java.util.function.Supplier<? extends Iterable<? extends BodyPush.Body>> others) {
		this.self = self;
		this.crowd = others;
	}

	/** 캡슐 반지름 (월드). */
	public float bodyRadius() {
		return body.half() * 16f * scale;
	}

	/** 키 (월드). */
	public float bodyHeight() {
		return body.height() * 16f * scale;
	}

	/**
	 * 줄 손 높이 = 키 (Sold +0x238 × 16 × 스케일, 내비 +0x120.y) [확실]: 잡기 0x1C5C20 이 위치 + 위 × +0x120.y 로 줄을 찾고 (0x1C5C68),
	 * 줄 위 몸은 줄 점 (0x2184E8) − 위 × +0x120.y (0x1C66E4 – 0x1C66F4) — 영웅 {@link HeroNavigator#ROPE_HAND} 35.2 와 같은 식.
	 */
	public float hand() {
		return bodyHeight();
	}

	/** 바닥 높이 (월드). */
	public float bodyBase() {
		return body.base() * 16f * scale;
	}

	public void setWorld(World w) {
		world = w;
		if (w != null && mode == Mode.GROUND) {
			mode = Mode.AIR; // 스폰 직후: 바닥을 찾을 때까지 공중 (벽·줄 옆 스폰 → 잡기)
		}
	}

	public World world() {
		return world;
	}

	public Mode mode() {
		return mode;
	}

	public int caps() {
		return caps;
	}

	public AiData.Motion motion() {
		return motion;
	}

	/** 현재 지상 속도 (월드 단위/s). */
	public float speed() {
		return speed;
	}

	public boolean grounded() {
		return mode == Mode.GROUND;
	}

	public Vector3f wallNormal() {
		return wallN;
	}

	public Rope rope() {
		return rope;
	}

	/** 원작 owner+0x174 상태 비트 (줄은 하위 상태 0x10000/0x20000/0x40000 포함). */
	public int stateBits() {
		return switch (mode) {
			case GROUND -> ST_GROUND;
			case AIR -> ST_AIR;
			case WALL -> ST_WALL;
			case ROPE -> ST_ROPE | (rope != null ? rope.sub() : 0);
		};
	}

	/** 결정 트리 조건 4/5 값 (0x1A1480): 0 지상, 1 벽(0x20), 2 공중(0x4), 3 줄(0x400). */
	public int conditionState() {
		return switch (mode) {
			case GROUND -> 0;
			case WALL -> 1;
			case AIR -> 2;
			case ROPE -> 3;
		};
	}

	/**
	 * 전투 분기 허용 상태 마스크 (Combat_StateMask 0x207DC0): 지상 0x2, 공중 0x4, 벽 0x20, 줄 0x80.
	 * 줄은 **하위 상태 0x20000 (양끝 고정 수평 줄) 일 때만 0x80**, 매단 줄·짚라인·사슬은 0 (상태 조건 없는 분기만) [확실: 0x207E20].
	 */
	public int combatMask() {
		return switch (mode) {
			case GROUND -> 0x2;
			case AIR -> 0x4;
			case WALL -> 0x20;
			case ROPE -> rope != null && rope.sub() == SUB_TRAVERSE ? 0x80 : 0;
		};
	}

	/** 바라볼 yaw (벽: 벽 쪽, 줄: 진행 방향). NaN = AI 의 face 를 따름. */
	public float faceYaw() {
		return faceYaw;
	}

	/** 벽·줄 위 이동 방향 (오른쪽 +x, 위 +y; 줄은 x 만). */
	public float moveX() {
		return moveX;
	}

	public float moveY() {
		return moveY;
	}

	/** 이번 틱에 벽·줄을 놓았는지. */
	public boolean released() {
		return released;
	}

	/**
	 * 벽·줄에서 떨어진다. 죽으면 부름 — 원작 내비게이션 상태 종료 (0x1EBD78 a1 = 4, 0x1EBE2C): 줄이면 놓고 (0x1C4FF8),
	 * 지상이 아니면 0x1E9030(2) = 낙하 (+0x174 = 8, 속도 유지, HP ≤ 0 이면 사망 기술은 그대로 재생) [확실: 경로 / 추정: 사망 때 a1 = 4].
	 * 피격 반응(wallHit*·ropeHOHR)은 붙은 채 — CombatFile 데이터 (32 문서).
	 */
	public void drop() {
		if (mode == Mode.WALL || mode == Mode.ROPE) {
			mode = Mode.AIR;
			vy = 0;
			speed = 0;
			rope = null;
			if (sim != null) {
				sim.detach(); // 0x218270: 무게는 줄어들며 줄은 계속 흔들림
				sim = null;
			}
			released = true;
			noCatch = true;
		}
	}

	/** 점프 버튼으로 놓기 (0x1CF1C8 → 줄/짚라인 점프): 수평 dir × hs, 수직 vy. */
	private void jumpOff(Vector3f dir, float hs, float up) {
		drop();
		if (dir.lengthSquared() > 1e-6f) {
			heading.set(dir.x, 0, dir.z).normalize();
		}
		speed = hs;
		vy = up;
	}

	/**
	 * 한 틱.
	 *
	 * @param pos   발 위치 (갱신됨)
	 * @param stick AI 이동 의도 (수평, 크기 0..1 = 속도 배율)
	 * @param goal  AI 목표 점 (벽·줄 투영용, null = 없음)
	 * @param busy  기술 중 (지상 이동 없음, 벽·줄은 유지)
	 */
	public void update(Vector3f pos, Vector3f stick, Vector3f goal, boolean busy, float dt) {
		update(pos, stick, goal, null, busy, dt);
	}

	/**
	 * @param goalClinging 목표(대상)가 같은 종류의 벽·줄에 붙어 있는지 (대상 조건 5 값). null 이면 목표 위치로 판단.
	 *                     참이면 벽·줄 위에서 {@link #KEEP} 앞에서 멈추고 놓지 않는다.
	 */
	public void update(Vector3f pos, Vector3f stick, Vector3f goal, Boolean goalClinging, boolean busy, float dt) {
		faceYaw = Float.NaN;
		moveX = moveY = 0;
		released = false;
		switch (mode) {
			case WALL -> wall(pos, goal, goalClinging, busy, dt);
			case ROPE -> rope(pos, goal, goalClinging, busy, dt);
			default -> ground(pos, busy ? new Vector3f() : stick, dt);
		}
	}

	// ------------------------------------------------------------------ 지상 / 공중

	/**
	 * 자유 이동 기술 중 (기술 stateMask &amp; 0x1C == 0 — 처형 MAI* 등 stateMask 1/3): 원작 0x1EBD78 은 이 때 지상/공중 이동 (0x1EAEC8 /
	 * 0x1EB3D8, 중력) 을 부르지 않고 0x1E5F90 (위치 + 루트 모션 변위 3D) → 0x1EA200 (밀어내기는 stateMask 비트 2 일 때만, 벽 0x1E2100,
	 * 바닥 0x1E3298 — 바닥 아래로만 막음) [확실: 호출 경로 / 바닥 처리 세부는 근사]. 루트 모션은 {@link EnemyFighter} 가 더한다.
	 */
	public boolean free;

	private void free(Vector3f pos) {
		speed = 0;
		vy = 0;
		if (world == null) {
			return;
		}
		if (pushInFree) {
			crowdPush(pos, null);
		}
		float r = bodyRadius();
		Vector3f c = new Vector3f(pos.x, pos.y + STEP + r, pos.z);
		if (world.collide(c, r, bodyExclude(true, moveFlags))) { // 0x1EA200
			pos.x = c.x;
			pos.z = c.z;
		}
		float g = world.ground(pos.x, pos.y + STEP, pos.z, 4000);
		if (!Float.isNaN(g) && pos.y < g) {
			pos.y = g; // 바닥 아래로는 못 감
		}
	}

	/** 자유 이동 중 밀어내기 (stateMask 비트 2, 0x1ECDEC). */
	public boolean pushInFree;
	/** 지금 전투 기술 flags (+0x0E, 기술이 없으면 −1) — 몸 질의 마스크 ({@link #bodyExclude(boolean, int)}). */
	public int moveFlags = -1;

	/**
	 * 적 몸 질의 면 제외 마스크 ({@link NavWorld#bodyExclude}, 플레이어 아님 → +0x258 0xA00000 &amp; 0x4300000 = NoAICollision,
	 * CombatGuide 는 늘 지나감, ClimbGuide 는 지상·공중에서 지나감) [확실: 0x1EA200 / 0x1EA5F0 공용].
	 */
	static long bodyExclude(boolean free, int moveFlags) {
		return NavWorld.bodyExclude(false, free, false, moveFlags >= 0, Math.max(moveFlags, 0), 0);
	}

	private void ground(Vector3f pos, Vector3f stick, float dt) {
		if (free) {
			free(pos);
			return;
		}
		if (mode == Mode.GROUND || world == null) {
			float mag = Math.min(1f, (float) Math.hypot(stick.x, stick.z));
			if (mag > 1e-3f) {
				heading.set(stick.x, 0, stick.z).normalize();
			}
			// 0x1EAEC8: 목표 속도 = Sold+0x178 × min(1, |스틱|) × 16, 가속 +0x16C / 감속 +0x170 (×16) — sub_001E5FE0 접근
			float target = motion.ground() * 16f * mag;
			float rate = (target > speed ? motion.accel() : motion.decel()) * 16f * dt;
			speed = target > speed ? Math.min(target, speed + rate) : Math.max(target, speed - rate);
		}
		pos.fma(speed * dt, heading); // 공중은 수평 속도 유지 (공중 조종 [근사])
		if (world == null) {
			mode = Mode.GROUND;
			float y = pos.y;
			crowdPush(pos, null);
			pos.y = y; // 세계 없음 = 지상만: 바닥 붙이기 (0x1E3298) 대신 높이 유지 [근사]
			return;
		}
		crowdPush(pos, null);
		// 벽 충돌 (0x1E2100, 생물 밀어내기 뒤): 허리 높이 구 [근사: 원작은 상자 모서리 레이]
		float r = bodyRadius();
		Vector3f c = new Vector3f(pos.x, pos.y + STEP + r, pos.z); // 구 아래 = 계단 높이 (턱은 넘음)
		if (world.collide(c, r, bodyExclude(false, moveFlags))) { // 0x1EA5F0
			pos.x = c.x;
			pos.z = c.z;
		}
		float g = world.ground(pos.x, pos.y + STEP, pos.z, 4000);
		if (!Float.isNaN(g) && pos.y - g <= STEP && vy <= 0) {
			pos.y = g;
			vy = 0;
			mode = Mode.GROUND;
			noCatch = false;
			return;
		}
		if (mode == Mode.GROUND && !Float.isNaN(g)) {
			mode = Mode.AIR; // 턱에서 떨어짐
		}
		if (mode == Mode.AIR) {
			if ((!noCatch && tryCatch(pos)) || Float.isNaN(g)) {
				return; // 아래에 바닥이 없으면 GameScene 과 같이 높이 유지
			}
			vy -= GRAVITY * dt;
			pos.y += vy * dt;
			if (!Float.isNaN(g) && pos.y <= g) {
				pos.y = g;
				vy = 0;
				mode = Mode.GROUND;
				noCatch = false;
			}
		}
	}

	/** 생물끼리 밀어내기 (0x1E2DD8). wallNormal = 벽 오르기 중이면 벽 법선. */
	private void crowdPush(Vector3f pos, Vector3f wallNormal) {
		if (self != null && crowd != null && !noPush) {
			BodyPush.push(self, pos, crowd.get(), wallNormal);
		}
	}

	/** 공중 잡기: 줄 (0x1C5C20, 공중 0x4C 상태에서만) → 벽 (0x1EBCE4 → 0x1C7828, 능력 Climb). */
	private boolean tryCatch(Vector3f pos) {
		if ((caps & AiTemplate.CAP_ANY_ROPE) != 0) {
			Vector3f hand = new Vector3f(pos).add(0, hand(), 0);
			Rope r = world.rope(hand, ROPE_GRAB);
			if (r != null) {
				rope = r;
				ropeT = r.closest(hand);
				mode = Mode.ROPE;
				ropeYaw = (float) Math.atan2(-heading.x, -heading.z);
				ropeDir = NavRope.bestDir(r.axis(), r.dirs(), new Vector3f((float) Math.sin(ropeYaw), 0, (float) Math.cos(ropeYaw)));
				turnBlend = HeroNavigator.TURN_BLEND_START;
				// 짚라인: 낮은 끝 쪽으로 (0x1C5E04), 잡을 때 줄 방향 속도 0 [추정 — 영웅과 같음]
				zipDir = r.b().y <= r.a().y ? 1 : -1;
				zipSpeed = 0;
				vy = 0;
				speed = 0;
				world.ropeGrabbed(); // 0x1C5D98: 0x29DBF8 = 0
				// 줄 오브젝트에 매달림 (0x218200 / 0x2183B0): 영웅과 같은 시뮬레이션 — 양끝 고정 줄은 무게 10 으로 처지고 손 = 줄 위 점 (0x2184E8)
				sim = r.src() != null ? world.sim(r.src()) : null;
				if (sim != null) {
					sim.attach(ropeT * sim.length());
					ropeT = sim.dist() / sim.length();
				}
				hangRide.grab(new Vector3f((float) -Math.sin(ropeYaw), 0, (float) -Math.cos(ropeYaw)), r.src() != null && r.src().shortChain());
				snapRope(pos);
				return true;
			}
		}
		if ((caps & AiTemplate.CAP_CLIMB) != 0) {
			WallHit best = null;
			Vector3f o = new Vector3f(pos).add(0, CHEST, 0);
			for (int k = 0; k < 8; k++) {
				double a = Math.PI * 2 * k / 8;
				Vector3f d = new Vector3f((float) Math.sin(a), 0, (float) Math.cos(a));
				WallHit h = world.wall(o, d, WALL_REACH);
				if (h != null && h.climbable() && (best == null || h.point().distanceSquared(o) < best.point().distanceSquared(o))) {
					best = h;
				}
			}
			if (best != null) {
				mode = Mode.WALL;
				vy = 0;
				speed = 0;
				stuckMs = 0;
				wallN.set(best.normal());
				pos.set(best.point().x + wallN.x * WALL_OFF, pos.y, best.point().z + wallN.z * WALL_OFF);
				faceWall();
				return true;
			}
		}
		return false;
	}

	// ------------------------------------------------------------------ 벽

	private void faceWall() {
		faceYaw = (float) Math.atan2(wallN.x, wallN.z); // 정면(−Z 회전) = −법선 → 벽을 봄
	}

	/** 벽 위 밀어내기: 벽 평면 성분만 (0x1E2CA8), 민 자리도 Climbable 이어야 함 (벽 충돌 0x1E2100 대신 [근사]). */
	private void wallPush(Vector3f pos) {
		if (self == null || crowd == null || world == null || noPush) {
			return;
		}
		Vector3f p = new Vector3f(pos);
		if (!BodyPush.push(self, p, crowd.get(), wallN)) {
			return;
		}
		WallHit h = world.wall(new Vector3f(p).add(0, CHEST, 0).fma(8, wallN), new Vector3f(wallN).negate(), WALL_OFF + 24);
		if (h != null && h.climbable()) {
			pos.set(h.point().x + wallN.x * WALL_OFF, p.y, h.point().z + wallN.z * WALL_OFF);
		}
	}

	private void wall(Vector3f pos, Vector3f goal, Boolean clinging, boolean busy, float dt) {
		faceWall();
		if (busy || goal == null || world == null) {
			wallPush(pos);
			return;
		}
		// 0x1CF5D0: 목표 방향을 벽 평면(오른쪽·위)에 투영, 정지 거리 = Sold+0x108 ×16 / 60
		Vector3f right = new Vector3f(wallN).negate().cross(0, 1, 0).normalize();
		Vector3f d = new Vector3f(goal).sub(pos);
		float sx = d.dot(right), sy = d.y;
		float len = (float) Math.hypot(sx, sy);
		boolean goalOnWall = clinging != null ? clinging : Math.abs(d.dot(wallN)) < WALL_OFF + 16;
		if (len < (goalOnWall ? KEEP : Math.max(4f, motion.wallMin() * 16f / 60f))) {
			stuckMs = 0;
			wallPush(pos);
			return;
		}
		sx /= len;
		sy /= len;
		// 0x1C8D34: 좌우 Sold+0x10C, 상하 +0x110 (×16)
		float vx = sx * motion.wallSide() * 16f, vyw = sy * motion.wallUp() * 16f;
		Vector3f cand = new Vector3f(pos).fma(vx * dt, right).add(0, vyw * dt, 0);
		WallHit h = world.wall(new Vector3f(cand).add(0, CHEST, 0).fma(8, wallN), new Vector3f(wallN).negate(), WALL_OFF + 24);
		moveX = sx;
		moveY = sy;
		float prevY = pos.y;
		if (h != null && h.climbable()) {
			wallN.set(h.normal());
			pos.set(h.point().x + wallN.x * WALL_OFF, cand.y, h.point().z + wallN.z * WALL_OFF);
			faceWall();
			stuckMs = 0;
			wallPush(pos);
		} else {
			if (sy > 0 && (caps & AiTemplate.CAP_WALL_PULLUP) != 0) {
				// 꼭대기 (ANIWallPullup): 벽 너머 바닥으로 올라선다
				Vector3f over = new Vector3f(pos).fma(-(WALL_OFF + 16), wallN);
				float top = world.ground(over.x, pos.y + CHEST + 50, over.z, 90);
				if (!Float.isNaN(top) && top > pos.y - 4) {
					pos.set(over.x, top, over.z);
					mode = Mode.GROUND;
					released = true;
					return;
				}
			}
			stuckMs += dt * 1000f;
			moveX = moveY = 0;
		}
		if (sy < 0) {
			// 아래 끝: 이번 틱에 지난 바닥 (갑판을 뚫고 내려가지 않게)
			float g = world.ground(pos.x, prevY + 2, pos.z, prevY - pos.y + 6);
			if (!Float.isNaN(g)) {
				pos.y = g; // 아래 끝 = 바닥 → 지상
				mode = Mode.GROUND;
				released = true;
				return;
			}
		}
		if (stuckMs >= jumpDelayMs) {
			// 더 갈 수 없음 → AI1 0x8A ms 뒤 점프 버튼 (0x1CF894) → 벽 점프 (영웅 벽 점프와 같은 밀어내기 [근사])
			Vector3f n = new Vector3f(wallN);
			drop();
			heading.set(n);
			speed = 70f;
			stuckMs = 0;
		}
	}

	// ------------------------------------------------------------------ 줄

	private void snapRope(Vector3f pos) {
		Vector3f p;
		if (sim != null) {
			// 손 = 시뮬레이션 줄 위 붙은 거리의 점 (0x2184E8), 붙은 거리 = 줄 위치 (0x2183B0) — 영웅 placeOnRope 와 같음
			if (rope.sub() == SUB_TRAVERSE || rope.sub() == SUB_ZIPLINE) {
				sim.moveAttach(ropeT * sim.length());
			}
			p = sim.pointAt(sim.dist(), new Vector3f());
		} else {
			p = rope.point(ropeT);
		}
		pos.set(p.x, p.y - hand(), p.z);
	}

	/**
	 * 매단 줄 위 회전 (원작 내비게이션 0x1C6858, 영웅과 공용) [확실]: 몸 방향은 줄 축 4 방향 (방향 제한 줄은 2) 중 하나로만 있고,
	 * AI 회전 입력 (1, 0, 0, 1) 의 x · w = 1 &gt; 0.5 → 몸 보간 (+0x150) 이 1 일 때 번호 + 1 (90°). 보간은 0.1 × 1.1111^프레임 (0x1E0DC0) 이라
	 * 22 프레임 (0.37 초) 에 한 번 돌고, 보이는 몸은 그동안 slerp 로 따라간다 → 90° 당 0.37 초 (평균 ≈ 245 °/초, 전 근사 π rad/s 폐기).
	 * 방향 제한 줄은 AI 스틱 (0) 에 가장 가까운 후보 = 첫 후보로만 간다.
	 */
	public static final int TURN_FRAMES = 22;
	/** 짚라인 끝 여유 0.25 m (0x1C6B1C, 영웅과 같음). */
	public static final float ZIP_END = 4f;
	private static final float COS45 = 0.70710677f;

	/** 매단 줄 위 몸 방향 yaw (정면 = (−sin, 0, −cos)). */
	public float ropeYaw() {
		return ropeYaw;
	}

	private void rope(Vector3f pos, Vector3f goal, Boolean clinging, boolean busy, float dt) {
		Vector3f dir = new Vector3f(rope.b()).sub(rope.a());
		float len = dir.length();
		if (len < 1e-3f) {
			drop();
			return;
		}
		dir.div(len);
		Vector3f flat = new Vector3f(dir.x, 0, dir.z);
		if (rope.sub() == SUB_ZIPLINE) {
			zip(pos, goal, clinging, dir, len, dt);
			return;
		}
		if (rope.sub() != SUB_TRAVERSE) {
			faceYaw = ropeYaw;
		}
		if (sim != null && rope.sub() != SUB_TRAVERSE && !(rope.src() != null && rope.src().shortChain())) {
			// 매단 줄: 매 프레임 매달림 힘 (0x1C71A8 → 0x218470). AI 는 흔들기 버튼을 내지 않으므로 잡을 때 0.25 초 창만 민다 [확실]
			sim.setForce(hangRide.force(sim, NavRope.backDirs(rope.axis())[ropeDir], false, HeroNavigator.SWING_FORCE, dt));
		}
		if (busy || goal == null) {
			snapRope(pos);
			return;
		}
		// 0x1CF9D0 줄 분기 (0x1CFCE0–0x1CFEF4) [확실: 흐름 / 근사: 위치 기준점 = 발]
		float tGoal = rope.closest(goal);
		Vector3f gp = rope.point(tGoal);
		float along = (tGoal - ropeT) * len;
		float flatGoal = (float) Math.hypot(goal.x - gp.x, goal.z - gp.z);
		boolean goalOnRope = clinging != null ? clinging : goal.y > gp.y - hand() - STEP && flatGoal < ROPE_GRAB;
		Vector3f d = new Vector3f(goal).sub(pos);
		float dl = d.length();
		if (rope.sub() == SUB_TRAVERSE) {
			// 수평 줄: 스틱 = 목표 방향 그대로. 목표가 16 넘게 아래(Δy < −16)이고 방향이 45° 보다 가파르면 (dir.y < −cos45) 점프 버튼 → 놓음
			if (!goalOnRope && d.y < -ROPE_DROP && dl > 1e-3f && d.y / dl < -COS45) {
				drop();
				return;
			}
		} else {
			hang(pos, goal, goalOnRope, dt);
			return;
		}
		if (Math.abs(along) > (goalOnRope ? KEEP : 2f)) {
			float s = Math.signum(along);
			float step = Math.min(Math.abs(along), motion.rope() * 16f * dt);
			ropeT = Math.max(0, Math.min(1, ropeT + s * step / len));
			moveX = s;
			if (flat.lengthSquared() > 1e-4f) {
				faceYaw = (float) Math.atan2(-flat.x * s, -flat.z * s);
			}
		}
		snapRope(pos);
	}

	/**
	 * 매단 줄·사슬 (하위 비트 없음 / 0x10000) — AI 0x1CFD90–0x1CFF20 [확실: 흐름·값 / 추정: 출력 a3 = 회전 입력]:
	 * 이동 스틱 = 0 (오르내리기·흔들기 입력 없음 — 매단 줄 오르기 Sold+0x138 은 스틱 × 이므로 제자리). 목표가 자신보다 아래일 때만
	 * <ol>
	 *   <li>목표 쪽 (수평) 방향 · 몸 정면 (+0x20 행) &gt; cos45 (0x3F34FDF4) 이면 — 줄 매단 점 (줄 객체 행렬 +0x30) 에서 목표까지 수평 거리 &gt; 16 이면
	 *       점프 버튼 (0x1CF1C8) → 놓기, 아니면 그대로.</li>
	 *   <li>정렬이 아니면 두 번째 출력 = (1, 0, 0, 1) — 회전 입력으로 보고 대상 쪽으로 돈다 [추정].</li>
	 * </ol>
	 * 대상도 줄 (조건 5) 이면 매달린 채 (KEEP 과 같은 뜻).
	 */
	private void hang(Vector3f pos, Vector3f goal, boolean goalOnRope, float dt) {
		snapRope(pos);
		if (goalOnRope || goal.y >= pos.y) {
			turnRope(dt);
			return;
		}
		float gx = goal.x - pos.x, gz = goal.z - pos.z;
		float gl = (float) Math.hypot(gx, gz);
		float fx = (float) -Math.sin(ropeYaw), fz = (float) -Math.cos(ropeYaw);
		float dot = gl < 1e-3f ? 1f : (gx * fx + gz * fz) / gl;
		if (dot > COS45) {
			turnRope(dt);
			float flatTop = (float) Math.hypot(goal.x - rope.a().x, goal.z - rope.a().z);
			if (flatTop > ROPE_DROP && world.ropeClock() > HeroNavigator.ROPE_JUMP_DELAY) {
				ropeJump(new Vector3f(fx, 0, fz));
			}
			return;
		}
		// 정렬이 아니면 회전 입력 (1, 0, 0, 1): 이전 회전이 끝났을 때 (+0x150 = 1) 번호 + 1 (방향 제한 줄 = 스틱 0 에 가장 가까운 첫 후보)
		if (turnBlend >= 1) {
			int next = rope.dirs() == 0 ? (ropeDir + 1) & 3 : NavRope.bestDir(rope.axis(), rope.dirs(), new Vector3f());
			if (next != ropeDir) {
				ropeDir = next;
				turnBlend = HeroNavigator.TURN_BLEND_START;
			}
		}
		turnRope(dt);
		moveX = 1; // 돌고 있음 (애니 ANIRopeU 대신 회전 [근사])
	}

	/**
	 * 점프 버튼 (0x1CF1C8 → 입력 +0x30 비트 1 = ×) → 줄 갱신의 0x1C5488 — 영웅과 같은 함수 [확실: 디스어셈블].
	 * <ul>
	 *   <li>0.5 초 조건 = 전역 0x29DBF8 &gt; 0.5. 이 시계는 누가 잡든 0 이 되고 (0x1C5D98) <b>플레이어 (+0x174 비트 31, 0x1E7E20 의 t0 = 1 은 영웅 생성 0x1D6368 뿐)
	 *       의 줄 갱신만 더한다</b> (0x1C54B4) → 적이 매단 줄을 잡으면 영웅이 줄에 0.5 초 넘게 있을 때까지 × 가 먹지 않는다.</li>
	 *   <li>정면 = 줄 축 후보 중 −(+0x2E0) 쪽 — AI 스틱 0 이면 +0x2E0 = 지금 정면.</li>
	 *   <li>속도 (+0xE0 덮어씀) = 위 × Sold+0x140 + 정면 × Sold+0x144 (적 Sold 53 개 모두 14 / 14 → 224 / 224), 줄에 반작용.</li>
	 * </ul>
	 */
	private void ropeJump(Vector3f facing) {
		Vector3f face = RopeRide.jumpFace(rope.axis(), rope.dirs(), facing);
		Vector3f v = RopeRide.jumpVelocity(face, ride.ropeJumpUp() * 16f, ride.ropeJumpOut() * 16f);
		if (sim != null) {
			sim.setForce(RopeRide.kick(sim, v));
		}
		ropeYaw = (float) Math.atan2(-face.x, -face.z);
		faceYaw = ropeYaw;
		jumpOff(face, ride.ropeJumpOut() * 16f, ride.ropeJumpUp() * 16f);
	}

	/** 보이는 몸 = 몸 축 (줄 축 후보) 쪽으로 slerp(+0x150 × f), +0x150 ×= 1 + 0.1111 f (0x1E0C90 / 0x1E0DC0, f = 60 Hz 프레임). */
	private void turnRope(float dt) {
		Vector3f back = NavRope.backDirs(rope.axis())[ropeDir];
		float want = (float) Math.atan2(back.x, back.z);
		float diff = (float) Math.IEEEremainder(want - ropeYaw, Math.PI * 2);
		float f = dt * 60f;
		ropeYaw += diff * Math.min(1, turnBlend * f);
		turnBlend = Math.min(1, turnBlend * (1 + (HeroNavigator.TURN_BLEND_GROW - 1) * f));
		faceYaw = ropeYaw;
	}

	/**
	 * 짚라인 (0x40000) — 내비게이션 0x1C60F8: 스틱과 무관하게 목표 속도 Sold+0x160 으로 가속 +0x164 (0x1E5FE0), 방향 = 낮은 끝 (0x1C5E04),
	 * 줄 위치를 [0.25 m, 길이 − 0.25 m] 로 자르고 끝에 닿으면 월드 속도 0 으로 놓음 (0x1C6B1C) [확실: 속도 / 추정: 끝 처리 — 영웅과 같음].
	 * AI (0x1CFCE0) 는 수평 줄과 같은 분기로 점프 버튼을 누르지만 0x1C5488 이 짚라인이면 무시한다 → 끝까지 탄다 [확실].
	 */
	private void zip(Vector3f pos, Vector3f goal, Boolean clinging, Vector3f dir, float len, float dt) {
		Vector3f travel = new Vector3f(dir).mul(zipDir);
		faceYaw = (float) Math.atan2(-travel.x, -travel.z);
		moveX = 1;
		// AI 는 목표가 가파르게 아래면 점프 버튼을 누르지만 (0x1CFCE0, 수평 줄과 같은 분기) 줄 갱신 0x1C5488 은 짚라인 (0x40000) 이면 바로 돌아간다
		// → 적도 짚라인에서는 끝까지 탄다 [확실: 영웅과 같은 코드] (전 "점프로 놓음" [근사] 폐기)
		zipSpeed = Math.min(ride.zipSpeed() * 16f, zipSpeed + ride.zipAccel() * 16f * dt);
		float gap = Math.min(0.5f, ZIP_END / len);
		ropeT += zipDir * zipSpeed * dt / len;
		if (zipDir > 0 ? ropeT >= 1 - gap : ropeT <= gap) {
			ropeT = Math.max(gap, Math.min(1 - gap, ropeT));
			snapRope(pos);
			drop(); // 끝: 월드 속도 0 으로 놓음
			return;
		}
		snapRope(pos);
	}

	/** 짚라인 속도 (월드 단위/s). */
	public float zipSpeed() {
		return zipSpeed;
	}
}
