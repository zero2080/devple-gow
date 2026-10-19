package devple.gow.engine.level;

import devple.gow.engine.render.Camera;
import devple.gow.tools.anim.Animation;
import devple.gow.tools.anim.AnimationSet;
import devple.gow.tools.anim.Mat4;
import devple.gow.tools.anim.Skeleton;
import devple.gow.tools.level.BallHull;
import devple.gow.tools.level.CameraDef;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.level.LevelLayout;
import devple.gow.tools.level.NcvCurve;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 원작 레벨 카메라 시스템 재구현 (30 문서). 실행 파일의 카메라 관리자 0x1EE8E0/0x1EF310/0x1EF3F0 와 카메라 상태 갱신 0x1F3768/0x1F29A0 를 따른다.
 * <ul>
 *   <li>선택: 플레이어가 든 CMV hull 의 카메라들이 후보. 활성 목록은 후보에서 빠질 때만 지워지고 새 후보는 뒤에 붙는다.
 *       우선순위(CAM 0xBC) 최대, 동순위면 나중에 들어온 것 (전환표 prefer 가 있으면 그것). 후보가 없으면 현재 카메라 유지</li>
 *   <li>상태: 대상 T(영웅 + 24 높이), 요/피치(도), 거리. 눈 = T − 방향(요, 피치)·거리</li>
 *   <li>모드 (CAM 0x78 피치 / 0x7A 요): 1 설계 각도, 2 카메라 위치 → T 방향, 3 레일 점 → T 방향 (레일 점은 비용 최소 t 를 지역 탐색)</li>
 *   <li>전환: 기본 시간 CAM 0xB8 또는 전환표(이전 카메라 이름) 시간. 0 이하 = 컷. 상태 공간(T, 각, 거리)에서 Hermite 보간 [근사: 속도항 0]</li>
 * </ul>
 * 스크립트: {@link #request} = Internal 0x8 SwitchCamera (한 프레임 후보), {@link #setVolumeQuery} = Internal 0x9 (CMV 질의 on/off).
 * <p>
 * 관절 카메라: 소유 오브젝트에 ANM 이 있으면 카메라(이름이 같은 관절, 없으면 관절 0)는 그 애니를 따라 움직인다. 플래그 0x1000 이면 활성화될 때
 * 애니를 처음부터 튼다 (0x1F3A38 → 0x1004B8/0x131038). 보스 잡기·처형 카메라(grabLeftCam, bossKillCam …)가 이 방식이다 (30 문서 §6).
 * 전환마다 {@link #onSwitch} 로 새 카메라 이름을 알린다 (원작 0x1EEE88 → 전역 이벤트 1037, 인자0 = 카메라 이름).
 * AresCamera1 은 원작 전용 함수 0x1F67C0 (영웅 + 아레스 두 대상, 레일 NCV_AresCamRail) — {@link AresCamera} ({@link #request(String, Vector3f...)}).
 */
public final class CameraRig {

	/** 영웅 카메라 대상 높이 (0x1DCC20: 오프셋 (0, 1.5·16, 0)). */
	public static final float TARGET_HEIGHT = 24f;
	/** 대상이 없을 때 쓰는 추적 카메라 거리/피치 (원작 기본 "Follow" 카메라 대용, 추정). */
	private static final float FOLLOW_DIST = 150f, FOLLOW_PITCH = -18f;
	private static final float FRAME = 1 / 60f;
	/** 원작 전용 처리 카메라 이름 (0x1F38C8 문자열 비교 → 0x1F67C0). */
	public static final String ARES_CAMERA = "AresCamera1";

	/** 카메라 매개 상태 (원작 상태 블록 +0x70..0x94 해당). */
	public static final class Pose {
		public final Vector3f target = new Vector3f();
		public float yaw, pitch, dist, fovDeg = 45f;

		public Vector3f eye(Vector3f out) {
			return dir(yaw, pitch, out).mul(-dist).add(target);
		}

		void set(Pose o) {
			target.set(o.target);
			yaw = o.yaw;
			pitch = o.pitch;
			dist = o.dist;
			fovDeg = o.fovDeg;
		}

		/** 눈/주시점에서 매개 상태 만들기. */
		void look(Vector3f eye, Vector3f at) {
			target.set(at);
			Vector3f v = new Vector3f(at).sub(eye);
			dist = v.length();
			yaw = yawOf(v);
			pitch = pitchOf(v);
		}
	}

	/** 소유 오브젝트 ANM 으로 움직이는 카메라 (관절 카메라). */
	private static final class CamAnim {
		final Skeleton sk;
		final float[] inst, local;
		final int joint;
		final Animation anim;
		float time;
		boolean live;

		CamAnim(Skeleton sk, float[] inst, float[] local, int joint, Animation anim) {
			this.sk = sk;
			this.inst = inst;
			this.local = local;
			this.joint = joint;
			this.anim = anim;
		}

		/** 시간 time 의 카메라 월드 행렬. 애니 끝에서는 마지막 프레임 유지 [추정]. */
		float[] world() {
			float[][] lw = anim.sample(sk, time, false).world(sk);
			return Mat4.mul(local, Mat4.mul(lw[Math.min(joint, lw.length - 1)], inst));
		}
	}

	/** 카메라 하나의 실행 상태 (원작 0x170 상태 링 원소). */
	private final class Shot {
		final LevelData.PlacedCamera cam;
		final Pose pose = new Pose();
		float t = -1;
		boolean init;
		float elapsed, blend;
		/** AresCamera1 상태 (0x1F67C0) 와 그 카메라의 이전 화면. */
		AresCamera ares;
		AresCamera.View aresView;

		Shot(LevelData.PlacedCamera cam) {
			this.cam = cam;
		}
	}

	private final LevelData data;
	private final Map<LevelData.PlacedCamera, CamAnim> anims = new HashMap<>();
	/** 요청과 함께 온 추가 대상 (AresCamera1: 아레스 몸, 영웅 관절 vertebrae4, 아레스 관절 BAR01JOCVEC1). */
	private Vector3f[] focus;
	/** 이번 틱 영웅 발 위치. */
	private final Vector3f heroFeet = new Vector3f();
	private Consumer<String> switchListener;
	private final List<LevelData.PlacedCamera> active = new ArrayList<>();
	private final List<LevelData.PlacedCamera> requests = new ArrayList<>();
	private final List<Shot> shots = new ArrayList<>();
	private Shot follow;
	private boolean volumeQuery = true;
	/** Vis Attr11_1 = 2 로 꺼진 오브젝트 (그 CMV 볼륨은 질의에서 빠진다). */
	private final java.util.Set<String> disabled = new java.util.HashSet<>();
	private final Pose prev = new Pose(), cur = new Pose();
	private boolean first = true;
	/** 점프 중 대상 높이 고정 (플래그 0x800/0x4000, 0x1F2850). */
	private float groundY = Float.NaN, jumpOffset;
	private String last = "";

	public CameraRig(LevelData data) {
		this.data = data;
		bindAnimations();
	}

	/** 카메라를 가진 오브젝트에 ANM 이 있으면 그 카메라는 관절 애니를 따른다 (LevelData 와 같은 관절 규칙). */
	private void bindAnimations() {
		if (data.wad == null) {
			return;
		}
		for (LevelLayout.Placement p : data.layout.placements()) {
			String anm = null;
			boolean cams = false;
			for (String r : p.object().refs()) {
				if (r.startsWith("ANM_")) {
					anm = r;
				}
				cams |= r.startsWith("CAM_");
			}
			if (anm == null || !cams) {
				continue;
			}
			Animation act = null;
			try {
				AnimationSet set = AnimationSet.load(data.wad, anm);
				act = set.all().isEmpty() ? null : set.all().getFirst(); // 카메라 오브젝트 ANM 은 act 1 개 (ATHN01E/02D 전수) [확실: 데이터]
			} catch (RuntimeException ignored) {
				// 읽지 못하는 ANM: 정지 카메라로 둔다
			}
			if (act == null) {
				continue;
			}
			Skeleton sk = p.object().skeleton();
			for (LevelData.PlacedCamera c : data.placedCameras) {
				if (!c.owner().equals(p.instance().name()) || !p.object().refs().contains(c.def().name())) {
					continue;
				}
				String jn = c.def().name().substring(4);
				int j = 0;
				for (int k = 0; k < sk.size(); k++) {
					if (sk.joints.get(k).name().equalsIgnoreCase(jn)) {
						j = k;
					}
				}
				anims.put(c, new CamAnim(sk, p.instance().matrix(), c.def().local(), j, act));
			}
		}
	}

	/** Internal 0x8 SwitchCamera: 이름(RCM_ 접두사 선택)을 이번 갱신의 후보로 넣는다. */
	public void request(String name) {
		request(name, (Vector3f[]) null);
	}

	/**
	 * 추가 대상이 있는 요청. 원작 아레스 클래스(Enemy1 하위 클래스 2, vtable 0x2BB2D0)의 갱신 0x1C3370 이 매 프레임
	 * SwitchCamera("AresCamera1", 0) 을 부른다 — 그 카메라는 영웅과 아레스를 함께 잡는다 (대상 슬롯은 0x1F59A0 이 등록).
	 * targets = [아레스 몸(발) 위치, 영웅 관절 "vertebrae4" 월드, 아레스 관절 "BAR01JOCVEC1" 월드] (뒤 둘은 생략 가능).
	 */
	public void request(String name, Vector3f... targets) {
		LevelData.PlacedCamera c = data.camera(name);
		if (c != null) {
			requests.add(c);
			if (targets != null && targets.length > 0 && targets[0] != null) {
				focus = new Vector3f[targets.length];
				for (int i = 0; i < targets.length; i++) {
					focus[i] = targets[i] == null ? null : new Vector3f(targets[i]);
				}
			}
		}
	}

	/** AresCamera1 의 마지막 결과 (검증용, 활성이 아니면 null). */
	public AresCamera aresState() {
		Shot s = shots.isEmpty() ? null : shots.getLast();
		return s == null ? null : s.ares;
	}

	/** 카메라가 바뀔 때마다 새 카메라 짧은 이름 (원작 전역 이벤트 1037 → EventSensor, 예 ATHN02C AlcWall "SparkCam13"). */
	public void onSwitch(Consumer<String> l) {
		switchListener = l;
	}

	/** 현재 카메라가 관절 애니 카메라면 그 애니 시간(초), 아니면 -1. */
	public float animTime() {
		Shot s = shots.isEmpty() ? null : shots.getLast();
		CamAnim a = s != null && s.cam != null ? anims.get(s.cam) : null;
		return a == null ? -1 : a.time;
	}

	/** 현재 상태의 눈 위치 (헤드리스 검증용). */
	public Vector3f eye() {
		return cur.eye(new Vector3f());
	}

	/** Internal 0x9: CMV 볼륨 질의 켜기/끄기 (전역 0x29F138). 끄면 스크립트 요청 카메라만 후보가 된다. */
	public void setVolumeQuery(boolean on) {
		volumeQuery = on;
	}

	/** Vis 이펙터 슬롯 4 (Attr11_1, 오브젝트 속성 0x11 = 충돌 서버 볼륨 추정): 1 켬, 2 끔. 꺼진 소유자의 CMV 는 무시. */
	public void setOwnerVolumes(String owner, boolean on) {
		if (on) {
			disabled.remove(owner.toLowerCase());
		} else {
			disabled.add(owner.toLowerCase());
		}
	}

	/** 현재(가장 최근) 카메라 이름, 없으면 "Follow". */
	public String current() {
		Shot s = shots.isEmpty() ? null : shots.getLast();
		return s == null || s.cam == null ? "Follow" : s.cam.def().shortName();
	}

	/** 현재 카메라가 수중 카메라인지 (CameraDef 플래그 0x8000). */
	public boolean underwater() {
		Shot s = shots.isEmpty() ? null : shots.getLast();
		return s != null && s.cam != null && (s.cam.def().p().flags() & devple.gow.tools.level.CameraDef.F_UNDERWATER) != 0;
	}

	public Pose pose() {
		return cur;
	}

	/** 고정 틱 갱신. hero = 발 위치, heroYaw = 진행 방향(라디안), airborne = 공중. */
	public void update(float dt, Vector3f hero, float heroYaw, boolean airborne) {
		prev.set(cur);
		heroFeet.set(hero);
		Vector3f t = target(hero, airborne);
		select(t);
		// 상태 링: 오래된 것부터 진행, 전환이 끝나면 앞쪽을 버린다 (0x1EF3F0)
		for (int i = shots.size() - 1; i >= 0; i--) {
			Shot s = shots.get(i);
			s.elapsed += dt;
			if (s.elapsed >= s.blend && i > 0) {
				shots.subList(0, i).clear();
				break;
			}
		}
		for (CamAnim a : anims.values()) {
			a.live = false;
		}
		for (Shot s : shots) {
			CamAnim a = s.cam != null ? anims.get(s.cam) : null;
			if (a != null && !a.live) {
				a.live = true;
				a.time += dt;
			}
		}
		Pose out = null;
		for (Shot s : shots) {
			solve(s, t, heroYaw, dt);
			if (out == null) {
				out = new Pose();
				out.set(s.pose);
			} else {
				blend(out, s.pose, s.blend <= 0 ? 1 : Math.min(1, s.elapsed / s.blend), out);
			}
		}
		cur.set(out);
		if (first) {
			prev.set(cur);
			first = false;
		}
		String n = current();
		if (!n.equals(last)) {
			if (System.getProperty("gow.debug") != null) {
				System.out.printf("[카메라] %s → %s (전환 %.2f초)%n", last, n, shots.getLast().blend);
			}
			if (switchListener != null && !n.equals("Follow")) {
				switchListener.accept(n);
			}
		}
		last = n;
	}

	/** 렌더 보간 결과를 카메라에 적용. */
	public void apply(Camera c, float alpha) {
		Pose p = new Pose();
		blend(prev, cur, alpha, p);
		p.eye(c.eye);
		c.target.set(p.target);
		c.fovDeg = p.fovDeg;
	}

	private Vector3f target(Vector3f hero, boolean airborne) {
		Vector3f t = new Vector3f(hero.x, hero.y + TARGET_HEIGHT, hero.z);
		if (!airborne || Float.isNaN(groundY)) {
			// 착지: 고정해 둔 높이 차를 프레임당 ×0.9 로 풀어 준다 (0x1F2968)
			jumpOffset *= 0.9f;
			groundY = hero.y;
		} else {
			jumpOffset = groundY - hero.y;
		}
		if (holdJump()) {
			t.y += jumpOffset;
		}
		return t;
	}

	private boolean holdJump() {
		Shot s = shots.isEmpty() ? null : shots.getLast();
		return s != null && s.cam != null && (s.cam.def().p().flags() & CameraDef.F_HOLD_JUMP) != 0;
	}

	// ---- 선택 (0x1EE8E0) ----

	private void select(Vector3f t) {
		List<LevelData.PlacedCamera> cand = new ArrayList<>(requests);
		requests.clear();
		if (volumeQuery) {
			for (LevelData.PlacedVolume v : data.volumes) {
				if (v.hull().kind != 1 || disabled.contains(v.owner().toLowerCase())) {
					continue;
				}
				float[] l = new float[3];
				Mat4.transformPoint(v.inverse(), t.x, t.y, t.z, l);
				BallHull h = v.hull();
				for (int i = 0; i < h.hulls.size(); i++) {
					if (inside(h.hulls.get(i), l)) {
						String n = h.nameOf(i);
						LevelData.PlacedCamera c = n != null ? data.camera(n) : null;
						// ANM 관절 카메라(IdleCam 인트로 팬)는 관절 애니메이션 미지원이라 정지 화면이 엉뚱해 후보에서 뺀다 [미구현]
						if (c != null && !c.def().p().animated() && !cand.contains(c)) {
							cand.add(c);
						}
					}
				}
			}
		}
		active.retainAll(cand);
		for (LevelData.PlacedCamera c : cand) {
			if (!active.contains(c)) {
				active.add(c);
			}
		}
		Shot curShot = shots.isEmpty() ? null : shots.getLast();
		String curName = curShot != null && curShot.cam != null ? curShot.cam.def().shortName() : "";
		LevelData.PlacedCamera best = null;
		int bestPri = Integer.MIN_VALUE;
		boolean bestPrefer = false;
		for (LevelData.PlacedCamera c : active) {
			int pri = c.def().p().priority();
			boolean prefer = transition(c, curName) != null && transition(c, curName).prefer();
			if (pri > bestPri || pri == bestPri && (prefer || !bestPrefer)) {
				best = c;
				bestPri = pri;
				bestPrefer = prefer;
			}
		}
		if (best == null) {
			if (shots.isEmpty()) {
				follow = new Shot(null);
				shots.add(follow);
			}
			return;
		}
		if (curShot != null && curShot.cam == best) {
			return;
		}
		Shot s = new Shot(best);
		CamAnim a = anims.get(best);
		if (a != null && (best.def().p().flags() & CameraDef.F_RESTART_ANIM) != 0) {
			a.time = 0; // 0x1F3B60: 활성화 시 소유 오브젝트 애니 처음부터
		}
		CameraDef.Transition tr = transition(best, curName);
		s.blend = curShot == null ? 0 : tr != null ? tr.blend() : best.def().p().blend();
		if (s.blend <= 0) {
			shots.clear();
		}
		shots.add(s);
	}

	private static CameraDef.Transition transition(LevelData.PlacedCamera c, String from) {
		for (CameraDef.Transition tr : c.def().transitions()) {
			if (tr.from().equalsIgnoreCase(from)) {
				return tr;
			}
		}
		return null;
	}

	private static boolean inside(BallHull.Hull h, float[] p) {
		if (h.planes().isEmpty()) {
			return false;
		}
		if (h.planes().size() == 1) { // 평면 1개 = 구 (x, y, z, r) — BallHull.insideHull, 36 문서 §5
			float[] b = h.planes().getFirst();
			float dx = p[0] - b[0], dy = p[1] - b[1], dz = p[2] - b[2];
			return dx * dx + dy * dy + dz * dz <= b[3] * b[3];
		}
		for (float[] pl : h.planes()) {
			if (pl[0] * p[0] + pl[1] * p[1] + pl[2] * p[2] + pl[3] > 0) {
				return false;
			}
		}
		return true;
	}

	// ---- 카메라 하나 풀기 (0x1F3768 / 0x1F29A0) ----

	private void solve(Shot s, Vector3f t, float heroYaw, float dt) {
		Pose p = s.pose;
		if (s.cam == null) {
			followSolve(s, t, heroYaw, dt);
			return;
		}
		CameraDef.Params cp = s.cam.def().p();
		p.fovDeg = verticalFov(cp.fovRad());
		CamAnim anim = anims.get(s.cam);
		float[] w = anim != null ? anim.world() : s.cam.world();
		Vector3f camPos = new Vector3f(w[12], w[13], w[14]);
		float[] f = s.cam.forward();
		Vector3f fwd = new Vector3f(f[0], f[1], f[2]);
		if ((cp.flags() & CameraDef.F_TRACK) == 0) {
			Vector3f at = new Vector3f(t);
			if ((cp.flags() & CameraDef.F_FIXED_LOOK) != 0) {
				// 고정 주시: 카메라 행렬 × (0xC0..0xC8 ×16) — 대개 (0,0,-80): 정면 고정
				float[] o = new float[3];
				float[] off = cp.lookOffset();
				Mat4.transformPoint(w, off[0], off[1], off[2], o);
				at.set(o[0], o[1], o[2]);
			}
			p.look(camPos, at);
			return;
		}
		float dYaw = yawOf(fwd), dPitch = pitchOf(fwd);
		int pm = cp.pitchMode(), ym = cp.yawMode();
		boolean railMode = pm == 3 || ym == 3, posMode = pm == 2 || ym == 2;
		boolean limited = cp.yawRange() < 180 || cp.pitchRange() < 90;
		Vector3f pivot = new Vector3f(camPos);
		if (ARES_CAMERA.equalsIgnoreCase(s.cam.def().shortName()) && s.cam.rail() != null) {
			aresSolve(s, p);
			return;
		}
		if (railMode && s.cam.rail() != null) {
			s.t = railParam(s, t, dYaw, dPitch, limited, dt);
			float[] r = new float[3];
			s.cam.rail().eval(s.t, r);
			pivot.set(r[0], r[1], r[2]);
		}
		Vector3f v = new Vector3f(t).sub(pivot);
		if (cp.distScale() < 0) {
			v.negate();
		}
		float pivotYaw = yawOf(v), pivotPitch = pitchOf(v);
		p.target.set(t);
		p.yaw = switch (ym) {
			case 2, 3 -> pivotYaw;
			default -> dYaw;
		};
		p.pitch = switch (pm) {
			case 2, 3 -> pivotPitch;
			default -> dPitch;
		};
		float dist;
		if (railMode || posMode) {
			float d = v.length();
			float keep = d * (1 - cp.distScale());
			if (!railMode) {
				keep = Math.max(cp.railMin(), Math.min(cp.railMax(), keep));
			}
			dist = d - keep;
		} else {
			dist = s.init ? p.dist : camPos.distance(t);
		}
		if (cp.distScale() >= 0) {
			dist = Math.max(cp.distMin(), Math.min(cp.distMax(), dist));
		}
		p.dist = Math.abs(dist);
		if (limited) {
			p.yaw = clampAngle(p.yaw, dYaw, cp.yawRange());
			p.pitch = Math.max(dPitch - cp.pitchRange(), Math.min(dPitch + cp.pitchRange(), p.pitch));
		}
		s.init = true;
	}

	/** AresCamera1 (0x1F67C0): 원작 비용 함수로 레일 t·붐·방향을 풀어 상태에 넣는다 ({@link AresCamera}). */
	private void aresSolve(Shot s, Pose p) {
		if (s.ares == null) {
			s.ares = new AresCamera(s.cam.rail());
			// 새 상태는 직전 화면에서 시작 (0x1F3A38 상태 초기화)
			s.aresView = view(cur);
		}
		AresCamera.Targets tg = new AresCamera.Targets();
		tg.heroBody.set(heroFeet);
		Vector3f boss = focus != null && focus.length > 0 ? focus[0] : heroFeet;
		tg.bossBody.set(boss);
		tg.heroJoint.set(focus != null && focus.length > 1 && focus[1] != null ? focus[1] : new Vector3f(heroFeet).add(0, TARGET_HEIGHT, 0));
		tg.bossJoint.set(focus != null && focus.length > 2 && focus[2] != null ? focus[2] : new Vector3f(boss).add(0, TARGET_HEIGHT, 0));
		s.ares.update(tg, s.aresView, view(prev));
		AresCamera a = s.ares;
		float d = Math.max(1, a.target.distance(a.eye));
		p.yaw = a.yaw;
		p.pitch = a.pitch;
		p.dist = d;
		dir(a.yaw, a.pitch, p.target).mul(d).add(a.eye);
		s.init = true;
	}

	private static AresCamera.View view(Pose p) {
		AresCamera.View v = new AresCamera.View();
		p.eye(v.eye);
		dir(p.yaw, p.pitch, v.fwd);
		v.depth = p.dist;
		return v;
	}

	/** 레일 t: 비용 최소점을 이전 t 에서 지역 탐색 (0x1F1AA0, 0x1F20F0), 감쇠 0xA8 로 따라간다. */
	private float railParam(Shot s, Vector3f t, float dYaw, float dPitch, boolean limited, float dt) {
		NcvCurve rail = s.cam.rail();
		CameraDef.Params cp = s.cam.def().p();
		boolean angles = limited && (cp.flags() & CameraDef.F_RAIL_ANGLES) != 0;
		float best;
		if (s.t < 0) {
			// 처음: 전 구간 표본 → 최소점 (0x1F20F0 대용)
			best = 0;
			float bc = Float.MAX_VALUE;
			int n = 128;
			for (int i = 0; i <= n; i++) {
				float u = rail.tMax() * i / n;
				float c = railCost(rail, u, t, cp, dYaw, dPitch, angles);
				if (c < bc) {
					bc = c;
					best = u;
				}
			}
			best = descend(rail, best, t, cp, dYaw, dPitch, angles);
			return best;
		}
		best = descend(rail, s.t, t, cp, dYaw, dPitch, angles);
		// t += (t' − t)·(1 − 감쇠) (60Hz 기준)
		float keep = (float) Math.pow(cp.damping(), dt / FRAME);
		float d = best - s.t;
		if (rail.closed) {
			float m = rail.tMax();
			if (d > m / 2) {
				d -= m;
			} else if (d < -m / 2) {
				d += m;
			}
		}
		return rail.wrap(s.t + d * (1 - keep));
	}

	private float descend(NcvCurve rail, float t0, Vector3f t, CameraDef.Params cp, float dYaw, float dPitch, boolean angles) {
		float u = t0, c = railCost(rail, u, t, cp, dYaw, dPitch, angles);
		float step = 0.05f;
		int guard = 0;
		while (Math.abs(step) > 0.0005f && guard++ < 400) {
			float n = rail.wrap(u + step);
			float nc = railCost(rail, n, t, cp, dYaw, dPitch, angles);
			if (nc < c) {
				u = n;
				c = nc;
				step *= 1.5f;
			} else {
				step *= -0.5f;
			}
		}
		return u;
	}

	/** 비용: 레일 점-대상 거리가 [min,max] 밖인 정도, 각도 범위 사용 시 max(거리, 각도 초과·1000) (0x1F1280, 0x1F12F0). */
	private static float railCost(NcvCurve rail, float u, Vector3f t, CameraDef.Params cp, float dYaw, float dPitch,
			boolean angles) {
		float[] r = new float[3];
		rail.eval(u, r);
		float dx = t.x - r[0], dy = t.y - r[1], dz = t.z - r[2];
		float d = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		float cost = d < cp.railMin() ? cp.railMin() - d : Math.max(0, d - cp.railMax());
		if (angles) {
			Vector3f v = new Vector3f(dx, dy, dz);
			float py = pitchOf(v), yy = yawOf(v);
			float pe = py < dPitch - cp.pitchRange() ? dPitch - cp.pitchRange() - py : Math.max(0, py - dPitch - cp.pitchRange());
			float ye = Math.max(0, Math.abs(wrap180(yy - dYaw)) - cp.yawRange());
			cost = Math.max(cost, (pe + ye) * 1000);
		}
		return cost;
	}

	/** 볼륨 밖 기본 카메라 (원작 "Follow", 0x337B68): 진행 방향 뒤에서 천천히 따라감 [추정]. */
	private void followSolve(Shot s, Vector3f t, float heroYaw, float dt) {
		Pose p = s.pose;
		float want = (float) Math.toDegrees(heroYaw); // 영웅 정면 (−sin, −cos) 과 같은 방향을 본다
		if (!s.init) {
			p.yaw = want;
			s.init = true;
		}
		p.yaw += wrap180(want - p.yaw) * Math.min(1, dt * 1.5f);
		p.pitch = FOLLOW_PITCH;
		p.dist = FOLLOW_DIST;
		p.target.set(t);
		p.fovDeg = verticalFov(0.95f);
	}

	// ---- 보간 / 각도 ----

	/** 상태 보간: 3차 Hermite(속도 0) = smoothstep, 각은 최단 방향 (0x1F3C90). */
	static void blend(Pose a, Pose b, float s, Pose out) {
		float h = s * s * (3 - 2 * s);
		out.target.set(a.target).lerp(b.target, h);
		out.yaw = a.yaw + wrap180(b.yaw - a.yaw) * h;
		out.pitch = a.pitch + (b.pitch - a.pitch) * h;
		out.dist = a.dist + (b.dist - a.dist) * h;
		out.fovDeg = a.fovDeg + (b.fovDeg - a.fovDeg) * h;
	}

	/** 원작 FOV(라디안, 4:3 가로) → 세로 FOV(도): 세로 반폭 = tan(f/2)·0.75 (0x1F2AC4) [추정]. */
	public static float verticalFov(float hRad) {
		return (float) Math.toDegrees(2 * Math.atan(Math.tan(hRad / 2) * 0.75));
	}

	/** 요(도): 0x1F0DC8 와 같이 atan2(−x, −z). */
	public static float yawOf(Vector3f v) {
		return (float) Math.toDegrees(Math.atan2(-v.x, -v.z));
	}

	/** 피치(도): atan2(y, 수평 길이). */
	public static float pitchOf(Vector3f v) {
		return (float) Math.toDegrees(Math.atan2(v.y, Math.sqrt(v.x * v.x + v.z * v.z)));
	}

	/** 요/피치 → 바라보는 방향 단위 벡터. */
	public static Vector3f dir(float yaw, float pitch, Vector3f out) {
		double y = Math.toRadians(yaw), p = Math.toRadians(pitch);
		return out.set((float) (-Math.sin(y) * Math.cos(p)), (float) Math.sin(p), (float) (-Math.cos(y) * Math.cos(p)));
	}

	static float wrap180(float a) {
		a %= 360;
		if (a > 180) {
			a -= 360;
		} else if (a < -180) {
			a += 360;
		}
		return a;
	}

	private static float clampAngle(float a, float center, float range) {
		if (range >= 180) {
			return a;
		}
		float d = wrap180(a - center);
		return center + Math.max(-range, Math.min(range, d));
	}
}
