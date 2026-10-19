package devple.gow.engine.combat;

import devple.gow.engine.game.Tweaks;
import devple.gow.engine.input.Input;
import devple.gow.engine.input.PadButton;

import java.util.Random;

/**
 * 타이밍 미니게임 (원작 SCR_TimedPress 0x192ED0, 틱 0x192C90, 실패 0x192A80 — 35 문서 §5).
 * 적 처형 기술의 SCRIPT 이벤트 하나 = 프롬프트 하나. 입력 창 = 그 이벤트의 [start, end] (시간 상수 없음).
 * 템플릿 {@code /TweakTemplates/TimedPress/NNN/} (NNN = 이벤트 idx):
 * <pre>
 *   #d650acb2 u16 종류: 4 △ 5 ○ 6 × 7 □, F0–F5 스틱 회전(Up180CW, Up180CCW, Down180CW, Down180CCW, 360CW, 360CCW),
 *             FB 10종 무작위, FC 회전 무작위, FD 180 무작위, FE 360 무작위, FF 버튼 무작위
 *   #776f2158 s8  회전: 첫 단계 전 허용되는 틀린 사분면 변화 수
 *   #815ff05d     표시 소리 (SND_MINIGAME_BUTTON)      #f48a3e39 성공 소리 (SND_MINIGAME_HIT)
 * </pre>
 * 버튼: 맞는 버튼 → 성공(기술은 그대로 진행), 다른 얼굴 버튼 → 즉시 실패. 회전: 왼쪽 스틱 사분면(데드존 0.6) 변화가
 * 순서대로 일치하면 성공, 단계 진행 후 틀리면 실패 표시(창 끝에서 실패 분기). 창이 끝날 때까지 성공 못 하면 실패.
 */
public final class TimedPressQte {

	public enum State { RUNNING, PASSED, FAILED }

	public static final int SCR_TIMED_PRESS = 0xf980ef69;
	static final int ICON = 0xd650acb2, TOLERANCE = 0x776f2158, SHOW_SOUND = 0x815ff05d, HIT_SOUND = 0xf48a3e39;
	/** 원작 HUD 아이콘 클립 이름 표 0x29DA40 (인덱스 0..9 사용). */
	public static final String[] ICON_CLIPS = {"XButtonGreek", "TriangleButtonGreek", "CircleButtonGreek", "SquareButtonGreek",
			"Up180CW", "Up180CCW", "Down180CW", "Down180CCW", "360CW", "360CCW"};
	/** 종류 값 표 0x29D740 의 앞 10개 (HUD 인덱스 순). */
	static final int[] KINDS = {6, 4, 5, 7, 0xF0, 0xF1, 0xF2, 0xF3, 0xF4, 0xF5};
	/** 회전 단계 (0x235C88): 사분면 1 위, 2 오른쪽, 3 아래, 4 왼쪽. */
	static final int[][] SEQ = {{4, 1}, {2, 1}, {2, 3}, {4, 3}, {4, 1, 2}, {2, 1, 4}};
	static final float DEADZONE = 0.6f;

	private final int kind;
	private final int tolerance;
	public final String showSound, hitSound;
	private int idx, lastQuad, wrong;
	private boolean failedFlag;
	private State state = State.RUNNING;

	public TimedPressQte(Tweaks tw, int template, Random rnd) {
		String n = String.format("/TweakTemplates/TimedPress/%03d/", template);
		if (tw.findNode(n) == null) {
			String first = tw.firstNode("/TweakTemplates/TimedPress/");
			n = first != null ? first : n;
		}
		int raw = tw.rawHash(n, ICON).orElse(0xFF) & 0xFF;
		this.kind = resolve(raw, rnd);
		this.tolerance = (byte) tw.rawHash(n, TOLERANCE).orElse(0);
		this.showSound = tw.sHash(n, SHOW_SOUND, "SND_MINIGAME_BUTTON");
		this.hitSound = tw.sHash(n, HIT_SOUND, "SND_MINIGAME_HIT");
	}

	/** 무작위 종류 해석 (점프표 0x2B4A30). */
	static int resolve(int raw, Random rnd) {
		return switch (raw) {
			case 0xFB -> KINDS[rnd.nextInt(10)];
			case 0xFC -> KINDS[4 + rnd.nextInt(6)];
			case 0xFD -> KINDS[4 + rnd.nextInt(4)];
			case 0xFE -> KINDS[8 + rnd.nextInt(2)];
			case 0xFF -> KINDS[rnd.nextInt(4)];
			default -> raw;
		};
	}

	public int kind() {
		return kind;
	}

	public boolean rotation() {
		return kind >= 0xF0 && kind <= 0xF5;
	}

	/** HUD 아이콘 인덱스 (0x29DA40), 클립 이름 = {@link #ICON_CLIPS}. */
	public int iconIndex() {
		for (int i = 0; i < KINDS.length; i++) {
			if (KINDS[i] == kind) {
				return i;
			}
		}
		return -1;
	}

	/** 버튼 종류의 버튼 (회전이면 null). */
	public PadButton button() {
		return switch (kind) {
			case 4 -> PadButton.TRIANGLE;
			case 5 -> PadButton.CIRCLE;
			case 6 -> PadButton.CROSS;
			case 7 -> PadButton.SQUARE;
			default -> null;
		};
	}

	/** 회전 종류의 사분면 순서 (버튼이면 null). */
	public int[] sequence() {
		return rotation() ? SEQ[kind - 0xF0] : null;
	}

	public State state() {
		return state;
	}

	/** 실패 표시만 된 상태 (창 끝에서 실패 분기). */
	public boolean failedFlag() {
		return failedFlag;
	}

	/** 왼쪽 스틱 사분면 (0x192A00): 0 중앙, 1 위, 2 오른쪽, 3 아래, 4 왼쪽. */
	static int quadrant(float x, float y) {
		if (Math.abs(x) < DEADZONE && Math.abs(y) < DEADZONE) {
			return 0;
		}
		if (Math.abs(x) < Math.abs(y)) {
			return y > 0 ? 1 : 3;
		}
		return x > 0 ? 2 : 4;
	}

	/** @return 상태. FAILED 는 즉시 실패 분기를 실행해야 함 (틀린 버튼). */
	public State update(Input in) {
		if (state != State.RUNNING || failedFlag) {
			return state;
		}
		PadButton b = button();
		if (b != null) {
			if (in.pressed(b)) {
				state = State.PASSED;
			} else {
				for (PadButton o : new PadButton[]{PadButton.CROSS, PadButton.CIRCLE, PadButton.SQUARE, PadButton.TRIANGLE}) {
					if (o != b && in.pressed(o)) {
						state = State.FAILED;
					}
				}
			}
			return state;
		}
		int q = quadrant(in.leftX(), in.leftY());
		if (q != lastQuad) {
			lastQuad = q;
			int[] seq = sequence();
			if (q == seq[idx]) {
				idx++;
				if (idx >= seq.length) {
					state = State.PASSED;
				}
			} else {
				wrong++;
				if (idx > 0 || wrong > tolerance) {
					failedFlag = true;
				}
			}
		}
		return state;
	}

	/** 창이 끝남: 성공하지 못했으면 실패. */
	public State expire() {
		if (state == State.RUNNING) {
			state = State.FAILED;
		}
		return state;
	}
}
