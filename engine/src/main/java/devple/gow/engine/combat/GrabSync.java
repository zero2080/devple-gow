package devple.gow.engine.combat;

import devple.gow.engine.anim.Animator;
import devple.gow.engine.anim.HeroController;
import devple.gow.engine.game.Tweaks;
import devple.gow.engine.input.Input;
import devple.gow.tools.anim.Animation;

/**
 * 잡기/처형 동기화. 원작은 SYNC(18) 분기와 synchJoint 로 두 캐릭터의 애니를 맞춘다 (10 문서 §12, 05 문서 synchJoint).
 * 구현(근사):
 * <ul>
 *   <li>영웅이 MAI* 기술에 들어가면 대상 적과 "결합" — 적의 MAI* 기술이 끝날 때까지 영웅은 같은 이름의 외부 애니(영웅용)를 같은 시간으로 재생</li>
 *   <li>두 캐릭터는 같은 기준점(영웅 위치·방향)에 배치 — 처형 애니는 같은 원점 기준으로 제작됨 [추정]</li>
 *   <li>적 기술에 SCRIPT SCR_RepeatPress + 재생 속도 0 → {@link RepeatPressQte} (idx = 템플릿)</li>
 *   <li>적 기술에 SCRIPT SCR_TimedPress → 이벤트 창마다 {@link TimedPressQte} (버튼 / 스틱 회전, 사이클롭스·고르곤 처형).
 *       실패(틀린 버튼, 창 만료) → 적 기술의 같은 창 REQUEST0 분기 (실패 기술) — 영웅은 같은 이름 애니로 따라감</li>
 * </ul>
 */
public final class GrabSync {

	private final HeroController hero;
	private final Combatant heroCombat;
	private final Animator heroAnim;
	private EnemyFighter partner;
	private Tweaks partnerTweaks;
	private RepeatPressQte qte;
	private String lastEnemyClip;
	private RepeatPressQte.State lastResult;
	private TimedPressQte timed;
	private devple.gow.tools.combat.CombatFile.Event timedEvent;
	private int timedSerial = -1;
	private final java.util.Random rnd = new java.util.Random(3);
	/** 타이밍 프롬프트 결과 기록 (테스트·HUD). */
	public int timedPassed, timedFailed;
	private int sessionPassed, sessionFailed;

	/** 마지막 결합(처형)이 성공으로 끝났는지: 연타 성공, 또는 타이밍 프롬프트 전부 성공. */
	public boolean executionSucceeded() {
		return lastResult == RepeatPressQte.State.PASSED || (lastResult == null && sessionPassed > 0 && sessionFailed == 0);
	}
	/** 소리 출력 (SND_MINIGAME_BUTTON/HIT). */
	private java.util.function.Consumer<String> sound = n -> { };

	public void onSound(java.util.function.Consumer<String> s) {
		sound = s;
	}

	/** 진행 중인 타이밍 프롬프트 (없으면 null) — HUD 아이콘 {@link TimedPressQte#ICON_CLIPS}. */
	public TimedPressQte timed() {
		return timed != null && timed.state() == TimedPressQte.State.RUNNING ? timed : null;
	}

	/** 마지막 QTE 결과 (없으면 null). */
	public RepeatPressQte.State lastResult() {
		return lastResult;
	}

	public GrabSync(HeroController hero, Combatant heroCombat) {
		this.hero = hero;
		this.heroCombat = heroCombat;
		this.heroAnim = hero.animator();
	}

	public boolean active() {
		return partner != null;
	}

	public RepeatPressQte qte() {
		return qte;
	}

	/** @return 결합 중이면 true (게임 쪽은 영웅 이동/전투 갱신을 건너뛴다) */
	public boolean update(Input in, EnemyFighter target, Tweaks targetTweaks, float dt) {
		String hc = heroCombat.clipName();
		if (target != null) {
			// 적 세트에 없는 처형 애니(예 사이클롭스 MAICYW*)는 영웅 세트의 같은 애니로 시간·이름을 맞춘다
			target.combat().setClipFallback(heroCombat::animationByHash);
		}
		if (partner == null && hc != null && hc.toLowerCase().startsWith("mai") && target != null) {
			String ec = target.combat().clipName();
			if (ec != null && ec.toLowerCase().startsWith("mai")) {
				partner = target;
				partnerTweaks = targetTweaks;
				lastEnemyClip = null;
				lastResult = null;
				sessionPassed = sessionFailed = 0;
			}
		}
		if (partner == null) {
			return false;
		}
		String ec = partner.combat().clipName();
		if (ec == null || !ec.toLowerCase().startsWith("mai")) {
			partner = null;
			qte = null;
			timed = null;
			timedEvent = null;
			return false;
		}
		// 기준점 공유: 적을 영웅 위치·방향에
		partner.pos.set(hero.position);
		partner.prevPos.set(hero.prevPosition);
		partner.yaw = hero.yaw;
		if (!ec.equals(lastEnemyClip)) {
			lastEnemyClip = ec;
			Animation a = heroAnim.find(ec);
			if (a != null && (hc == null || !hc.equalsIgnoreCase(ec))) {
				heroAnim.playClip(a, 0, 1f);
			}
			var rp = partner.combat().activeScript(RepeatPressQte.SCR_REPEAT_PRESS);
			qte = (rp != null || partner.combat().hasScriptEvent()) && partner.combat().move().speed() == 0
					? new RepeatPressQte(partner, heroCombat, heroAnim, partnerTweaks, rp != null ? rp.index() : -1) : null;
		}
		updateTimed(in);
		if (qte != null) {
			lastResult = qte.update(in, dt);
			// 영웅 애니도 같은 진행률
			Animation cur = heroAnim.current();
			if (cur != null) {
				heroAnim.seek(qte.meter() * cur.duration);
			}
		} else {
			heroAnim.update(dt);
		}
		return true;
	}

	/** TimedPress: 이벤트 창 진입 → 프롬프트, 틀린 버튼 → 즉시 실패 분기, 창 만료 → 실패 분기. */
	private void updateTimed(Input in) {
		updateTimed(partner, partnerTweaks, in);
	}

	/** 적 combatant 하나에 대한 TimedPress 처리 (테스트에서 직접 호출 가능). */
	public void updateTimed(EnemyFighter en, Tweaks tw, Input in) {
		updateTimed(en.combat(), tw, in);
	}

	/** TimedPress 를 가진 쪽 combatant (보통 적, BMI 올라타기 451–453 는 영웅 기술에 있음). 실패 = 그 combatant 의 같은 창 REQUEST0 분기. */
	public void updateTimed(Combatant ec, Tweaks tw, Input in) {
		var ev = ec.activeScript(TimedPressQte.SCR_TIMED_PRESS);
		if (timed != null && (ev != timedEvent || ec.moveSerial() != timedSerial)) {
			// 창을 벗어남
			if (timed.state() != TimedPressQte.State.PASSED && timed.expire() == TimedPressQte.State.FAILED
					&& ec.moveSerial() == timedSerial) {
				timedFailed++;
				sessionFailed++;
				ec.runWindowBranch(timedEvent);
			}
			timed = null;
			timedEvent = null;
			ev = ec.activeScript(TimedPressQte.SCR_TIMED_PRESS);
		}
		if (timed == null && ev != null && !(ev == timedEvent && ec.moveSerial() == timedSerial)) {
			timed = new TimedPressQte(tw, ev.index(), rnd);
			timedEvent = ev;
			timedSerial = ec.moveSerial();
			sound.accept(timed.showSound);
		}
		if (timed != null && timed.state() == TimedPressQte.State.RUNNING) {
			TimedPressQte.State st = timed.update(in);
			if (st == TimedPressQte.State.PASSED) {
				timedPassed++;
				sessionPassed++;
				sound.accept(timed.hitSound);
			} else if (st == TimedPressQte.State.FAILED) {
				timedFailed++;
				sessionFailed++;
				var e = timedEvent;
				timed = null;
				ec.runWindowBranch(e);
				timedEvent = e; // 같은 창에서 재시작 방지
			}
		}
	}
}
