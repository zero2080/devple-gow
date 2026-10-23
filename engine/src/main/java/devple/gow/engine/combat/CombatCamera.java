package devple.gow.engine.combat;

import devple.gow.engine.anim.Animator;
import devple.gow.tools.anim.Animation;
import devple.gow.tools.anim.AnimationSet;
import devple.gow.tools.anim.Skeleton;
import devple.gow.tools.combat.CombatFile;
import devple.gow.tools.wad.WadFile;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * 전투 카메라 이벤트 23 (CAMERA) — 처형 클로즈업 (30 문서 §9.3). 원작 0x2054A0 (이벤트 창 첫 프레임, 전투원 +0x40 = 카메라 GO 가 없을 때):
 * <ol>
 *   <li>전투원 컨텍스트에 R_PERMK <b>goCamera</b> (스켈레톤 "camera": main / AimJoint / CamPos / Width / locator1) 를 만들고,
 *       덮어쓰기 표 0x304AB0 에 AimJoint·CamPos·Width 관절 번호를 둔다 (0x1F4560).</li>
 *   <li>변형 i = 0, 1, 2 … : act 해시 = Hash127(i 의 "%d", 시드 = 이벤트 해시 = Hash127Upper(접두사)) — 즉 act 이름 "접두사"+i
 *       (예 사이클롭스 M240 #ad15316a = "MAICYWMINI02CAM" → R_CY2 ANM_MAICY2 (ANM_camera 의 외부 그룹 MaiCy2) "MAICYWMini02Cam0").
 *       act 마다 0 초 CamPos 를 전투원 synchJoint 관절 (루트 모션 +0xEA) 기준으로 놓고 (전투원 → CamPos) 수평 방향과 (전투원 → 지금 카메라 눈)
 *       수평 방향의 내적이 가장 큰 변형을 고른다 — 지금 카메라 쪽에서 저작된 화면.</li>
 *   <li>goCamera 를 그 관절에 붙여 (0x131460) act 를 튼다. 매 프레임 0x205280: act 시간 = 전투원 애니 시간, 전투원 몸을 (눈 방향 − CamPos 방향)
 *       수평 각 × 가중치만큼 제자리 Y 회전 (0x1846F0 은 회전 행만) — 저작된 CamPos 가 실제 카메라 쪽으로 돌아온다.</li>
 *   <li>그 프레임에 이벤트 23 창이 없으면 GO 를 지우고 표를 비운다 (0x2070B8).</li>
 * </ol>
 * 카메라 쪽 (가중치 0.01/프레임, 대상 T, 줌 반경, 위 벡터) 은 {@link devple.gow.engine.level.CameraRig#combatCamera}.
 */
public final class CombatCamera {

	/** 이벤트 종류 23 (CAMERA). */
	public static final int EVENT = 23;

	/** 덮어쓰기 표 0x304AB0 의 이번 프레임 값 (월드). */
	public record Frame(Vector3f aim, Vector3f width, Vector3f camPos, Vector3f up) {
		/** 줌 반경 = |Width − AimJoint| (0x1F4638 → 관리자 +0x7A8 = /16, 0x1EF078 이 ×16). */
		public float radius() {
			return aim.distance(width);
		}
	}

	private WadFile perm;
	private final java.nio.file.Path permPath;
	private AnimationSet set;
	private Skeleton sk;
	private final List<WadFile> pending = new ArrayList<>();
	private int jAim = -1, jPos = -1, jWidth = -1;
	private boolean loadFailed;

	/** 지금 카메라를 잡은 전투원 (원작 전투원 +0x40) — 하나만 (덮어쓰기 표가 전역 하나). */
	private Object owner;
	private Animation act;
	private int eventHash;
	private Frame frame;

	/** perm = R_PERMK (ANM_camera, 스켈레톤 camera). null 이면 아무것도 안 한다. */
	public CombatCamera(WadFile perm) {
		this.perm = perm;
		this.permPath = null;
	}

	/** R_PERMK 경로 — 처음 이벤트 23 이 올 때 읽는다 (처형이 없는 장면은 적재 비용 없음). */
	public CombatCamera(java.nio.file.Path permPath) {
		this.permPath = permPath;
	}

	/** 적 WAD (ANM_camera 의 외부 그룹 MaiCy2 / MaiMin … 을 가진 쪽) — 처음 쓸 때 붙인다. */
	public void addWad(WadFile w) {
		if (w != null && !pending.contains(w)) {
			pending.add(w);
			if (set != null) {
				set.attachAll(w);
			}
		}
	}

	private boolean ready() {
		if (set != null) {
			return sk != null;
		}
		if (perm == null && permPath != null && !loadFailed) {
			try {
				perm = WadFile.load(permPath);
			} catch (java.io.IOException | RuntimeException e) {
				loadFailed = true;
			}
		}
		if (perm == null) {
			return false;
		}
		try {
			set = AnimationSet.load(perm, "ANM_camera");
			for (WadFile w : pending) {
				set.attachAll(w);
			}
			for (Skeleton s : Skeleton.loadAll(perm)) {
				if (s.name.equalsIgnoreCase("camera")) {
					sk = s;
				}
			}
		} catch (RuntimeException e) {
			sk = null;
		}
		if (sk != null) {
			jAim = sk.indexOf("AimJoint");
			jPos = sk.indexOf("CamPos");
			jWidth = sk.indexOf("Width");
			if (jAim < 0 || jPos < 0 || jWidth < 0) {
				sk = null;
			}
		}
		return sk != null;
	}

	/** 이번 프레임 덮어쓰기 (없으면 null). */
	public Frame frame() {
		return frame;
	}

	/** 카메라를 잡은 전투원 (없으면 null). */
	public Object owner() {
		return owner;
	}

	/** 지금 재생 중인 카메라 act 이름 (검증용, 없으면 null). */
	public String actName() {
		return act == null ? null : act.name;
	}

	/** 이 전투원 기술의 이벤트 23 창 (없으면 null). */
	public static CombatFile.Event active(Combatant c) {
		if (c == null || !c.active()) {
			return null;
		}
		float t = c.progress();
		for (CombatFile.Event e : c.move().events()) {
			if (e.type() == EVENT && t >= e.start() && t <= e.end()) {
				return e;
			}
		}
		return null;
	}

	/** act 이름 해시 = Hash127Upper (대소문자 무시 h = h·127 + c). */
	static int hashUpper(String s) {
		int h = 0;
		for (byte b : s.toUpperCase(java.util.Locale.ROOT).getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)) {
			h = h * 127 + b;
		}
		return h;
	}

	/** Hash127(문자열, 시드) — 0x1758A0. */
	static int hash(String s, int seed) {
		int h = seed;
		for (byte b : s.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)) {
			h = h * 127 + b;
		}
		return h;
	}

	/** 이벤트 해시의 변형 act 들 ("접두사"+0, +1, …, 처음 없는 번호에서 끝). */
	List<Animation> variants(int eventHash) {
		List<Animation> out = new ArrayList<>();
		if (!ready()) {
			return out;
		}
		for (int i = 0; ; i++) {
			int h = hash(Integer.toString(i), eventHash);
			Animation found = null;
			for (Animation a : set.all()) {
				if (hashUpper(a.name) == h) {
					found = a;
					break;
				}
			}
			if (found == null) {
				return out;
			}
			out.add(found);
		}
	}

	/**
	 * 틱마다 (전투원 갱신 뒤, 카메라 갱신 전). 이벤트 23 창이 있는 전투원 하나 (먼저 잡은 쪽 유지) 를 받는다.
	 *
	 * @param who    전투원 식별 (영웅 / EnemyFighter)
	 * @param c      그 전투 상태 (창이 없으면 해제)
	 * @param pos    전투원 위치 (요 회전의 축)
	 * @param yaw    [0] 전투원 요 (0x205280 이 돌린다 — 입출력)
	 * @param an     전투원 애니 (synchJoint, 시간)
	 * @param scale  전투원 배율
	 * @param eye    지금 카메라 눈
	 * @param weight 지금 덮어쓰기 가중치 (관리자 0x304AC4, 카메라가 0.01/프레임 올림)
	 * @return 이 전투원이 이번 프레임 카메라를 잡았는지
	 */
	public boolean update(Object who, Combatant c, Vector3f pos, float[] yaw, Animator an, float scale, Vector3f eye, float weight) {
		if (owner != null && owner != who) {
			return false;
		}
		CombatFile.Event e = active(c);
		if (e == null) {
			if (owner == who) {
				clear(); // 0x2070B8: 창 밖 → GO 지움, 표 비움
			}
			return false;
		}
		// 새 이벤트 해시 (다음 기술의 이벤트 23) 면 그 기술의 act 로 다시 고른다 [추정]. 0x2054A0 은 +0x40 이 비었을 때만 부르고 기술 시작 0x2098DC 도 GO 를
		// 지우지 않아 창이 이어지면 처음 act 가 남는 것처럼 읽히지만, 그대로 하면 미노타우로스 MAIMIN03 (6.67 초) 동안 maiMin01Cam0 (3.8 초) 을 다시 틀어 빈
		// 바닥을 본다. 기술마다 길이가 맞는 act (maiMin02Cam0 / maiMin03Cam0, 사이클롭스 F2/F4 …) 가 저작돼 있다.
		if (owner == null || e.hash() != eventHash) {
			if (!start(who, e, pos, yaw[0], an, scale, eye)) {
				return false;
			}
		} else {
			rotate(pos, yaw, an, scale, eye, weight);
		}
		frame = sample(pos, yaw[0], an, scale);
		return true;
	}

	/** 덮어쓰기 해제. */
	public void clear() {
		owner = null;
		act = null;
		frame = null;
		eventHash = 0;
	}

	/** 0x2054A0: 변형 고르기. 없으면 false (표 비움 — 원작도 GO 를 지운다). */
	private boolean start(Object who, CombatFile.Event e, Vector3f pos, float yaw, Animator an, float scale, Vector3f eye) {
		List<Animation> vs = variants(e.hash());
		if (vs.isEmpty()) {
			clear();
			return false;
		}
		Vector3f toEye = flat(new Vector3f(eye).sub(pos));
		Matrix4f att = attach(pos, yaw, an, scale);
		float best = -2;
		Animation pick = null;
		for (Animation a : vs) {
			float[][] w = a.sample(sk, 0, false).world(sk);
			Vector3f cp = att.transformPosition(new Vector3f(w[jPos][12], w[jPos][13], w[jPos][14]));
			float d = flat(cp.sub(pos)).dot(toEye);
			if (d > best) { // c.lt.s: 같으면 앞쪽
				best = d;
				pick = a;
			}
		}
		owner = who;
		act = pick;
		eventHash = e.hash();
		return true;
	}

	/**
	 * 0x205280: 전투원 몸을 Y 축으로 (눈 방향 − CamPos 방향) 각 × 가중치 — 수평 단위 벡터 사이 acos, 부호는 외적 y. 위치는 그대로 (회전 행만).
	 * [추정: 부호 — 저작된 CamPos 가 눈 쪽으로 수렴하는 방향]
	 */
	private void rotate(Vector3f pos, float[] yaw, Animator an, float scale, Vector3f eye, float weight) {
		if (weight <= 0) {
			return;
		}
		Frame f = sample(pos, yaw[0], an, scale);
		Vector3f toEye = flat(new Vector3f(eye).sub(pos)), toCam = flat(new Vector3f(f.camPos).sub(pos));
		if (toEye.lengthSquared() < 1e-6f || toCam.lengthSquared() < 1e-6f) {
			return;
		}
		double d = Math.atan2(-toEye.x, -toEye.z) - Math.atan2(-toCam.x, -toCam.z);
		d = Math.atan2(Math.sin(d), Math.cos(d));
		yaw[0] += (float) (d * weight);
	}

	/** 지금 act 를 전투원 애니 시간으로 표본해 관절 월드 (goCamera = 전투원 synchJoint 에 붙음). */
	private Frame sample(Vector3f pos, float yaw, Animator an, float scale) {
		float t = Math.min(an.time(), act.duration);
		float[][] w = act.sample(sk, Math.max(0, t), false).world(sk);
		Matrix4f att = attach(pos, yaw, an, scale);
		Vector3f aim = att.transformPosition(new Vector3f(w[jAim][12], w[jAim][13], w[jAim][14]));
		Vector3f wid = att.transformPosition(new Vector3f(w[jWidth][12], w[jWidth][13], w[jWidth][14]));
		Vector3f cp = att.transformPosition(new Vector3f(w[jPos][12], w[jPos][13], w[jPos][14]));
		// 관리자 +0x790 = CamPos 관절 행렬 +0x10 (둘째 행 = 관절 Y 축) — 0x1EF078 룩앳의 위 벡터 (카메라 롤)
		Vector3f up = att.transformDirection(new Vector3f(w[jPos][4], w[jPos][5], w[jPos][6]));
		if (up.lengthSquared() < 1e-8f) {
			up.set(0, 1, 0);
		}
		return new Frame(aim, wid, cp, up.normalize());
	}

	/** 붙는 관절 = 전투원 synchJoint (zeroJoint 기준, 0x1F7110 덮어쓰기와 같은 식 — {@link SyncPull#synchRel}). */
	private static Matrix4f attach(Vector3f pos, float yaw, Animator an, float scale) {
		return new Matrix4f().translation(pos).rotateY(yaw).mul(SyncPull.synchRel(an, scale));
	}

	private static Vector3f flat(Vector3f v) {
		v.y = 0;
		float l = v.length();
		return l > 1e-6f ? v.div(l) : v;
	}
}
