package devple.gow.engine.combat;

import devple.gow.engine.anim.Animator;
import devple.gow.engine.game.Tweaks;
import devple.gow.engine.input.Input;
import devple.gow.engine.input.PadButton;

/**
 * 연타 미니게임 (원작 SCR_RepeatPress 0x1924F0, 갱신 0x192160, 10 문서 §12 / 35 문서 §5).
 * 적의 처형 기술에 SCRIPT 이벤트 SCR_RepeatPress(해시 0xE513E9CB, idx = 템플릿 번호)가 있고 재생 속도가 0 이면
 * (예 미노타우로스 MAIMIN02) 게이지가 두 캐릭터의 애니 진행률이 된다.
 * 템플릿 {@code /TweakTemplates/RepeatPress/NNN/} (적 WAD, 번호가 없으면 첫 템플릿):
 * <pre>
 *   +0x18 Forward Amount  누를 때 +F (원작 F × 60 × dt, 60fps 에서 F)       +0x1C #b4af76ab 프레임당 감소 (× 60 × dt)
 *   +0x20 #1fc6d36d 누름 사이 최대 간격(초) — 넘으면 실패              +0x24 #819e4a49 전체 제한 시간
 *   +0x28 #4ba7dfc2 표시 평활 (disp += (meter − disp) × k)            +0x2C #1d8c1daa 압력 가중치 (디지털 = 압력 255 → 영향 없음)
 *   +0x30 #870e6017 최소 간격 — 더 빨리 누르면 F × (1 − since/min) 감점  +0x34 Fail Move, +0x36 #931459a0 Pass Move, +0x38 아이콘
 * </pre>
 * 게이지는 0 에서 시작. ≥ 1 → Pass Move, (간격 초과 또는 시간 초과) → Fail Move (같으면 생략). 양쪽 combatant 모두 전환.
 */
public final class RepeatPressQte {

	public enum State { RUNNING, PASSED, FAILED }

	public static final int SCR_REPEAT_PRESS = 0xe513e9cb;
	private static final int TOTAL = 0x819e4a49, DECAY = 0xb4af76ab, GAP = 0x1fc6d36d, PASS = 0x931459a0, ICON = 0xd650acb2,
			SMOOTH = 0x4ba7dfc2, MIN_GAP = 0x870e6017;

	private final EnemyFighter enemy;
	private final Combatant hero;
	private final Animator heroAnim;
	private final float total, forward, decay, maxGap, smooth, minGap;
	private final int passMove, failMove, icon;
	private float meter, disp, time, since;
	private State state = State.RUNNING;

	public RepeatPressQte(EnemyFighter enemy, Combatant hero, Animator heroAnim, Tweaks enemyTweaks) {
		this(enemy, hero, heroAnim, enemyTweaks, -1);
	}

	/** @param template 이벤트 idx (−1 = 첫 템플릿) */
	public RepeatPressQte(EnemyFighter enemy, Combatant hero, Animator heroAnim, Tweaks enemyTweaks, int template) {
		this.enemy = enemy;
		this.hero = hero;
		this.heroAnim = heroAnim;
		String n = template >= 0 ? String.format("/TweakTemplates/RepeatPress/%03d/", template) : null;
		if (n == null || enemyTweaks.findNode(n) == null) {
			n = enemyTweaks.firstNode("/TweakTemplates/RepeatPress/");
		}
		total = f(enemyTweaks, n, TOTAL, 5f);
		forward = enemyTweaks.f(n, "Forward Amount", 0.2f);
		decay = f(enemyTweaks, n, DECAY, 0.02f);
		maxGap = f(enemyTweaks, n, GAP, 0.5f);
		smooth = f(enemyTweaks, n, SMOOTH, 0.2f);
		minGap = f(enemyTweaks, n, MIN_GAP, 0.1f);
		passMove = (short) enemyTweaks.rawHash(n, PASS).orElse(-1);
		failMove = enemyTweaks.i16(n, "Fail Move", -1);
		icon = (short) enemyTweaks.rawHash(n, ICON).orElse(5);
	}

	private static float f(Tweaks tw, String node, int hash, float def) {
		var r = tw.rawHash(node, hash);
		return r.isPresent() ? Float.intBitsToFloat(r.getAsInt()) : def;
	}

	/** 아이콘 종류 (값 표 0x29D740: 4 △, 5 ○, 6 ×, 7 □). */
	public PadButton button() {
		return switch (icon & 0xFF) {
			case 4 -> PadButton.TRIANGLE;
			case 6 -> PadButton.CROSS;
			case 7 -> PadButton.SQUARE;
			default -> PadButton.CIRCLE;
		};
	}

	public State update(Input in, float dt) {
		if (state != State.RUNNING) {
			return state;
		}
		time += dt;
		float step = forward * 60f * dt;
		if (in.pressed(button())) {
			meter += step; // 디지털 버튼: 압력 255 → 가중치 무관
			if (since < minGap) {
				meter -= step * (1 - since / minGap);
			}
			since = 0;
		} else {
			since += dt;
			meter -= decay * 60f * dt;
		}
		meter = Math.max(0, meter);
		if (meter >= 1f) {
			finish(true);
		} else if (since > maxGap || time > total) {
			finish(false);
		} else {
			disp += (meter - disp) * smooth;
			enemy.combat().setProgress(disp);
		}
		return state;
	}

	private void finish(boolean pass) {
		state = pass ? State.PASSED : State.FAILED;
		if (!pass && failMove == passMove) {
			return;
		}
		int move = pass ? passMove : failMove;
		if (!enemy.combat().request(move)) {
			enemy.combat().forceMove(move);
		}
		String clip = enemy.combat().clipName();
		if (clip != null) {
			var a = heroAnim.find(clip);
			if (a != null) {
				heroAnim.playClip(a, 0, 1f); // 영웅도 같은 이름의 외부 애니로 동기 (maiMin03/04)
			}
		}
	}

	/** 표시 게이지 (애니 진행률). */
	public float meter() {
		return disp;
	}

	/** 원 게이지. */
	public float rawMeter() {
		return meter;
	}

	public State state() {
		return state;
	}

	public int passMove() {
		return passMove;
	}

	public int failMove() {
		return failMove;
	}
}
