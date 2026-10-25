package devple.gow.engine.input;

import org.joml.Vector3f;

import java.util.EnumSet;
import java.util.Set;

/**
 * -Dgow.bot: 시연 녹화용 자동 플레이 (원작에 없음). 가장 가까운 적에게 걸어가 □□□△ 연타, 네 번째 묶음마다 ○ 잡기 뒤 □.
 * 적이 없으면 천천히 원을 그리며 걷는다 (해치 위 등 볼륨 안에 서 있으면 진행이 보류되는 스크립트가 있다 — ATHN01A HatchZone). 틱마다 {@link #steer} 후 영웅 입력으로 넘긴다.
 */
public final class BotInput extends Input {

	private static final float NEAR = 110f;
	/** 한 묶음 (초, 버튼): 0.3 초 간격 누름. */
	private static final PadButton[] COMBO = {PadButton.SQUARE, PadButton.SQUARE, PadButton.SQUARE, PadButton.TRIANGLE};
	private static final PadButton[] GRAB = {PadButton.CIRCLE, PadButton.SQUARE, PadButton.SQUARE, PadButton.SQUARE};
	/**
	 * 석상 깨기: 석상 Breakable 517 Hit Points 7 은 공격 피해 바이트만큼 준다 (0x1DE4D8) — 서서 △ (combo6A, 6) + □ (2) = 두 타. □□□△ 묶음은
	 * 2 + 2 + 2 + 0 (combo7A) 이라 네 타 (≈ 2 초) 가 걸려 셋을 얼려도 둘째·셋째가 돌 지속 5 초 안에 못 깨졌다.
	 */
	private static final PadButton[] STONE = {PadButton.TRIANGLE, PadButton.SQUARE, PadButton.SQUARE, PadButton.SQUARE};
	private static final float GAP = 0.3f, PAUSE = 0.5f;

	private final Set<PadButton> down = EnumSet.noneOf(PadButton.class);
	private final Set<PadButton> prev = EnumSet.noneOf(PadButton.class);
	private float lx, ly;
	private float clock, wander;
	private int combos;
	/** 대상이 잡기를 기다림 (자세 태그 설정 중 — 선창 히드라 기절 107 SET_1B0 830): 묶음마다 ○ 부터 (GameScene 이 매 틱 넣음). */
	public boolean grabTarget;
	/** 대상이 석상: ○ 잡기 묶음을 쓰지 않는다 (석상 잡기 = 200 grab → 204 grabReject 로 1 초 낭비, ATHN02D). */
	public boolean stoneTarget;
	/**
	 * 메두사의 시선으로 대상을 석화 (GameScene 이 매 틱: 시선 보유·MP 충분·대상이 석화 가능하고 아직 돌이 아님). 원작 조작 = 메시지 595
	 * "L2 를 누르고, □ 를 눌러서 발사": 다가가서 몸을 대상 쪽으로 돌린 뒤 L2 홀드 (81 PSENTHEAD → 82 USEHEAD) + □ 홀드 (광선, 0x1972E8).
	 */
	public boolean gaze;
	/** 시선이 선택 마법이 아님 → ← (방향키 왼쪽, 0x1D7E10) 를 누른다. */
	public boolean selectGaze;
	private float gazeClock, selectClock;
	/**
	 * 아르테미스의 검을 든다 (GameScene: -Dgow.auto.artemis + 보유 + 아직 들지 않음) — 묶음 사이에 L1 홀드 0.45 초, 0.2 초에 R1
	 * (L1+R1 → BERSERKENTER 555, 10 문서 §4). 영상 4062–5060 처럼 검으로 싸우는 구간의 대조용 [엔진 전용 봇].
	 */
	public boolean equipArtemis;
	private float equipClock;
	/** 시선을 쏠 거리 (유닛): 원뿔 Distance 15 m × 16 = 240 안쪽. 광선을 켜 두는 최대 초. */
	private static final float GAZE_NEAR = 215f;
	/**
	 * 광선을 켜 두는 최대 초: 미노타우로스 (게이지 6000, 단계 0 6000/초) 하나 ≈ 1 초, 광선이 켜진 동안 {@link #GAZE_SWEEP} 안의 다음 대상으로 넘겨
	 * 셋까지 (GameScene) — 석화되면 곧바로 뗀다 (gaze 거짓).
	 */
	public static final float GAZE_MAX = 4.5f;
	/** 광선을 끄지 않고 넘길 다음 대상 거리 (유닛, 원뿔 Distance 15 m × 16 = 240 안쪽). */
	public static final float GAZE_SWEEP = 220f;

	public BotInput(long window) {
		super(window);
	}

	/** target = 가장 가까운 살아 있는 적 (없으면 null). */
	public void steer(Vector3f hero, Vector3f target, float camYaw, float dt) {
		prev.clear();
		prev.addAll(down);
		down.clear();
		lx = ly = 0;
		wander += dt;
		if (target == null) {
			clock = 0;
			lx = (float) Math.sin(wander * 0.4f);
			ly = (float) Math.cos(wander * 0.4f);
			return;
		}
		float dx = target.x - hero.x, dz = target.z - hero.z;
		float d = (float) Math.hypot(dx, dz);
		float a = (float) Math.atan2(-dx, -dz) - camYaw; // HeroController: 정면 = (-sin yaw, -cos yaw), 스틱 각 = atan2(-sx, sy)
		if (gaze || gazeClock > 0) {
			if (gazeStep(d, a, dt)) {
				return;
			}
		}
		if ((equipArtemis || equipClock > 0) && clock == 0) {
			equipClock += dt;
			if (equipClock < 0.45f) {
				down.add(PadButton.L1);
				if (equipClock >= 0.2f && equipClock < 0.28f) {
					down.add(PadButton.R1);
				}
				return;
			}
			if (equipClock < 0.9f) {
				return; // BERSERKENTER 동안 손대지 않음 (아직 안 들었으면 다음에 다시)
			}
			equipClock = 0;
		}
		if (d > NEAR && clock == 0) {
			lx = (float) -Math.sin(a);
			ly = (float) Math.cos(a);
			return;
		}
		PadButton[] seq = stoneTarget ? STONE : grabTarget || combos % 4 == 3 ? GRAB : COMBO;
		int i = (int) (clock / GAP);
		if (i < seq.length) {
			if (clock - i * GAP < 0.08f) {
				down.add(seq[i]);
			}
			if (i == 0) { // 첫 누름 동안 적 쪽으로 기울여 방향을 맞춘다
				lx = (float) -Math.sin(a) * 0.5f;
				ly = (float) Math.cos(a) * 0.5f;
			}
		}
		clock += dt;
		if (clock >= seq.length * GAP + PAUSE) {
			clock = 0;
			combos++;
		}
	}

	/**
	 * 시선 한 번: 선택 (←) → 대상 쪽으로 걸어가 몸을 돌림 (0.2 초) → L2 홀드, 0.35 초부터 □ 홀드 (광선) → 석화되거나 {@link #GAZE_MAX} 초면
	 * 버튼을 떼고 0.4 초 쉰다 (PUTAWAYHEAD). 그 뒤 석화된 대상은 보통 묶음 (□ 첫 타 = 석상 파괴).
	 *
	 * @return 이번 틱 입력을 정했으면 true
	 */
	private boolean gazeStep(float d, float a, float dt) {
		if (gaze && selectGaze) {
			selectClock += dt;
			if (selectClock > 0.4f) {
				selectClock = 0;
				down.add(PadButton.LEFT);
			}
			return true;
		}
		if (gazeClock == 0 && d > GAZE_NEAR) {
			if (!gaze) {
				return false;
			}
			lx = (float) -Math.sin(a);
			ly = (float) Math.cos(a);
			clock = 0;
			return true;
		}
		gazeClock += dt;
		clock = 0;
		if (gazeClock < 0.2f) { // 몸을 대상 쪽으로 (USEHEAD 조준은 몸 정면 ±80° 안)
			lx = (float) -Math.sin(a) * 0.3f;
			ly = (float) Math.cos(a) * 0.3f;
			return true;
		}
		if (gaze && gazeClock < GAZE_MAX) {
			down.add(PadButton.L2);
			if (gazeClock > 0.35f) {
				down.add(PadButton.SQUARE);
			}
			return true;
		}
		// 놓은 뒤 0.4 초 쉼 (PUTAWAYHEAD) — 석화가 끝나 gaze 가 꺼진 경우도 여기로
		gazeClock = Math.max(gazeClock, GAZE_MAX);
		if (gazeClock < GAZE_MAX + 0.4f) {
			return true;
		}
		gazeClock = 0;
		return false;
	}

	/** 걷기만 (자동 플레이 이동): 목표 쪽 스틱, 버튼 없음. */
	public void walkTo(Vector3f hero, float[] p, float camYaw) {
		prev.clear();
		prev.addAll(down);
		down.clear();
		float a = (float) Math.atan2(-(p[0] - hero.x), -(p[2] - hero.z)) - camYaw;
		lx = (float) -Math.sin(a);
		ly = (float) Math.cos(a);
	}

	/**
	 * 버튼을 누르고 있으면서 스틱 (자동 플레이 경로 단계: 크랭크 돌리기 = R2 + 손잡이 쪽 스틱). p = 스틱을 밀 월드 점 (null = 스틱 없음),
	 * raw = 카메라와 무관한 스틱 값 {x, y} (p 가 null 일 때, 사다리 "위" 등).
	 */
	public BotInput hold(PadButton b, Vector3f hero, float[] p, float[] raw, float camYaw) {
		return hold(b, hero, p, raw, camYaw, 1f);
	}

	/** {@link #hold(PadButton, Vector3f, float[], float[], float)} 의 스틱 크기 mag (몸만 돌리기 = 작게). */
	public BotInput hold(PadButton b, Vector3f hero, float[] p, float[] raw, float camYaw, float mag) {
		prev.clear();
		prev.addAll(down);
		down.clear();
		lx = ly = 0;
		if (b != null) {
			down.add(b);
		}
		if (p != null) {
			float a = (float) Math.atan2(-(p[0] - hero.x), -(p[2] - hero.z)) - camYaw;
			lx = (float) -Math.sin(a) * mag;
			ly = (float) Math.cos(a) * mag;
		} else if (raw != null) {
			lx = raw[0];
			ly = raw[1];
		}
		return this;
	}

	/** 멈춰서 버튼 하나만 (null = 아무것도). */
	public BotInput press(PadButton b) {
		prev.clear();
		prev.addAll(down);
		down.clear();
		lx = ly = 0;
		if (b != null) {
			down.add(b);
		}
		return this;
	}

	@Override
	public void poll() {
	}

	@Override
	public boolean held(PadButton b) {
		return down.contains(b);
	}

	@Override
	public boolean pressed(PadButton b) {
		return down.contains(b) && !prev.contains(b);
	}

	@Override
	public boolean released(PadButton b) {
		return !down.contains(b) && prev.contains(b);
	}

	@Override
	public float leftX() {
		return lx;
	}

	@Override
	public float leftY() {
		return ly;
	}

	@Override
	public float rightX() {
		return 0;
	}

	@Override
	public float rightY() {
		return 0;
	}
}
