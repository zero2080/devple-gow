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
	 * <p>
	 * 바닥 호출 마스크 (0x1EA5F0 의 s2 → 0x1EA8D4 a3, 자유 이동 0x1EA200 의 0x8020000 → 0x1EA2DC) 에는 <b>ClimbGuide 0x20000</b> 이 벽에 붙어 있지 않을 때
	 * (+0x174 0x20 · +0x2D8 0x300 이 아닐 때, 0x1EA6A0 – 0x1EA6B8) 늘 들어 있다 → 지상·공중의 영웅과 적에게 ClimbGuide 윗면은 바닥이 아니다 [확실].
	 * 바닥 질의는 벽에 붙지 않았을 때만 쓰므로 정적 면 거름에 넣는다. 위를 보는 ClimbGuide 면은 전 레벨 358 개 (ATHN05A 덩굴 통로 146 · PAND04E 96 …,
	 * 덩굴 벽 아래 띠 상자 윗면) — 예전에는 ATHN05A 에서 뒤로 점프한 크라토스가 맞은편 벽 대신 16 폭 띠 윗면 (y 175 / 205) 에 내려섰다 (43 문서 §19).
	 * 위를 보는 CombatGuide 면은 데이터에 없다.
	 */
	public static final long FLOOR_EXCLUDE_STATIC = SurfaceAttr.GENERAL_GUIDE.bit() | SurfaceAttr.NO_PLAYER_COLLISION.bit() | SurfaceAttr.CLIMB_GUIDE.bit();
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

	/**
	 * 원작 바닥 질의 0x1E3298 의 면 조건 (법선 · 위 &gt; 0.1, {@link LevelCollision#QUERY_NY}) 으로 정적 바닥만 — 엔진 바닥 ({@link LevelCollision#FLOOR_NY} 0.7)
	 * 이 놓치는 가파른 비탈 (ATHN02B 사이클롭스 바닥 945 → 937 경사 띠, 법선 Y 0.696) 용. 없으면 NaN.
	 */
	public float steepGround(float x, float y, float z, float drop) {
		float best = Float.NaN;
		for (LevelCollision c : statics) {
			LevelCollision.Floor f = c.floorAt(x, y, z, drop, LevelCollision.QUERY_NY, t -> (t.attrs() & FLOOR_EXCLUDE_STATIC) == 0);
			if (f != null && (Float.isNaN(best) || f.y() > best)) {
				best = f.y();
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

	/** 한쪽 면 선분 질의 (정적 시트, 시작점이 면 앞인 면만 — 원작 0x16BDE0) [확실]: 처음 만나는 면, 없으면 null. */
	public LevelCollision.Hit frontSegment(Vector3f from, Vector3f to, long exclude) {
		Vector3f d = new Vector3f(to).sub(from);
		float len = d.length();
		if (len < 1e-5f) {
			return null;
		}
		LevelCollision.Hit best = null;
		for (LevelCollision c : statics) {
			LevelCollision.Hit h = c.raycast(from, d, len, t -> (t.attrs() & exclude) == 0 && t.n().dot(d) < 0);
			if (h != null && (best == null || h.distance() < best.distance())) {
				best = h;
			}
		}
		return best;
	}

	/**
	 * 정적 시트의 한쪽 면 선분 접촉: 원작 시트 면 검사 0x16BDE0 처럼 시작점이 면 앞 (법선 쪽)·끝점이 뒤인 면만 [확실: 디스어셈블 — 시작 거리 &lt; 0 이거나
	 * 끝 거리 &gt; 0 이면 안 맞음]. 엔진 {@link #staticContact} 는 양면 (다른 질의들의 근사) — 천장 질의만 이것을 쓴다.
	 */
	public Contact frontContact(Vector3f from, Vector3f to, long exclude) {
		LevelCollision.Hit best = frontSegment(from, to, exclude);
		if (best == null) {
			return null;
		}
		float len = from.distance(to);
		int fx = best.tri().info() != null ? best.tri().info().sheetFx() : 0;
		return new Contact(len > 0 ? best.distance() / len : 0, best.point(), new Vector3f(best.tri().n()), fx, null);
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

	/**
	 * 이동 선분 검사의 면 제외 마스크 (0x1EA5F0 0x1EAB78 · 0x1EA200 0x1EA3D8, 0x3309F4 = <b>0x2030002</b> | (+0x258 &amp; 0x4300000) | 호출 마스크) [확실: 디스어셈블].
	 * 0x2030002 = Water · AIBlock · ClimbGuide · NoDiving — 몸 질의 (0x10042) 와 달리 <b>Death 는 막고</b> ClimbGuide 는 늘 지나감.
	 * 나머지 (NoPlayer/NoAICollision, 호출 마스크의 CombatGuide · NoCSMCollision) 는 몸 질의 마스크 body ({@link #bodyExclude}) 에서 그대로.
	 */
	public static long moveExclude(long body) {
		return body & (SurfaceAttr.NO_PLAYER_COLLISION.bit() | SurfaceAttr.NO_AI_COLLISION.bit() | SurfaceAttr.COMBAT_GUIDE.bit()
				| SurfaceAttr.NO_CSM_COLLISION.bit()) | SurfaceAttr.WATER.bit() | SurfaceAttr.AI_BLOCK.bit() | SurfaceAttr.CLIMB_GUIDE.bit()
				| SurfaceAttr.NO_DIVING.bit();
	}

	/** 이동 선분 검사를 건너뛰는 수평 이동 (순간 이동·스폰·동기 배치 — 원작은 이 경로를 거치지 않는다) [엔진]. */
	public static final float MOVE_TELEPORT = 64f;

	/**
	 * 이동 선분 마지막 검사 (원작 0x1EA5F0 끝 0x1EAB78 – 0x1EAC20, 자유 이동 0x1EA200 의 0x1EA3D8 – 0x1EA478) [확실: 디스어셈블]:
	 * 몸 밀어내기 (0x1E2DD8) · 몸 질의 (0x1E2100) · 바닥 (0x1E3298) 뒤에 <b>이전 내비 위치 (+0x110) → 새 위치</b> 선분 (0x11E190) 을 던져
	 * 막히면 새 위치 = 충돌점 + 면 법선 × 0.05 (0x3D4CCCCD) 로 두고 밀기·몸 질의부터 다시 (최대 3 회). 몸 질의는 몸 가운데에서 바깥으로 쏘는
	 * 선분이라 가운데가 얇은 면 (GeneralGuide 막이, 갑판 난간) 을 한 틱에 넘어가면 반대쪽으로 밀어내는데, 이 선분이 그것을 막는다.
	 * <p>
	 * 엔진: from / to = 발 위치, 선분 높이 = max(두 발) + lift (내비 위치 = 발 + 바닥 +0x130.y), 수평으로 [근사: 원작은 두 내비 위치 그대로 —
	 * 계단 오르내림의 바닥 면은 엔진 바닥 붙이기가 맡으므로 바닥 면 (|n.y| ≥ {@link LevelCollision#FLOOR_NY}) 은 보지 않는다]. 법선은 시작점 쪽으로.
	 * to 의 x·z 만 고친다.
	 *
	 * @return 막혀서 to 를 고쳤으면 true
	 */
	public boolean blockMove(Vector3f from, Vector3f to, float lift, long exclude) {
		float dx = to.x - from.x, dz = to.z - from.z;
		float len2 = dx * dx + dz * dz;
		if (len2 < 1e-8f || len2 > MOVE_TELEPORT * MOVE_TELEPORT) {
			return false;
		}
		float y = Math.max(from.y, to.y) + lift;
		Vector3f o = new Vector3f(from.x, y, from.z), d = new Vector3f(dx, 0, dz);
		float len = (float) Math.sqrt(len2);
		LevelCollision.Hit best = null;
		for (LevelCollision c : statics) {
			LevelCollision.Hit h = c.raycast(o, d, len, t -> !t.floor() && (t.attrs() & exclude) == 0);
			if (h != null && (best == null || h.distance() < best.distance())) {
				best = h;
			}
		}
		if (best == null) {
			return false;
		}
		Vector3f n = new Vector3f(best.tri().n().x, 0, best.tri().n().z);
		if (n.lengthSquared() < 1e-8f) {
			n.set(-dx, 0, -dz);
		}
		n.normalize();
		if (n.x * dx + n.z * dz > 0) {
			n.negate(); // 시작점 쪽 (양면 질의)
		}
		to.x = best.point().x + n.x * MOVE_SKIN;
		to.z = best.point().z + n.z * MOVE_SKIN;
		return true;
	}

	/** 천장 질의 면 법선 문턱: 법선 · 위 &lt; −0.1 인 (아래를 보는) 면만 천장 (0x1E39A8 0xBDCCCCCD) [확실]. */
	public static final float CEILING_NY = -0.1f;

	/**
	 * 천장 질의 (원작 0x1E36B0) [확실: 디스어셈블 — 선분·마스크·법선·높이 / 추정: 내비 위치 = 발 + base]:
	 * 0x1EA5F0 (지상·공중) 은 수직 속도 (+0xE0.y) &gt; 0 이면 바닥 0x1E3298 대신 이것을 (0x1EA8A4 – 0x1EA8BC → 0x1EAB60), 자유 이동 0x1EA200 은
	 * 바닥 뒤에 늘 (0x1EA3CC) 부른다. 영웅·적 공용.
	 * <ul>
	 *   <li>d = |새 내비 y − 옛 내비 y (+0x110)|, L = min(키 − base − 1, d + base) (+0x120.y = 키 35.2, +0x130.y = base 6.4).</li>
	 *   <li>선분 시작 P0 = 발 + 키 − L (머리 − L), 끝 Q = 발 + 키 + d + base 의 몸 상자 윗면 다섯 점 (가운데, 오른쪽 ± 반폭 × 정면 ± 깊이/2)
	 *       — 시작은 늘 가운데 (0x1E3958 0x11E190 모드 2, 가장 가까운 접촉 — 시트 면은 한쪽 면 {@link #frontContact}). 벽 (+0x174 &amp; 0x30) 이면 네 모서리만 (엔진 벽 상태는 내비게이터가 맡음).</li>
	 *   <li>면 제외 = 호출 마스크 (몸 질의와 같은 s2) | 0x1010042 | (+0x258 &amp; 0x4300000) — 바닥 질의와 같다 (GeneralGuide 는 천장이 아님).</li>
	 *   <li>처음 맞은 면의 법선 · 위 &lt; −0.1 이고 맞은 y − 키 &lt; 발 y 면 발 y = 맞은 y − 키 (머리를 천장에 맞춤) 으로 끝 (0x1E3A18).
	 *       수직 속도는 건드리지 않는다 (0x1EA200 / 0x1EA5F0 모두 반환값을 버림) — 오르는 동안 천장에 붙어 있다가 중력으로 떨어진다.</li>
	 * </ul>
	 *
	 * @param foot      이번 틱 이동 뒤 발 위치 (y 만 고침)
	 * @param prevFootY 지난 틱 발 높이 (+0x110)
	 * @param yaw       정면 요 (모델 −Z 기준)
	 * @param height    키 (+0x120.y)
	 * @param base      바닥 (+0x130.y, 내비 위치 = 발 + base)
	 * @param halfW     반폭 (+0x120.x / 2)
	 * @param halfD     반깊이 (+0x120.z / 2)
	 * @param exclude   면 제외 마스크
	 * @return 막혀서 foot.y 를 낮췄으면 true
	 */
	public boolean ceiling(Vector3f foot, float prevFootY, float yaw, float height, float base, float halfW, float halfD, long exclude) {
		float d = Math.abs(foot.y - prevFootY);
		float l = Math.min(height - base - 1, d + base);
		Vector3f p0 = new Vector3f(foot.x, foot.y + height - l, foot.z);
		float top = foot.y + height + d + base;
		float fx = (float) -Math.sin(yaw), fz = (float) -Math.cos(yaw), rx = -fz, rz = fx;
		Vector3f q = new Vector3f();
		for (int i = 0; i < 5; i++) {
			float s = i == 0 ? 0 : (i & 1) != 0 ? halfW : -halfW, t = i == 0 ? 0 : i < 3 ? -halfD : halfD;
			q.set(foot.x + rx * s + fx * t, top, foot.z + rz * s + fz * t);
			Contact c = nearer(frontContact(p0, q, exclude), bodyContact(p0, q, exclude));
			if (c == null || c.normal().y >= CEILING_NY) {
				continue;
			}
			float y = c.point().y - height;
			if (y < foot.y) {
				foot.y = y;
				return true;
			}
		}
		return false;
	}

	/** 영웅 천장 질의 면 제외 = 몸 질의 마스크 (호출 마스크 s2 + 0x10042 + NoPlayerCollision) + GeneralGuide (0x1E38CC 0x1010042) [확실]. */
	public static long ceilingExclude(long body) {
		return body | SurfaceAttr.GENERAL_GUIDE.bit();
	}

	/** 막힌 이동의 새 위치 = 충돌점 + 법선 × 0.05 (0x1EABF4 / 0x1EA438). */
	public static final float MOVE_SKIN = 0.05f;

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
		return neighbour.collideSphere(c, r, skip, exclude) | own; // 이웃 레버·밀기 상자도 붙잡은 동안 몸 제외 (SceneNav 가 두 슬롯 이름을 함께 줌)
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
