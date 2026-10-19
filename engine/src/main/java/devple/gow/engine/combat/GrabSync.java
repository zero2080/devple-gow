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
 *   <li>적 기술에 SCRIPT 이벤트 + 재생 속도 0 → {@link RepeatPressQte}</li>
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
		if (partner == null && hc != null && hc.toLowerCase().startsWith("mai") && target != null) {
			String ec = target.combat().clipName();
			if (ec != null && ec.toLowerCase().startsWith("mai")) {
				partner = target;
				partnerTweaks = targetTweaks;
				lastEnemyClip = null;
				lastResult = null;
			}
		}
		if (partner == null) {
			return false;
		}
		String ec = partner.combat().clipName();
		if (ec == null || !ec.toLowerCase().startsWith("mai")) {
			partner = null;
			qte = null;
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
			qte = partner.combat().hasScriptEvent() && partner.combat().move().speed() == 0
					? new RepeatPressQte(partner, heroCombat, heroAnim, partnerTweaks) : null;
		}
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
}
