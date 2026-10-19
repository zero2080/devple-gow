package devple.gow.engine.combat;

import devple.gow.engine.anim.Animator;
import devple.gow.engine.game.Tweaks;
import devple.gow.engine.input.Input;
import devple.gow.engine.input.PadButton;

/**
 * 연타 미니게임 (원작 SCR_RepeatPress 0x1924F0, 10 문서 §12).
 * 적의 처형 기술에 SCRIPT(8) 이벤트가 있고 재생 속도가 0 이면(예 미노타우로스 MAIMIN02) 게이지가 애니 진행률이 된다.
 * 템플릿 {@code /TweakTemplates/RepeatPress/NNN/} (적 WAD):
 * <pre>
 *   #819e4a49 Total Time (5초)         "Forward Amount" 누를 때 +0.2      #b4af76ab 감소/초 (0.02)
 *   #1fc6d36d 시작 게이지 (0.5) [추정]  #931459a0 Pass Move (402) [추정] "Fail Move" (403)   #d650acb2 버튼 아이콘 (5)
 * </pre>
 * 성공 → 적 Pass Move + 영웅은 같은 이름의 외부 애니(maiMin03), 실패 → Fail Move / maiMin04.
 */
public final class RepeatPressQte {

	public enum State { RUNNING, PASSED, FAILED }

	private static final int TOTAL = 0x819e4a49, DECAY = 0xb4af76ab, START = 0x1fc6d36d, PASS = 0x931459a0, ICON = 0xd650acb2;

	private final EnemyFighter enemy;
	private final Combatant hero;
	private final Animator heroAnim;
	private final float total, forward, decay;
	private final int passMove, failMove, icon;
	private float meter, time;
	private State state = State.RUNNING;

	public RepeatPressQte(EnemyFighter enemy, Combatant hero, Animator heroAnim, Tweaks enemyTweaks) {
		this.enemy = enemy;
		this.hero = hero;
		this.heroAnim = heroAnim;
		String n = enemyTweaks.firstNode("/TweakTemplates/RepeatPress/");
		total = f(enemyTweaks, n, TOTAL, 5f);
		forward = enemyTweaks.f(n, "Forward Amount", 0.2f);
		decay = f(enemyTweaks, n, DECAY, 0.02f);
		meter = f(enemyTweaks, n, START, 0.5f);
		passMove = (short) enemyTweaks.rawHash(n, PASS).orElse(-1);
		failMove = enemyTweaks.i16(n, "Fail Move", -1);
		icon = (short) enemyTweaks.rawHash(n, ICON).orElse(0);
	}

	private static float f(Tweaks tw, String node, int hash, float def) {
		var r = tw.rawHash(node, hash);
		return r.isPresent() ? Float.intBitsToFloat(r.getAsInt()) : def;
	}

	/** 원작 버튼 아이콘 5 = ○ 로 추정 (아이콘 표 0x29D740 미해석). */
	public PadButton button() {
		return icon == 5 ? PadButton.CIRCLE : PadButton.CIRCLE;
	}

	public State update(Input in, float dt) {
		if (state != State.RUNNING) {
			return state;
		}
		time += dt;
		if (in.pressed(button())) {
			meter += forward;
		}
		meter = Math.max(0, meter - decay * dt);
		enemy.combat().setProgress(meter);
		if (meter >= 1f) {
			finish(true);
		} else if (time >= total) {
			finish(false);
		}
		return state;
	}

	private void finish(boolean pass) {
		state = pass ? State.PASSED : State.FAILED;
		int move = pass ? passMove : failMove;
		enemy.combat().request(move);
		String clip = enemy.combat().clipName();
		if (clip != null) {
			var a = heroAnim.find(clip);
			if (a != null) {
				heroAnim.playClip(a, 0, 1f); // 영웅도 같은 이름의 외부 애니로 동기 (maiMin03/04)
			}
		}
	}

	public float meter() {
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
