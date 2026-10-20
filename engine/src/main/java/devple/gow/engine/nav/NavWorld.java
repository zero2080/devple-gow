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

	/**
	 * 영웅 충돌 질의의 면 거름 (원작 질의 제외 마스크 0x3309F4 와 면 재질 bitfield0 의 AND, 0x139218 / 0x16C3B8) [확실: 디스어셈블].
	 * <ul>
	 *   <li>바닥 0x1E3298: 호출 마스크 | <b>0x1010042</b> (Water·Death·AIBlock·GeneralGuide) | (+0x258 &amp; 0x4300000) — GeneralGuide 면은 바닥이 아니다.</li>
	 *   <li>몸 0x1E2100: 호출 마스크 | <b>0x10042</b> (Water·Death·AIBlock) | (+0x258 &amp; 0x4300000), 호출 마스크 (0x1EA200 / 0x1EA5F0) 에도 0x1000000 이 없다
	 *       → <b>GeneralGuide 면은 영웅 몸을 막는다</b> (보이지 않는 막이 벽).</li>
	 *   <li>턱 0x1E3A80 · 벽 0x1E47E0 · 옆 벽 0x1E53F0 선분: <b>0x9030042</b> (Water·Death·AIBlock·ClimbGuide·GeneralGuide·CombatGuide) | +0x258
	 *       (턱은 &amp; 0x4300000) — 선분은 GeneralGuide 막이를 지나 뒤의 벽·턱을 본다.</li>
	 * </ul>
	 * 영웅 +0x258 = 0x500000 (NoPlayerCollision·NoPlayerUse, Player 생성 0x1D63E0).
	 */
	public static final long FLOOR_EXCLUDE_STATIC = SurfaceAttr.GENERAL_GUIDE.bit() | SurfaceAttr.NO_PLAYER_COLLISION.bit();
	/** 턱 질의 선분 제외 = 0x9030042 | (0x500000 &amp; 0x4300000) [확실: 0x1E3C54]. */
	public static final long LEDGE_PROBE_EXCLUDE = SurfaceAttr.fromRecord(0x09030042, 0, 0) | SurfaceAttr.NO_PLAYER_COLLISION.bit();
	/** 벽·옆 벽 질의 선분 제외 = 0x9030042 | 0x500000 [확실: 0x1E492C / 0x1E5530]. */
	public static final long WALL_PROBE_EXCLUDE = LEDGE_PROBE_EXCLUDE | SurfaceAttr.NO_PLAYER_USE.bit();

	/** 가장 높은 바닥 (정적 + 발판). 없으면 null. 정적 면은 GeneralGuide 를 뺀다 (0x1E3298 의 0x1010042) [확실]. */
	public Floor floor(float x, float y, float z, float drop) {
		Floor best = null;
		for (LevelCollision c : statics) {
			LevelCollision.Floor f = c.floorAt(x, y, z, drop, t -> (t.attrs() & FLOOR_EXCLUDE_STATIC) == 0);
			if (f != null && (best == null || f.y() > best.y)) {
				best = new Floor(f.y(), f.tri().attrs(), null);
			}
		}
		for (Platforms ps : new Platforms[]{platforms, neighbour}) {
			Platforms.Support s = ps.floor(x, y, z, drop);
			if (s != null && (best == null || s.y() > best.y)) {
				best = new Floor(s.y(), s.attrs(), s); // 윗면 평면 재질의 표면 속성 (CDV 재질 엔트리 = 시트 표면 레코드) // 윗면 평면 재질의 표면 속성 (CDV 재질 엔트리 = 시트 표면 레코드)
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
		return segment(from, to, SurfaceAttr.NO_PLAYER_COLLISION.bit());
	}

	/** 선분 질의, 면 속성이 exclude 에 하나라도 걸리면 지나간다 (원작 0x3309F4 거름 — {@link #LEDGE_PROBE_EXCLUDE} / {@link #WALL_PROBE_EXCLUDE}). */
	public LevelCollision.Hit segment(Vector3f from, Vector3f to, long exclude) {
		Vector3f d = new Vector3f(to).sub(from);
		float len = d.length();
		if (len < 1e-5f) {
			return null;
		}
		LevelCollision.Hit best = null;
		for (LevelCollision c : statics) {
			LevelCollision.Hit h = c.raycast(from, d, len, t -> (t.attrs() & exclude) == 0);
			if (h != null && (best == null || h.distance() < best.distance())) {
				best = h;
			}
		}
		return best;
	}

	/**
	 * 선분 접촉 (원작 0x11E190 접촉 기록 0x2C8F10): t = 선분 비율, 점, 면 법선, 면 재질 SheetFX, 맞은 개체 (정적 시트 = null, CDV GO = 소유 GO 이름).
	 */
	public record Contact(float t, Vector3f point, Vector3f normal, int sheetFx, String owner) {
	}

	/**
	 * 정적 시트 (모든 슬롯 레벨) 의 가장 가까운 선분 접촉 (면 속성 &amp; exclude ≠ 0 인 면은 지나감, 양면).
	 */
	public Contact staticContact(Vector3f from, Vector3f to, long exclude) {
		LevelCollision.Hit h = segment(from, to, exclude);
		if (h == null) {
			return null;
		}
		float len = from.distance(to);
		int fx = h.tri().info() != null ? h.tri().info().sheetFx() : 0;
		return new Contact(len > 0 ? h.distance() / len : 0, h.point(), new Vector3f(h.tri().n()), fx, null);
	}

	/** CDV GO 몸체 (이 레벨 + 다른 슬롯 레벨 발판·문, 충돌 켜진 것) 의 가장 가까운 선분 접촉. */
	public Contact bodyContact(Vector3f from, Vector3f to, long exclude) {
		return nearer(platforms.segment(from, to, exclude), neighbour.segment(from, to, exclude));
	}

	/** 정적 시트 + CDV GO 몸체 중 가장 가까운 선분 접촉 (원작 0x11E190 모드 2). */
	public Contact contact(Vector3f from, Vector3f to, long exclude) {
		return nearer(staticContact(from, to, exclude), bodyContact(from, to, exclude));
	}

	public static Contact nearer(Contact a, Contact b) {
		return a == null ? b : b == null || a.t() <= b.t() ? a : b;
	}

	/**
	 * 몸 질의 0x1E2100 의 면 제외 마스크 (0x3309F4 = 호출 마스크 | 0x10042 | (+0x258 &amp; 0x4300000), 0x1E221C – 0x1E2240) [확실: 디스어셈블].
	 * <ul>
	 *   <li>고정 0x10042 = Water · Death · AIBlock. +0x258 &amp; 0x4300000: 영웅 0x500000 → NoPlayerCollision, AI 0xA00000 (0x1CEEAC) → NoAICollision.</li>
	 *   <li>자유 이동 기술 (0x1EA200 ← 0x1ECCF4 / 0x1ECE30, 기술 stateMask &amp; 0x1C = 0): 0x8020000 (ClimbGuide · CombatGuide),
	 *       기술 flags &amp; 0x60 이면 0x28020000 (+ NoCSMCollision).</li>
	 *   <li>지상·공중·벽·물 (0x1EA5F0 ← 0x1EAEC8 / 0x1EB3D8 …): ClimbGuide 0x20000 은 벽 (+0x174 0x20) 도 벽 옆 끝 점프 (+0x2D8 0x300) 도 아닐 때만
	 *       (0x1EA6A0 – 0x1EA6B8) → <b>ClimbGuide 는 벽에 붙어 있을 때만 막는다</b>. CombatGuide 0x8000000 은 플레이어 (+0x174 bit 31) 가 전투 기술 중
	 *       (combatant +0x4C ≠ 0) 이고 그 기술 (+0x54) 의 +0x13 (guard) ≠ 2 (피격 반응이 아님) 일 때만 빠지지 않는다 (0x1EA6C0 – 0x1EA6F4)
	 *       → <b>CombatGuide 는 플레이어의 공격·방어 기술 중에만 막는다</b> (적은 늘 지나감). 기술 flags &amp; 0x60 이면 + NoCSMCollision (0x1EA6FC – 0x1EA724).</li>
	 *   <li>줄 (0x1C5848): 0x8020000, 벽 옆 끝 점프 (+0x2D8 0x300) 면 0x8000000.</li>
	 * </ul>
	 * GeneralGuide (0x1000000) 는 어디에도 없어 늘 막는다 (38 §18.4).
	 *
	 * @param player    플레이어 (+0x174 bit 31 — 영웅 내비게이션만, 0x1E7E68)
	 * @param free      자유 이동 기술 (0x1EA200)
	 * @param wall      벽에 붙음 (+0x174 0x20) 또는 벽 옆 끝 점프 (+0x2D8 0x300)
	 * @param inMove    전투 기술 중 (combatant +0x4C)
	 * @param moveFlags 기술 +0x0E
	 * @param moveGuard 기술 +0x13 (1 방어, 2 피격 반응)
	 */
	public static long bodyExclude(boolean player, boolean free, boolean wall, boolean inMove, int moveFlags, int moveGuard) {
		long m = SurfaceAttr.WATER.bit() | SurfaceAttr.DEATH.bit() | SurfaceAttr.AI_BLOCK.bit()
				| (player ? SurfaceAttr.NO_PLAYER_COLLISION.bit() : SurfaceAttr.NO_AI_COLLISION.bit());
		if (inMove && (moveFlags & 0x60) != 0) {
			m |= SurfaceAttr.NO_CSM_COLLISION.bit();
		}
		if (free) {
			return m | SurfaceAttr.CLIMB_GUIDE.bit() | SurfaceAttr.COMBAT_GUIDE.bit();
		}
		if (!wall) {
			m |= SurfaceAttr.CLIMB_GUIDE.bit();
		}
		if (!(player && inMove && moveGuard != 2)) {
			m |= SurfaceAttr.COMBAT_GUIDE.bit();
		}
		return m;
	}

	/** 영웅이 기술 없이 서 있거나 떨어질 때의 몸 제외 마스크 (Water · Death · AIBlock · NoPlayerCollision · ClimbGuide · CombatGuide). */
	public static final long HERO_BODY_EXCLUDE = bodyExclude(true, false, false, false, 0, 0);

	/** 구 벽 밀어내기 (모든 정적 충돌) — 영웅 기본 몸 마스크 {@link #HERO_BODY_EXCLUDE}. */
	public boolean collideSphere(Vector3f c, float r) {
		return collideSphere(c, r, HERO_BODY_EXCLUDE);
	}

	/** 구 벽 밀어내기 (모든 정적 충돌), 면 속성 &amp; exclude ≠ 0 인 면은 지나감 ({@link #bodyExclude}). */
	public boolean collideSphere(Vector3f c, float r, long exclude) {
		boolean any = false;
		for (LevelCollision col : statics) {
			any |= col.collideSphere(c, r, exclude);
		}
		return any;
	}

	/**
	 * 움직이는 오브젝트 CDV hull (문·창살·회전방·발판) 로 구 밀어내기 — 이 레벨 (skip = 붙잡는 오브젝트 제외) + 다른 슬롯 레벨 (이웃 Animator 자세).
	 */
	public boolean collideBodies(Vector3f c, float r, java.util.Collection<String> skip) {
		return collideBodies(c, r, skip, HERO_BODY_EXCLUDE);
	}

	/** {@link #collideBodies(Vector3f, float, java.util.Collection)} 를 몸 제외 마스크 exclude 로 ({@link #bodyExclude}). */
	public boolean collideBodies(Vector3f c, float r, java.util.Collection<String> skip, long exclude) {
		boolean own = platforms.collideSphere(c, r, skip, exclude);
		return neighbour.collideSphere(c, r, java.util.Set.of(), exclude) | own;
	}

	// ---- 줄 공유 상태 (영웅·적이 같은 줄 오브젝트를 쓴다) ----

	private final java.util.Map<NavRope, RopeSim> sims = new java.util.IdentityHashMap<>();
	/** 원작 전역 0x29DBF8: 줄을 잡은 뒤 시간. 누가 잡든 0 (0x1C5D98), 플레이어 (+0x174 비트 31) 의 줄 갱신만 더한다 (0x1C54B4) [확실]. */
	private float ropeClock;

	/** 줄 시뮬레이션 (없으면 만든다): 매단 줄 = 매단 점에서 아래로, 양끝 고정 줄 (수평 줄·짚라인) = 두 끝 고정 (0x218488). 줄 오브젝트당 하나 (영웅·적 공용). */
	public RopeSim sim(NavRope r) {
		return sims.computeIfAbsent(r, k -> k.type() == devple.gow.tools.level.RopeDef.Type.HANG ? new RopeSim(k.top(), k.simLength(), k.joints())
				: RopeSim.fixed(k.top(), k.end(), k.joints()));
	}

	/** 만든 줄 시뮬레이션을 모두 한 프레임 진행. */
	public void updateRopes(float dt) {
		for (RopeSim s : sims.values()) {
			s.update(dt);
		}
	}

	/** 줄 잡음 (영웅·적): 0x29DBF8 = 0. */
	public void ropeGrabbed() {
		ropeClock = 0;
	}

	/** 플레이어의 줄 갱신 (0x1C5488 첫머리, 비트 31 일 때만): 0x29DBF8 += dt. */
	public void tickRopeClock(float dt) {
		ropeClock += dt;
	}

	/** 0x29DBF8 — × 가 먹으려면 0.5 초 넘게 (0x1C5544 / 0x1C55A4). */
	public float ropeClock() {
		return ropeClock;
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
