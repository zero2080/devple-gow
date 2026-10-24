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
 *       우선순위(CAM 0xBC) 최대, 동순위면 나중에 들어온 것. 전환표에서 현재 카메라 항목의 +0x1C ("prefer") 가 켜진 카메라는
 *       이번 선택에서 이기지 못한다 (동순위면 목록 자리만 맞바꿈, 다른 순위면 건너뜀 — 0x1EECE4). 후보가 없으면 현재 카메라 유지</li>
 *   <li>상태: 대상 T(영웅 + 24 높이), 요/피치(도), 화면 속 대상 각(데드존), 거리. 눈 = T − 거리·R(요, 피치)·방향(화면 각) (30 문서 §8)</li>
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

	/**
	 * 카메라 매개 상태 (원작 상태 블록 +0x70..0x94 해당): 대상 T (+0x70), 카메라 요/피치 (+0x80/+0x84), 화면 속 대상 각 sa/sb (+0x88/+0x8C,
	 * 카메라 좌표의 요/피치 — 데드존 0x98..0xA4 로 자른 값), 대상까지 거리 (+0x90). 눈 = T − 거리·R(요, 피치)·방향(sa, sb) (0x1F1108).
	 */
	public static final class Pose {
		public final Vector3f target = new Vector3f();
		public float yaw, pitch, dist, fovDeg = 45f;
		/** 화면 속 대상 각 (도, +0x88 요 / +0x8C 피치). 0 이면 대상이 화면 가운데. */
		public float sa, sb;

		public Vector3f eye(Vector3f out) {
			return rotate(yaw, pitch, dir(sa, sb, new Vector3f()), out).mul(-dist).add(target);
		}

		/** 카메라 정면 (−Z). */
		public Vector3f forward(Vector3f out) {
			return dir(yaw, pitch, out);
		}

		void set(Pose o) {
			target.set(o.target);
			yaw = o.yaw;
			pitch = o.pitch;
			dist = o.dist;
			fovDeg = o.fovDeg;
			sa = o.sa;
			sb = o.sb;
		}

		/** 눈/주시점에서 매개 상태 만들기. */
		void look(Vector3f eye, Vector3f at) {
			target.set(at);
			Vector3f v = new Vector3f(at).sub(eye);
			dist = v.length();
			yaw = yawOf(v);
			pitch = pitchOf(v);
			sa = sb = 0;
		}

		/** 눈과 카메라 회전(요, 피치)을 두고 대상 T 의 화면 각·거리를 다시 구한다 (0x1F11A0). */
		void aim(Vector3f eye, float yaw, float pitch, Vector3f t) {
			target.set(t);
			this.yaw = yaw;
			this.pitch = pitch;
			Vector3f l = unrotate(yaw, pitch, new Vector3f(t).sub(eye));
			dist = l.length();
			sa = dist < 1e-6f ? 0 : yawOf(l);
			sb = dist < 1e-6f ? 0 : pitchOf(l);
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

		/**
		 * 시간 time 의 카메라 월드 행렬. 애니 끝에서는 마지막 프레임 유지 [추정]. 관절 자세는 원작 디스크립터 배치 ({@code Animation.sampleRecords}, 05 §2.8.1):
		 * 카메라 오브젝트 ANM 은 거의 모두 데이터 타입 [0(p2 3)] 하나라 예전 배치는 회전 (디스크립터 0) 만 읽고 이동 (디스크립터 1) 을 버렸다 —
		 * 전 WAD 관절 카메라 213 개 중 179 개의 눈 이동이 빠져 있었다 (ATHN03A camFireballShot 1.5 초 동안 +x 120 · +y 52, camMinoZoom −x 410,
		 * ATHN01A IdleCam 9.5 초 갑판 위로 +z 390, PAND05B camEleEnter 엘리베이터 쪽으로) [확실: 코드 0x100EA8 + 데이터].
		 */
		float[] world() {
			float[][] lw = anim.sampleRecords(sk, time, false).world(sk);
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
		/**
		 * 원작 상태 행렬 (눈 +0x30, 회전 mYaw/mPitch) 과 저장된 카메라 요/피치 (+0x80/+0x84 = stYaw/stPitch), 화면 각 (+0x88/+0x8C), 거리 +0x90.
		 * 새 상태(0x1F3A38)는 직전 상태를 복사하고, 리셋(레벨 시작·Warp, 0x1EDF48)은 템플릿 0x1F2500 (단위 행렬, 원점, +0x80 −90, +0x90 200, 화면 각 0).
		 * 정상 상태에서는 마지막 0x1F1108 이 행렬을 (+0x80, +0x84) 로 다시 만들므로 mYaw = stYaw — 템플릿만 다르다.
		 */
		final Vector3f stEye = new Vector3f();
		float mYaw, mPitch, stYaw = -90, stPitch, stSa, stSb, stDist = 200;
		/** 리셋 템플릿 상태 그대로 (아직 한 번도 갱신 안 됨). */
		boolean template = true;
		/** 이번 프레임 머리(0x1F29A0, 데드존 자르기 뒤)의 화면 각과 대상까지 거리, 정면. */
		float hSa, hSb, hDist;
		final Vector3f hFwd = new Vector3f(0, 0, -1);
		/** 전환 시간이 있으면(상태 +0x4C &gt; 0) 첫 표본 비용에 −100·(normalize(기준점 − P) · 정면) (0x1F23E0). */
		boolean fwdBonus;

		Shot(LevelData.PlacedCamera cam) {
			this.cam = cam;
		}

		/** 직전 상태 복사 (0x1F3A38). 직전이 엔진 Follow 면 그 화면에서. */
		void copyState(Shot o, Pose view) {
			if (o.cam == null) {
				commit(view);
				return;
			}
			stEye.set(o.stEye);
			mYaw = o.mYaw;
			mPitch = o.mPitch;
			stYaw = o.stYaw;
			stPitch = o.stPitch;
			stSa = o.stSa;
			stSb = o.stSb;
			stDist = o.stDist;
			template = o.template;
		}

		/** 이번 프레임 결과를 상태 행렬로 (마지막 0x1F1108). */
		void commit(Pose p) {
			p.eye(stEye);
			mYaw = stYaw = p.yaw;
			mPitch = stPitch = p.pitch;
			stSa = p.sa;
			stSb = p.sb;
			stDist = p.dist;
			template = false;
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
	/** 점프 중 대상 높이 고정 오프셋 (플래그 0x800/0x4000, 0x1F2850 +0x5C). */
	private float jumpOffset;
	private String last = "";

	public CameraRig(LevelData data) {
		this.data = data;
		bindAnimations(data);
	}

	/**
	 * 카메라를 가진 오브젝트에 ANM 이 있으면 그 카메라는 관절 애니를 따른다 (LevelData 와 같은 관절 규칙). 다른 슬롯 레벨 것도 묶는다
	 * ({@link #setOther}) — 원작 카메라 노드는 슬롯과 무관하게 자기 GO 관절을 따른다 (ATHN04B 가 현재인 채 ATHN04A 이웃 스크립트가 부르는
	 * SuicideCam / gojumppan JumpPan, 43 §18).
	 */
	private void bindAnimations(LevelData data) {
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
		LevelData.PlacedCamera c = camera(name);
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

	/**
	 * 추가 카메라 대상 슬롯 (원작 0x1F3F78: 표 0x304790 10칸, +0x4C 기본 7, +0x48 가중). 판도라 수호자 BMI 생성자 0x1C0378 이 머리 관절
	 * BMI01JOCHEA1 을 가중 "Camera Target Weight"(0.35)·반지름 "Camera Target Radius"·16 으로 등록한다. CAM 0x10000 카메라의 대상 T
	 * (0x1F26E8) = bit0 슬롯 가중 평균 — 영웅 기본 슬롯(가중 1, 발 + 24)과 이 슬롯. p == null 이면 지움 (소멸 0x1C06F0 → 0x1F4040).
	 */
	public void setTargetSlot(Vector3f p, float weight) {
		if (p == null || weight <= 0) {
			slotWeight = 0;
			return;
		}
		slot.set(p);
		slotWeight = weight;
	}

	private final Vector3f slot = new Vector3f();
	private float slotWeight;

	/** CAM 0x10000 이면 영웅 대상과 추가 슬롯의 가중 평균. */
	private Vector3f multiTarget(Shot s, Vector3f t) {
		if (slotWeight <= 0 || s.cam == null || (s.cam.def().p().flags() & CameraDef.F_MULTI_TARGET) == 0) {
			return t;
		}
		return new Vector3f(t).add(new Vector3f(slot).mul(slotWeight)).div(1 + slotWeight);
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

	private int heroBits = 0x1;
	private boolean heroCombat;

	/**
	 * 영웅 이동 상태 (원작 영웅 +0x174 하위 11 비트: 0x1/0x2 지상, 0x4 점프 공중, 0x8 떨어짐(놓음·발밑 없음), 0x10 턱 매달리기, 0x20 벽(오르기·좁은 턱·사다리),
	 * 0x40 날기, 0x80/0x100/0x200 물, 0x400 줄) 과 전투 (영웅이 적 무리(0x1B5D00, 영웅 대상 슬롯 +8)에 들어 있고 그 무리의 적 수 +0x1A0 &gt; 0).
	 */
	public void setHeroState(int bits174, boolean combat) {
		heroBits = bits174;
		heroCombat = combat;
	}

	/**
	 * 0x1EE510: 카메라 후보 마스크. bit0 = 전투, 0x2 = 지상, 0x4 = 줄, 0x8 = 벽·턱, 0x10 = 날기, 0x20 = 물, 0x40 = 점프, 0x80 = 떨어짐.
	 * CAM 플래그 하위 바이트와 AND 가 0 이면 그 카메라는 후보에서 빠진다 (예: FallCam 0x80, LadderCam 0x08, camRopes 0x04, SwimCam 0x20).
	 */
	public static int stateMask(int b, boolean combat) {
		int m = combat ? 1 : 0;
		if ((b & 0x3) != 0) {
			m |= 0x2;
		}
		if ((b & 0x400) != 0) {
			m |= 0x4;
		}
		if ((b & 0x30) != 0) {
			m |= 0x8;
		}
		if ((b & 0x40) != 0) {
			m |= 0x10;
		}
		if ((b & 0x380) != 0) {
			m |= 0x20;
		}
		if ((b & 0x4) != 0) {
			m |= 0x40;
		}
		if ((b & 0x8) != 0) {
			m |= 0x80;
		}
		return m;
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

	/**
	 * 다른 레벨 슬롯 (이웃 / 숙주 / Warp 짝). 원작은 두 슬롯 레벨이 함께 상주해 볼륨 질의(충돌 월드 0x29C978)와 RCM_ 이름 조회(0x17A7B8,
	 * 전역 GO 검색)에 둘 다 걸린다 — 예: ATHN02D(메두사 방) 는 자기 CMV 를 생성 시 끄고 숙주 ATHN02C 의 Room2Cam 들이 방을 잡는다,
	 * ATHN02D magicPlace 의 CameraName "Room2Cam3" 은 ATHN02C 카메라. null 이면 없음.
	 */
	public void setOther(LevelData other) {
		LevelData old = this.other;
		this.other = other == data ? null : other;
		if (this.other != null && this.other != old) {
			bindAnimations(this.other);
		}
		if (old == null || old == this.other) {
			return;
		}
		anims.keySet().removeIf(c -> old.placedCameras.contains(c));
		// 레벨 내리기 (Hero_LoadWads → 0x1EE240): 현재 카메라가 내려가는 레벨 것이면 0x1EDF48(0) — 상태 링을 템플릿으로, 볼륨 선택을 전환 0 으로,
		// CMV 질의 켬 (§7.1). 내려간 레벨 카메라는 활성 목록에서도 빠진다 (GO 가 없어짐).
		active.removeIf(c -> old.placedCameras.contains(c));
		Shot s = shots.isEmpty() ? null : shots.getLast();
		if (s != null && s.cam != null && old.placedCameras.contains(s.cam)) {
			shots.clear();
			volumeQuery = true;
		}
	}

	private LevelData other;

	/** 이름(RCM_ 선택)으로 카메라: 이 레벨 먼저, 없으면 다른 슬롯 레벨. */
	private LevelData.PlacedCamera camera(String name) {
		LevelData.PlacedCamera c = data.camera(name);
		return c == null && other != null ? other.camera(name) : c;
	}

	/**
	 * 이음(LoadCheck)·걷기 전환에서 카메라 상태 넘기기. 원작은 LoadCheck 가 "현재 레벨" 표시만 바꾸고 카메라 관리자(0x29F16C)는 그대로라
	 * 활성 목록·현재 카메라·상태가 이어진다 (떠나온 레벨이 다른 슬롯에 남아 그 CMV 도 계속 질의됨). levels[i] = 카메라가 속한 WAD 이름.
	 */
	public record Handoff(List<String> active, List<String> levels, String current, String currentLevel, Pose pose, float railT) {
	}

	public Handoff handoff() {
		List<String> names = new ArrayList<>(), lv = new ArrayList<>();
		for (LevelData.PlacedCamera c : active) {
			names.add(c.def().shortName());
			lv.add(levelOf(c));
		}
		Shot s = shots.isEmpty() ? null : shots.getLast();
		Pose p = new Pose();
		p.set(cur);
		return new Handoff(names, lv, s == null || s.cam == null ? null : s.cam.def().shortName(),
				s == null || s.cam == null ? null : levelOf(s.cam), p, s == null ? -1 : s.t);
	}

	/**
	 * 이음 도착 재현 (`--arrive FROM@x,y,z`, 넘겨받은 카메라 상태 없음) 전용 [근사: 하네스]. 원작은 이음에서 카메라 관리자가 그대로라 (0x29F16C)
	 * 도착점에 CMV 후보가 없으면 떠나온 레벨에서 쓰던 카메라가 남는다 (0x1EE8E0: 후보 0 이면 현재 상태 유지). 그 상태가 없으므로 도착점에서 가장 가까운
	 * (반지름 16 단위 수평 고리 ≤ 512, 위 ≤ 1024 · 아래 ≤ 256) 떠나온 레벨 CMV 후보 자리 — 걸어·떨어져 들어온 쪽 — 에서 먼저 한 번 고른다. 도착점에 후보가 있으면 아무것도 하지 않는다.
	 * 첫 {@link #update} 전에 부른다. 반환 = 고른 자리 (없으면 null).
	 */
	public Vector3f seedApproach(Vector3f hero, float heroYaw, LevelData from) {
		if (from == null || !shots.isEmpty() || hasCandidate(new Vector3f(hero).add(0, TARGET_HEIGHT, 0), null)) {
			return null;
		}
		for (float r = 16; r <= 1024; r += 16) {
			for (int k = 0; k < 34; k++) { // 수평 고리 32 + 바로 위·아래 (떨어져 들어온 걷기 도착: HDES01D Dcam11 → HDES01E; 아래는 짧게 — HDES01D ← 01C 의 1024 아래 ClimbCam3 제외)
				double a = k * Math.PI / 16;
				Vector3f p = k < 32 ? new Vector3f(hero.x + (float) (r * Math.sin(a)), hero.y, hero.z + (float) (r * Math.cos(a)))
						: new Vector3f(hero.x, hero.y + (k == 32 ? r : -r), hero.z);
				if ((k < 32 && r > 512) || (k == 33 && r > 256) || !hasCandidate(new Vector3f(p).add(0, TARGET_HEIGHT, 0), from)) {
					continue;
				}
				update(0, p, heroYaw, false);
				return p;
			}
		}
		return null;
	}

	/** 점 t (대상 높이) 를 포함하는 켜진 CMV 가 있는지 (only = 그 레벨만, null = 두 슬롯 모두), 상태 마스크 통과. */
	private boolean hasCandidate(Vector3f t, LevelData only) {
		int mask = stateMask(heroBits, heroCombat);
		for (LevelData lv : only != null ? List.of(only) : other != null ? List.of(data, other) : List.of(data)) {
			for (LevelData.PlacedVolume v : lv.volumes) {
				if (v.hull().kind != 1 || disabled.contains(v.owner().toLowerCase())) {
					continue;
				}
				float[] l = new float[3];
				Mat4.transformPoint(v.inverse(), t.x, t.y, t.z, l);
				for (int i = 0; i < v.hull().hulls.size(); i++) {
					if (inside(v.hull().hulls.get(i), l)) {
						String n = v.hull().nameOf(i);
						LevelData.PlacedCamera c = n == null ? null : lv.camera(n) != null ? lv.camera(n) : camera(n);
						if (c != null && (c.def().p().flags() & mask) != 0) {
							return true;
						}
					}
				}
			}
		}
		return false;
	}

	/** {@link #handoff()} 를 이어받는다 (새 레벨 장면의 첫 갱신 전). */
	public void resume(Handoff h) {
		active.clear();
		for (int i = 0; i < h.active().size(); i++) {
			LevelData.PlacedCamera c = camera(h.active().get(i), h.levels().get(i));
			if (c != null && !active.contains(c)) {
				active.add(c);
			}
		}
		shots.clear();
		Shot s = new Shot(h.current() == null ? null : camera(h.current(), h.currentLevel()));
		s.pose.set(h.pose());
		s.commit(s.pose);
		s.t = h.railT();
		s.init = true;
		shots.add(s);
		if (s.cam == null) {
			follow = s;
		}
		cur.set(h.pose());
		prev.set(h.pose());
		first = false;
		last = current();
	}

	private String levelOf(LevelData.PlacedCamera c) {
		return wadName(data.placedCameras.contains(c) || other == null ? data : other);
	}

	private static String wadName(LevelData d) {
		return d.wad == null ? "" : d.wad.name();
	}

	private LevelData.PlacedCamera camera(String name, String level) {
		if (other != null && wadName(other).equalsIgnoreCase(level)) {
			LevelData.PlacedCamera c = other.camera(name);
			if (c != null) {
				return c;
			}
		}
		return camera(name);
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

	/**
	 * 현재 카메라의 필터 템플릿 번호 (CAM 0xB0, 활성화 0x1F3A38 이 그 템플릿 색을 카메라 상태에 복사 — 전환 보간 없음, 45 문서 §1).
	 * 원작 카메라가 없으면 (엔진 Follow) −1.
	 */
	public int filter() {
		Shot s = shots.isEmpty() ? null : shots.getLast();
		return s == null || s.cam == null ? -1 : s.cam.def().filter();
	}

	public Pose pose() {
		return cur;
	}

	/** 고정 틱 갱신. hero = 발 위치, heroYaw = 진행 방향(라디안), airborne = 공중. */
	public void update(float dt, Vector3f hero, float heroYaw, boolean airborne) {
		overrideUpdate(dt);
		frameUpdate(dt);
		prev.set(cur);
		prevFrameT.set(frameT);
		heroFeet.set(hero);
		Vector3f t = target(hero, airborne);
		cut = false;
		if (!framedLast) {
			select(t); // 0x1EFB58: 프레이밍 줌 (0x1EF078) 을 건 프레임은 선택 생략 — 잡기·처형 클로즈업 동안 카메라가 바뀌지 않는다
		}
		// 덮어쓰기 중 대상 T = 영웅 슬롯 + (AimJoint − 영웅 슬롯)·가중치 (0x1F26E8 앞머리 — 다중 대상 0x10000 보다 먼저)
		Vector3f tSolve = overActive ? new Vector3f(t).lerp(overAim, overW) : t;
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
		Pose out = null, vel = null;
		for (Shot s : shots) {
			if (!(overActive && s.cam != null && (s.cam.def().p().flags() & F_FRAME_TARGET) != 0 && s.init)) {
				solve(s, tSolve, heroYaw, dt); // 0x1F388C: 덮어쓰기 (관리자 +0x7AC) 중 0x2000 카메라는 위치·회전 풀기 생략 — 눈이 그 자리에 멈춘다
			}
			if (out == null) {
				out = new Pose();
				out.set(s.pose);
			} else {
				Pose nv = new Pose();
				blend(out, vel, s.pose, s.blend <= 0 ? 1 : Math.min(1, s.elapsed / s.blend), out, nv); // 0x1EF3F0 → 0x1F3C90 연쇄 (관리자 +0x10 누적)
				vel = nv;
			}
		}
		cur.set(out);
		frameT.set(overActive ? tSolve : cur.target);
		if (first) {
			prev.set(cur);
			prevFrameT.set(frameT);
			first = false;
		}
		Shot top = shots.isEmpty() ? null : shots.getLast();
		framedLast = zoomCur > 0.01f && (top == null || top.cam == null || (top.cam.def().p().flags() & F_FRAME_TARGET) != 0);
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
		lerp(prev, cur, alpha, p);
		p.eye(c.eye);
		p.forward(c.target).mul(Math.max(1, p.dist)).add(c.eye); // 시선 = 카메라 정면 (대상은 화면 sa/sb 자리)
		c.fovDeg = p.fovDeg;
		c.up.set(0, 1, 0);
		frame(c, p, new Vector3f(prevFrameT).lerp(frameT, alpha), zoomPrev + (zoomCur - zoomPrev) * alpha);
	}

	// ---- 대상 프레이밍 줌 (전투 이벤트 11 CAMERA_FRAME, 0x1EF948 → 0x1EF078) ----

	/** 카메라 정의 플래그 0x2000: 프레이밍 줌을 받는 카메라 (ATHN01A/01B/02A 대부분, LiftCam 0x41xxx 는 아님). */
	static final int F_FRAME_TARGET = 0x2000;
	/** 관리자 +0x7A8 기본 1.5 ×16 (0x1EDC88). */
	static final float FRAME_RADIUS = 1.5f * 16f;
	private float zoomTgt, zoomCur, zoomPrev;
	/** 프레이밍 대상 (상태 +0x70 = 이번 틱 T, 덮어쓰기면 AimJoint 쪽으로 섞인 T) — 렌더 보간용 직전 값. */
	private final Vector3f frameT = new Vector3f(), prevFrameT = new Vector3f();
	/** 지난 틱에 프레이밍 줌을 걸었는지 (0x1EF620 의 s2 → 선택 생략). */
	private boolean framedLast;

	/** 이번 틱 목표 가중치 (관리자 +0x7A4 — 이벤트가 매 프레임 넣고 카메라가 읽은 뒤 0 으로, 0x1EF9A4). 덮어쓰기 중엔 무시 (0x2070EC: 이벤트 23 이 있으면 11 을 안 넣음). */
	public void frameTarget(float b) {
		if (!overActive) {
			zoomTgt = b;
		}
	}

	private void frameUpdate(float dt) {
		zoomPrev = zoomCur;
		zoomCur += (zoomTgt - zoomCur) * (1 - (float) Math.pow(0.9, dt * 60)); // 프레임당 ×0.1 (0x3DCCCCCD)
		zoomTgt = 0;
	}

	// ---- 전투 카메라 덮어쓰기 (이벤트 23 goCamera, 표 0x304AB0, 매 프레임 0x1F46D0) ----

	private boolean overActive, overSet;
	private float overW, overR = FRAME_RADIUS;
	private final Vector3f overAim = new Vector3f(), overUp = new Vector3f(0, 1, 0);

	/**
	 * 이번 틱 전투 카메라 (이벤트 23 창 동안 매 틱, {@link devple.gow.engine.combat.CombatCamera}). aim = AimJoint 월드, radius = |Width − AimJoint|,
	 * up = CamPos 관절 Y 축. 0x1F46D0: 가중치 += 0.01/프레임 (최대 1) → 관리자 +0x7AC = 1 (0x2000 카메라 풀기 멈춤), +0x7A8 = 반경/16, +0x790 = 위 벡터,
	 * +0x7A4 (줌 목표) = 가중치. 부르지 않은 틱이 오면 해제 — 가중치 0, 줌 목표·현재 0 (0x1EF058, 바로 돌아옴), 반경 1.5·16, 위 (0, 1, 0).
	 */
	public void combatCamera(Vector3f aim, float radius, Vector3f up) {
		overSet = true;
		overAim.set(aim);
		overR = radius;
		overUp.set(up);
	}

	/** 지금 덮어쓰기 가중치 (0x304AC4 — 0x205280 의 몸 회전 배율). 없으면 0. */
	public float combatWeight() {
		return overActive ? overW : 0;
	}

	private void overrideUpdate(float dt) {
		if (overSet) {
			if (!overActive) {
				overActive = true;
				overW = 0;
			}
			overW = Math.min(1, overW + 0.01f * dt * 60); // 0x1F4718: +0.01 (0x3C23D70A)
			zoomTgt = overW; // 0x1EF040
		} else if (overActive) {
			overActive = false;
			overW = 0;
			zoomTgt = zoomCur = zoomPrev = 0; // 0x1EF058(0): +0x7A4 = +0x7A0 = 0
			overR = FRAME_RADIUS;
			overUp.set(0, 1, 0);
		}
		overSet = false;
	}

	/**
	 * 0x1EF078: 대상 T 를 반경 R (기본 1.5·16, 덮어쓰기면 |Width − AimJoint|) 이 화면을 채우게 — 가로 FOV += (2·atan(R/|눈−T|) − FOV)·w,
	 * 카메라 회전은 룩앳 (T, 위 = 관리자 +0x790 — 기본 (0, 1, 0), 덮어쓰기면 CamPos 관절 Y 축 = 롤) 쪽으로 사원수 slerp(w). 눈은 그대로.
	 * 가중치 &gt; 0.01 이고 지금 카메라가 0x2000 일 때만 (엔진 Follow 는 받는다 [추정]).
	 */
	private void frame(Camera c, Pose p, Vector3f t, float w) {
		if (w <= 0.01f) {
			return;
		}
		Shot s = shots.isEmpty() ? null : shots.getLast();
		if (s != null && s.cam != null && (s.cam.def().p().flags() & F_FRAME_TARGET) == 0) {
			return;
		}
		float d = Math.max(1f, c.eye.distance(t));
		double h = 2 * Math.atan(Math.tan(Math.toRadians(p.fovDeg) / 2) / 0.75); // 세로(4:3) → 원작 가로
		h += (2 * Math.atan(overR / d) - h) * w;
		c.fovDeg = verticalFov((float) h);
		Vector3f f0 = new Vector3f(c.target).sub(c.eye).normalize();
		Vector3f f1 = new Vector3f(t).sub(c.eye).normalize();
		org.joml.Quaternionf q0 = new org.joml.Quaternionf().lookAlong(f0, new Vector3f(0, 1, 0));
		org.joml.Quaternionf q1 = new org.joml.Quaternionf().lookAlong(f1, overUp);
		org.joml.Quaternionf q = q0.slerp(q1, w, new org.joml.Quaternionf()).conjugate(); // 뷰 회전 → 카메라 회전
		Vector3f f = q.transform(new Vector3f(0, 0, -1)).normalize();
		q.transform(c.up.set(0, 1, 0)).normalize();
		c.target.set(c.eye).add(f.mul(Math.max(1, p.dist)));
	}

	/**
	 * 카메라 대상 T. ① 영웅 대상 슬롯 (0x1DCC20: 오프셋 (0, 1.5·16, 0), 반지름 +0x40 = 1.6) 은 매 프레임 0x1F4380 이 원하는 자리에서
	 * 반지름 밖으로 벗어난 만큼만 끌려온다 (끈 1.6 — ×16 아님). ② CAM 0x800/0x4000 (0x1F3874 → 0x1F2850, 상태별 +0x58 단계 / +0x5C 오프셋 / +0x60 이륙 높이):
	 * 공중 (영웅 +0x174 &amp; 0x4C, 날기 0x40 제외) 첫 프레임에 이륙 높이 = 직전 T 높이, 공중 동안 오프셋 = max(0, T.y − 이륙 높이) — T 는 이륙 높이 위로 오르지 않고
	 * 아래로는 따라 내려간다. 착지 프레임에 같은 식으로 오프셋 (더 낮은 곳이면 0) 을 잡고, 그 뒤 프레임당 ×0.9 로 푼다 (0x1F2968).
	 * [미해석: 영웅 +0x2D8 bit 0x8 — 0x800 카메라는 그 비트가 서면 ×0.95 로 풀고, 0x4000 만 있는 카메라는 그 비트가 있어야 고정. 엔진은 둘 다 0x800 처럼]
	 */
	private Vector3f target(Vector3f hero, boolean airborne) {
		desired.set(hero.x, hero.y + TARGET_HEIGHT, hero.z);
		float d = slotInit ? desired.distance(slotPos) : 0;
		if (!slotInit) {
			slotPos.set(desired);
			slotInit = true;
		} else if (d > SLOT_RADIUS) {
			slotPos.lerp(desired, (d - SLOT_RADIUS) / d);
		}
		Vector3f t = new Vector3f(slotPos);
		if (!holdJump()) {
			lastT.set(t);
			return t;
		}
		if (airborne) {
			if (jumpMode == 0) {
				jumpMode = 1;
				jumpGround = lastT.y;
			}
			jumpOffset = Math.max(0, t.y - jumpGround);
		} else if (jumpMode != 0) {
			jumpOffset = Math.max(0, t.y - jumpGround);
			jumpMode = 0;
		} else {
			jumpOffset *= 0.9f;
		}
		t.y -= jumpOffset;
		lastT.set(t);
		return t;
	}

	/** 영웅 대상 슬롯 반지름 (0x1DCC20 +0x40 = 1.6). */
	static final float SLOT_RADIUS = 1.6f;
	private final Vector3f desired = new Vector3f(), slotPos = new Vector3f(), lastT = new Vector3f();
	private boolean slotInit;
	private int jumpMode;
	private float jumpGround;

	private boolean holdJump() {
		Shot s = shots.isEmpty() ? null : shots.getLast();
		return s != null && s.cam != null && (s.cam.def().p().flags() & CameraDef.F_HOLD_JUMP) != 0;
	}

	// ---- 선택 (0x1EE8E0) ----

	private void select(Vector3f t) {
		List<LevelData.PlacedCamera> cand = new ArrayList<>(requests);
		requests.clear();
		if (volumeQuery) {
			for (LevelData lv : other != null ? List.of(data, other) : List.of(data)) {
				for (LevelData.PlacedVolume v : lv.volumes) {
					if (v.hull().kind != 1 || disabled.contains(v.owner().toLowerCase())) {
						continue;
					}
					float[] l = new float[3];
					Mat4.transformPoint(v.inverse(), t.x, t.y, t.z, l);
					BallHull h = v.hull();
					for (int i = 0; i < h.hulls.size(); i++) {
						if (System.getProperty("gow.debug.cmv") != null && shots.isEmpty()) {
							float worst = -1e9f;
							float[] wp = null;
							for (float[] pl : h.hulls.get(i).planes()) {
								float dd = h.hulls.get(i).planes().size() == 1 ? 0 : pl[0] * l[0] + pl[1] * l[1] + pl[2] * l[2] + pl[3];
								if (dd > worst) {
									worst = dd;
									wp = pl;
								}
							}
							System.out.printf(java.util.Locale.ROOT, "[CMV] %s hull %d %s 밖 %.1f 평면 %s 로컬 %s%n", v.owner(), i, h.nameOf(i), worst,
									java.util.Arrays.toString(wp), java.util.Arrays.toString(l));
						}
						if (inside(h.hulls.get(i), l)) {
							String n = h.nameOf(i);
							LevelData.PlacedCamera c = n == null ? null : lv.camera(n) != null ? lv.camera(n) : camera(n);
							// ANM 관절 카메라도 볼륨 후보 (ATHN01A IdleCam/Idle2Cam 인트로 팬 — 스크립트 Vis Attr11_1 이 9.5/13 초에 볼륨을 끈다, 30 §8)
							if (c != null && !cand.contains(c)) {
								cand.add(c);
							}
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
		// 0x1EEC3C–0x1EED58: best = −1, bestPri = INT_MIN. 전환표에서 현재 카메라 이름 (+0x808) 항목의 +0x1C 가 0 아니면 ("prefer")
		// 우선순위가 지금 최선과 같을 때만 목록에서 최선 자리와 맞바꾸고 최선 색인은 그 자리 (= 원래 최선 카메라) — 즉 그 카메라는 이번 선택에서 이기지 못한다.
		// 우선순위가 다르면 (또는 아직 최선이 없으면) 건너뛴다. 그 밖은 우선순위 ≥ 최선이면 최선 (동순위는 뒤엣것).
		// (이전 엔진은 prefer 를 "동순위 우선" 으로 읽어 서로 prefer 인 두 카메라 — PAND00D Lv1TunCam3/4 — 가 매 프레임 번갈았다, 43 §13.10.)
		int bestIdx = -1;
		int bestPri = Integer.MIN_VALUE;
		int mask = stateMask(heroBits, heroCombat);
		for (int i = 0; i < active.size(); i++) {
			LevelData.PlacedCamera c = active.get(i);
			if ((c.def().p().flags() & mask) == 0) {
				continue; // 0x1EEC9C: CAM 플래그 하위 바이트 & 영웅 상태 마스크 0x1EE510 = 0 이면 이번 선택에서 건너뜀 (활성 목록에는 남는다)
			}
			int pri = c.def().p().priority();
			CameraDef.Transition ptr = transition(c, curName);
			if (ptr != null && ptr.prefer()) {
				if (pri == bestPri && bestIdx >= 0) {
					active.set(i, active.get(bestIdx));
					active.set(bestIdx, c);
					bestIdx = i;
				}
				continue;
			}
			if (pri >= bestPri) {
				bestIdx = i;
				bestPri = pri;
			}
		}
		LevelData.PlacedCamera best = bestIdx >= 0 ? active.get(bestIdx) : null;
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
		if (System.getProperty("gow.debug") != null) {
			StringBuilder sb = new StringBuilder();
			for (LevelData.PlacedCamera c : active) {
				CameraDef.Params cp = c.def().p();
				sb.append(String.format(java.util.Locale.ROOT, " %s(pri %d fl 0x%x p%d y%d rail %s)", c.def().shortName(), cp.priority(), cp.flags(),
						cp.pitchMode(), cp.yawMode(), c.rail() != null));
			}
			System.out.printf("[카메라 후보] T=%s:%s%n", t, sb);
		}
		Shot s = new Shot(best);
		CamAnim a = anims.get(best);
		if (a != null && (best.def().p().flags() & CameraDef.F_RESTART_ANIM) != 0) {
			a.time = 0; // 0x1F3B60: 활성화 시 소유 오브젝트 애니 처음부터
		}
		CameraDef.Transition tr = transition(best, curName);
		s.blend = curShot == null ? 0 : tr != null ? tr.blend() : best.def().p().blend();
		if (curShot != null) { // 새 상태 = 직전 상태 복사 (0x1F3A38). 리셋(레벨 시작·Warp, 0x1EDF48)이면 템플릿 그대로 (Shot 기본값)
			s.copyState(curShot, cur);
			s.fwdBonus = s.blend > 0;
			if (curShot.cam != null && curShot.t >= 0 && best.rail() != null && best.rail() == curShot.cam.rail()) {
				s.t = curShot.t; // 같은 레일이면 t 를 이어받는다 (0x1F3A38: +0x44 레일이 같으면 +0x48 유지)
			}
		}
		if (s.blend <= 0) {
			shots.clear();
		}
		shots.add(s);
		cut = curShot != null && s.blend <= CUT_TIME; // 0x1EF310: 전환 ≤ 0.1초 → 영웅 입력 기준 유지 0x1DCE80
	}

	/** 0x29E15C: 이 이하 전환 시간은 컷 (입력 기준 유지). */
	static final float CUT_TIME = 0.1f;
	private boolean cut;

	/** 마지막 {@link #update} 의 선택이 컷이었는지 (리셋 첫 선택 제외) — GameScene 이 입력 기준 유지에 쓴다 (30 문서 §9.1). */
	public boolean cutThisTick() {
		return cut;
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
		solveShot(s, t, heroYaw, dt);
		s.commit(s.pose);
	}

	private void solveShot(Shot s, Vector3f t, float heroYaw, float dt) {
		if (!overActive) {
			t = multiTarget(s, t);
		}
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
			Vector3f at;
			if ((cp.flags() & CameraDef.F_FIXED_LOOK) != 0) {
				// 고정 주시: 카메라 행렬 × (0xC0..0xC8 ×16) — 대개 (0,0,-80): 정면 고정
				float[] o = new float[3];
				float[] off = cp.lookOffset();
				Mat4.transformPoint(w, off[0], off[1], off[2], o);
				at = new Vector3f(o[0], o[1], o[2]);
			} else {
				// 0x100·0x40000 둘 다 없음: 원작 0x1F3768 은 0x100 이 없으면 0x40000 과 무관하게 카메라 노드 월드 행렬 (관절 애니 포함, +0x70) 을
				// 상태 행렬로 복사하고 0x1F11A0 으로 각을 뽑는다 (0x1F3904..0x1F3960) — 영웅을 보지 않는다 (0x40000 은 대상 T 만 정함) [확실].
				// 예: ATHN04A SuicideCam (0x14FE, ANM JumpPan 11 초) 은 난간에서 떨어진 여자 몸 위로 내려가 내려다본다 (영상 5929–5935).
				// 예전엔 영웅을 봐서 그 크레인 샷이 난간 위 크레토스 등이 됐다 (전 WAD 2916 카메라 중 28 개, 43 §18).
				at = new Vector3f(-w[8], -w[9], -w[10]).normalize(80).add(camPos);
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
		Vector3f ref = head(s, t, cp);
		// 모드 1 거리 = 함수 머리 이전 상태의 깊이 +0x90·cos(+0x88)·cos(+0x8C) (0x1F2A40, 리셋 템플릿 200)
		float depth0 = (float) (s.stDist * Math.cos(Math.toRadians(s.stSa)) * Math.cos(Math.toRadians(s.stSb)));
		if (railMode && s.cam.rail() != null) {
			s.t = railParam(s, ref, dYaw, dPitch, limited, dt);
			float[] r = new float[3];
			s.cam.rail().eval(s.t, r);
			pivot.set(r[0], r[1], r[2]);
		}
		if (System.getProperty("gow.debug.cam") != null && !s.init) {
			System.out.printf(java.util.Locale.ROOT, "[카메라 풀기] %s pos %s fwd %s distScale %.3f rail [%.1f, %.1f] dist [%.1f, %.1f] yaw %.1f±%.1f pitch %.1f±%.1f 데드존 %s 기준점 %s 화면각 (%.1f, %.1f)%n",
					s.cam.def().shortName(), camPos, fwd, cp.distScale(), cp.railMin(), cp.railMax(), cp.distMin(), cp.distMax(), dYaw, cp.yawRange(),
					dPitch, cp.pitchRange(), java.util.Arrays.toString(cp.deadZone()), ref, s.hSa, s.hSb);
		}
		// 모드 2·3 의 요/피치·거리는 대상이 아니라 기준점에서 잰다 (0x1F2FD4: 0x2B0 기준점 − 0x2F0 피벗)
		Vector3f v = new Vector3f(ref).sub(pivot);
		if (cp.distScale() < 0) {
			v.negate();
		}
		float pivotYaw = yawOf(v), pivotPitch = pitchOf(v);
		float yaw = switch (ym) {
			case 2, 3 -> pivotYaw;
			default -> dYaw;
		};
		float pitch = switch (pm) {
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
			dist = depth0;
		}
		if (cp.distScale() >= 0) {
			dist = Math.max(cp.distMin(), Math.min(cp.distMax(), dist));
		}
		dist = Math.abs(dist); // 0x1F3324: 깊이 (정면 축 방향 거리)
		Vector3f d0 = dir(s.hSa, s.hSb, new Vector3f());
		if (!limited) {
			// 0x1F36D0: +0x90 = 깊이 / (cos sa · cos sb) → 0x1F1108: 눈 = T − +0x90·R·방향(sa, sb) — 대상이 화면 (sa, sb) 자리, 정면 깊이 = dist
			double c = Math.cos(Math.toRadians(s.hSa)) * Math.cos(Math.toRadians(s.hSb));
			p.target.set(t);
			p.yaw = yaw;
			p.pitch = pitch;
			p.sa = s.hSa;
			p.sb = s.hSb;
			p.dist = (float) (dist / Math.max(1e-3, c));
		} else {
			// 0x1F3640 / 0x1F3354: 눈 = T − 깊이·R·방향(sa, sb) 를 먼저 정하고, 카메라 회전만 설계 각 ± 범위로 자른다 — 눈은 움직이지 않는다
			Vector3f eye = rotate(yaw, pitch, d0, new Vector3f()).mul(-dist).add(t);
			Vector3f[] frame = railMode ? railFrame(s.cam.rail(), s.t, cp) : null;
			if (frame != null) {
				Vector3f l = toLocal(frame, dir(yaw, pitch, new Vector3f()));
				float ly = clampAngle(yawOf(l), dYaw, cp.yawRange());
				float lp = Math.max(dPitch - cp.pitchRange(), Math.min(dPitch + cp.pitchRange(), pitchOf(l)));
				Vector3f w2 = toWorld(frame, dir(ly, lp, new Vector3f()));
				yaw = yawOf(w2);
				pitch = pitchOf(w2);
			} else {
				yaw = clampAngle(yaw, dYaw, cp.yawRange());
				pitch = Math.max(dPitch - cp.pitchRange(), Math.min(dPitch + cp.pitchRange(), pitch));
			}
			p.aim(eye, yaw, pitch, t); // 0x1F11A0: 화면 각·거리를 새 회전에서 다시
		}
		if (System.getProperty("gow.debug.cam2") != null) { // 매 프레임 풀기 (기준점·화면 각)
			System.out.printf(java.util.Locale.ROOT, "[풀기2] %s t=%.3f pivot=%s ref=%s yaw0=%.1f pitch0=%.1f -> yaw=%.1f pitch=%.1f sa=%.1f sb=%.1f dist=%.1f eye=%s%n",
					s.cam.def().shortName(), s.t, pivot, ref, pivotYaw, pivotPitch, p.yaw, p.pitch, p.sa, p.sb, p.dist, p.eye(new Vector3f()));
		}
		s.init = true;
	}

	/**
	 * 0x1F29A0 머리 (0x1F2A34–0x1F2BBC, 0x1F2C50): 상태 행렬로 본 대상 T 의 화면 각 (sa = atan2(−x, −z), sb = 피치, 0x1F0DC8) 과 거리를
	 * 구하고, 새 카메라의 화면 데드존 (세로 [atan(0.75·tan(f/2)·0x9C), atan(0.75·tan(f/2)·0x98)], 가로 [−atan(tan(f/2)·0xA4), −atan(tan(f/2)·0xA0)],
	 * f = 카메라 가로 화각 GO+0xF8) 밖이면 각을 자르고 0x1F1108 로 행렬을 저장된 (+0x80, +0x84) 회전으로 다시 만들어 눈 = T − 거리·R·방향(sa, sb)
	 * 로 옮긴다. 기준점 = 눈 + 정면·(거리·cos sa·cos sb) — 레일 첫 표본·지역 탐색과 모드 2·3 의 각·거리가 이 점을 쓴다.
	 * 데드존이 0 이면 (CAM 의 대부분) 기준점 = T. 리셋 템플릿 (단위 행렬, 원점) 도 같은 규칙이라 데드존 0 이면 T 다.
	 */
	private Vector3f head(Shot s, Vector3f t, CameraDef.Params cp) {
		Vector3f l = unrotate(s.mYaw, s.mPitch, new Vector3f(t).sub(s.stEye));
		float d0 = l.length();
		float a = d0 < 1e-6f ? 0 : yawOf(l), b = d0 < 1e-6f ? 0 : pitchOf(l);
		double tan = Math.tan(cp.fovRad() / 2);
		// 리셋 템플릿(원점·단위 행렬)이면 해독대로는 |T| 에 비례해 기준점이 밀린다 (HDES01A |T| 9600, 데드존 −0.2 → 위로 약 740) — 실제 Warp 도착에서
		// 레일 t 가 엉뚱한 곳에 잡혀 영웅이 화면 밖이라 데드존 0 일 때의 결과(기준점 = T)를 쓴다 [근사]. 이후 프레임은 해독 그대로.
		float[] dz = s.template ? new float[4] : cp.deadZone();
		float bHi = (float) Math.toDegrees(Math.atan(0.75 * tan * dz[0])), bLo = (float) Math.toDegrees(Math.atan(0.75 * tan * dz[1]));
		float aHi = (float) -Math.toDegrees(Math.atan(tan * dz[2])), aLo = (float) -Math.toDegrees(Math.atan(tan * dz[3]));
		Vector3f eye = s.stEye;
		float ry = s.mYaw, rp = s.mPitch;
		if (b < bLo || b > bHi || a < aLo || a > aHi) {
			b = Math.max(bLo, Math.min(bHi, b));
			a = Math.max(aLo, Math.min(aHi, a));
			ry = s.stYaw;
			rp = s.stPitch;
			eye = rotate(ry, rp, dir(a, b, new Vector3f()), new Vector3f()).mul(-d0).add(t);
		}
		s.hSa = a;
		s.hSb = b;
		s.hDist = d0;
		dir(ry, rp, s.hFwd);
		return new Vector3f(s.hFwd).mul((float) (d0 * Math.cos(Math.toRadians(a)) * Math.cos(Math.toRadians(b)))).add(eye);
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

	/** 레일 비용 함수 종류 (0x1F20F0 이 고름). */
	private enum RailCost {
		/** 0x1F1280: 레일 점-대상 거리가 [min, max] 밖인 정도. */
		DISTANCE,
		/** 0x1F12F0 (플래그 0x200): max(거리 비용, (각도 범위 초과)·1000). */
		ANGLES,
		/** 0x1F1648 (각도 범위 있음, 0x200 없음): 대상 방향을 범위 안으로 자른 방향으로 잰 거리가 [min, max] 밖인 정도. */
		PROJECTED;

		static RailCost of(CameraDef.Params cp) {
			if (cp.yawRange() < 180 || cp.pitchRange() < 90) {
				return (cp.flags() & CameraDef.F_RAIL_ANGLES) != 0 ? ANGLES : PROJECTED;
			}
			return DISTANCE;
		}
	}

	/**
	 * 레일 t (0x1F29A0): 처음(t &lt; 0)엔 매듭 수 × 4 등분 표본 중 비용 최소(동률이면 앞쪽, 0x1F20F0), 이후 이전 t 에서 지역 탐색(0x1F1AA0)한
	 * t′ 쪽으로 감쇠 0xA8 로 따라간다.
	 */
	private float railParam(Shot s, Vector3f t, float dYaw, float dPitch, boolean limited, float dt) {
		NcvCurve rail = s.cam.rail();
		CameraDef.Params cp = s.cam.def().p();
		RailCost kind = RailCost.of(cp);
		if (s.t < 0) {
			Vector3f aim = t; // 기준점 (head)
			float best = 0, bc = Float.MAX_VALUE;
			int n = Math.max(1, rail.knots.length * 4);
			float[] r = new float[3];
			for (int i = 0; i <= n; i++) {
				float u = rail.tMax() * i / n;
				float c = railCost(rail, u, aim, cp, dYaw, dPitch, kind);
				if (System.getProperty("gow.debug.cam") != null) {
					rail.eval(u, r);
					float[] tg = new float[3];
					rail.tangent(u, tg);
					System.out.printf(java.util.Locale.ROOT, "[레일] %s u=%.3f P=(%.1f,%.1f,%.1f) tan=(%.2f,%.2f,%.2f) cost=%.2f%n", s.cam.def().shortName(), u,
							r[0], r[1], r[2], tg[0], tg[1], tg[2], c);
				}
				if (s.fwdBonus) {
					rail.eval(u, r);
					Vector3f nv = new Vector3f(aim.x - r[0], aim.y - r[1], aim.z - r[2]);
					if (nv.lengthSquared() > 1e-6f) {
						c -= 100 * nv.normalize().dot(s.hFwd);
					}
				}
				if (c < bc) {
					bc = c;
					best = u;
				}
			}
			return best;
		}
		float best = descend(rail, s.t, t, cp, dYaw, dPitch, kind);
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

	/**
	 * 지역 탐색 0x1F1AA0: 보폭 0.05(0x29F188, 열린 곡선 끝이면 반대로)로 비용이 줄면 그대로 걸어가고, 늘면 보폭 ×(−½). 비용이 같으면
	 * 이미 출발점에서 보폭 방향으로 와 있으면 ×(−½), 반대쪽이면 받아들인다. 직전 최적점과 후보점 사이가 0.0015(거리²) 이하면 멈춘다.
	 */
	private float descend(NcvCurve rail, float t0, Vector3f t, CameraDef.Params cp, float dYaw, float dPitch, RailCost kind) {
		float m = rail.tMax();
		float step = 0.05f;
		if (m < t0 + step && !rail.closed) {
			step = -step;
		}
		if (m < t0 + step && !rail.closed) {
			step = -step;
		}
		float u = t0, c = railCost(rail, u, t, cp, dYaw, dPitch, kind);
		float[] pb = new float[3], pc = new float[3];
		rail.eval(u, pb);
		for (int guard = 0; guard < 20000; guard++) {
			float n = rail.wrap(u + step);
			rail.eval(n, pc);
			float nc = railCost(rail, n, t, cp, dYaw, dPitch, kind);
			float dx = pb[0] - pc[0], dy = pb[1] - pc[1], dz = pb[2] - pc[2];
			int moved = (int) Math.signum(u - t0), dir = (int) Math.signum(step);
			boolean accept = nc < c || nc == c && moved != dir && moved != 0;
			if (accept) {
				u = n;
				c = nc;
				System.arraycopy(pc, 0, pb, 0, 3);
			} else {
				step *= -0.5f;
			}
			if (dx * dx + dy * dy + dz * dz <= 0.0015f || step == 0) {
				break;
			}
		}
		return u;
	}

	/** 거리 d 가 [railMin, railMax] 밖인 정도 (0x1F1280). */
	private static float rangeCost(float d, CameraDef.Params cp) {
		return d < cp.railMin() ? cp.railMin() - d : Math.max(0, d - cp.railMax());
	}

	/** 레일 비용 (0x1F1280 / 0x1F12F0 / 0x1F1648). 각도는 플래그 0x400 이면 레일 접선 좌표계에서 잰다. */
	private static float railCost(NcvCurve rail, float u, Vector3f t, CameraDef.Params cp, float dYaw, float dPitch, RailCost kind) {
		float[] r = new float[3];
		rail.eval(u, r);
		Vector3f v = new Vector3f(t.x - r[0], t.y - r[1], t.z - r[2]);
		if (kind == RailCost.DISTANCE) {
			return rangeCost(v.length(), cp);
		}
		Vector3f[] frame = railFrame(rail, u, cp);
		Vector3f l = frame != null ? toLocal(frame, v) : new Vector3f(v);
		float py = pitchOf(l), yy = yawOf(l);
		float pMin = dPitch - cp.pitchRange(), pMax = dPitch + cp.pitchRange();
		if (kind == RailCost.ANGLES) {
			float pe = py < pMin ? pMin - py : Math.max(0, py - pMax);
			float ye = Math.max(0, Math.abs(wrap180(yy - dYaw)) - cp.yawRange());
			return Math.max(rangeCost(v.length(), cp), (pe + ye) * 1000);
		}
		// 0x1F1648: 범위 안으로 자른 방향 → 월드, 그 방향 성분이 거리
		Vector3f dl = dir(clampAngle(yy, dYaw, cp.yawRange()), Math.max(pMin, Math.min(pMax, py)), new Vector3f());
		Vector3f dw = frame != null ? toWorld(frame, dl) : dl;
		return rangeCost(v.dot(dw), cp);
	}

	/**
	 * 플래그 0x400 레일의 접선 좌표계 (행 X = 위 × 접선, Y = 접선 × X, Z = 접선; 0x1F12F0/0x1F1648/0x1F3354). 0x400 이 없거나
	 * 접선이 수직이면 null (= 월드 축).
	 */
	private static Vector3f[] railFrame(NcvCurve rail, float u, CameraDef.Params cp) {
		if (rail == null || (cp.flags() & CameraDef.F_RAIL_TANGENT) == 0) {
			return null;
		}
		float[] tg = new float[3];
		rail.tangent(u, tg);
		Vector3f z = new Vector3f(tg[0], tg[1], tg[2]);
		if (z.lengthSquared() < 1e-12f) {
			return null;
		}
		z.normalize();
		Vector3f x = new Vector3f(0, 1, 0).cross(z);
		if (x.lengthSquared() < 1e-12f) {
			return null;
		}
		x.normalize();
		Vector3f y = new Vector3f(z).cross(x).normalize();
		return new Vector3f[]{x, y, z};
	}

	private static Vector3f toLocal(Vector3f[] f, Vector3f v) {
		return new Vector3f(v.dot(f[0]), v.dot(f[1]), v.dot(f[2]));
	}

	private static Vector3f toWorld(Vector3f[] f, Vector3f l) {
		return new Vector3f(f[0]).mul(l.x).add(new Vector3f(f[1]).mul(l.y)).add(new Vector3f(f[2]).mul(l.z));
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

	/**
	 * 상태 보간 0x1F3C90 (상태 +0x70..+0xEC 32 개 값마다 0x1F3BC0 3차 Hermite(p0, v0, p1, v1, s), s = 경과/전환 시간 그대로).
	 * 속도 = (값 − 직전 프레임 값 +0xF0..)·60. 상태 갱신 0x1F3768 끝이 직전 값 = 지금 값으로 복사하므로 카메라 상태의 속도는 0 —
	 * 0 이 아닌 것은 앞 보간 결과 (관리자 +0x10: 직전 값 = 결과 − 도함수/60) 뿐이다. 즉 상태 둘이면 smoothstep, 전환 중에 또 전환이 오면
	 * 앞 보간의 도함수 dp/ds 가 v0 로 들어간다. 요 (+0x80) 만 ±180 감김 (도함수도). va / vout = 도함수 (null = 0).
	 */
	static void blend(Pose a, Pose va, Pose b, float s, Pose out, Pose vout) {
		float s2 = s * s, s3 = s2 * s;
		float h00 = 2 * s3 - 3 * s2 + 1, h10 = s3 - 2 * s2 + s, h01 = -2 * s3 + 3 * s2;
		float d00 = 6 * s2 - 6 * s, d10 = 3 * s2 - 4 * s + 1, d01 = -6 * s2 + 6 * s;
		float by = a.yaw + wrap180(b.yaw - a.yaw);
		Pose v = va != null ? va : ZERO;
		float[] p0 = {a.target.x, a.target.y, a.target.z, a.yaw, a.pitch, a.dist, a.fovDeg, a.sa, a.sb};
		float[] v0 = {v.target.x, v.target.y, v.target.z, v.yaw, v.pitch, v.dist, v.fovDeg, v.sa, v.sb};
		float[] p1 = {b.target.x, b.target.y, b.target.z, by, b.pitch, b.dist, b.fovDeg, b.sa, b.sb};
		float[] r = new float[9], dr = new float[9];
		for (int i = 0; i < 9; i++) {
			r[i] = h00 * p0[i] + h10 * v0[i] + h01 * p1[i];
			dr[i] = d00 * p0[i] + d10 * v0[i] + d01 * p1[i];
		}
		out.target.set(r[0], r[1], r[2]);
		out.yaw = wrap180(r[3]);
		out.pitch = r[4];
		out.dist = r[5];
		out.fovDeg = r[6];
		out.sa = r[7];
		out.sb = r[8];
		if (vout != null) {
			vout.target.set(dr[0], dr[1], dr[2]);
			vout.yaw = dr[3];
			vout.pitch = dr[4];
			vout.dist = dr[5];
			vout.fovDeg = dr[6];
			vout.sa = dr[7];
			vout.sb = dr[8];
		}
	}

	/** 두 상태 smoothstep (속도 0 Hermite). */
	static void blend(Pose a, Pose b, float s, Pose out) {
		blend(a, null, b, s, out, null);
	}

	private static final Pose ZERO = zeroPose();

	private static Pose zeroPose() {
		Pose z = new Pose();
		z.fovDeg = 0;
		return z;
	}

	/** 렌더 보간 (틱 사이, 원작에 없음): 선형 — smoothstep 이면 틱마다 가감속이 생겨 렌더 주기 &gt; 60Hz 에서 움직임이 떨린다. */
	static void lerp(Pose a, Pose b, float s, Pose out) {
		out.target.set(a.target).lerp(b.target, s);
		out.yaw = a.yaw + wrap180(b.yaw - a.yaw) * s;
		out.pitch = a.pitch + (b.pitch - a.pitch) * s;
		out.dist = a.dist + (b.dist - a.dist) * s;
		out.fovDeg = a.fovDeg + (b.fovDeg - a.fovDeg) * s;
		out.sa = a.sa + (b.sa - a.sa) * s;
		out.sb = a.sb + (b.sb - a.sb) * s;
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

	/**
	 * 카메라 좌표 벡터 → 월드: R(요, 피치) = X 축 피치 회전 뒤 Y 축 요 회전 (0x1F1000: 단위 행렬 → 0x1847E0 롤 → 0x184600 피치 → 0x1846F0 요).
	 * rotate(요, 피치, (0, 0, −1)) = dir(요, 피치).
	 */
	static Vector3f rotate(float yaw, float pitch, Vector3f l, Vector3f out) {
		double p = Math.toRadians(pitch), y = Math.toRadians(yaw);
		double cp = Math.cos(p), sp = Math.sin(p), cy = Math.cos(y), sy = Math.sin(y);
		double x1 = l.x, y1 = l.y * cp - l.z * sp, z1 = l.y * sp + l.z * cp;
		return out.set((float) (x1 * cy + z1 * sy), (float) y1, (float) (-x1 * sy + z1 * cy));
	}

	/** 월드 벡터 → 카메라 좌표 (rotate 의 역, 0x185048 역행렬). */
	static Vector3f unrotate(float yaw, float pitch, Vector3f v) {
		double p = Math.toRadians(pitch), y = Math.toRadians(yaw);
		double cp = Math.cos(p), sp = Math.sin(p), cy = Math.cos(y), sy = Math.sin(y);
		double x1 = v.x * cy - v.z * sy, z1 = v.x * sy + v.z * cy, y1 = v.y;
		return new Vector3f((float) x1, (float) (y1 * cp + z1 * sp), (float) (-y1 * sp + z1 * cp));
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
