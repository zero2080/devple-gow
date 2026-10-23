package devple.gow.engine.combat;

import devple.gow.engine.anim.Animator;
import devple.gow.engine.anim.HeroController;
import devple.gow.engine.game.Tweaks;
import devple.gow.engine.input.Input;
import devple.gow.tools.anim.Animation;
import devple.gow.tools.combat.CombatFile;

/**
 * 잡기/처형 동기화. 원작은 SYNC(18) 분기와 synchJoint 로 두 캐릭터의 애니를 맞춘다 (10 문서 §12, 05 문서 synchJoint).
 * 구현(근사):
 * <ul>
 *   <li>영웅이 MAI* 기술에 들어가면 대상 적과 "결합" — 적의 MAI* 기술이 끝날 때까지 영웅은 같은 이름의 외부 애니(영웅용)를 같은 시간으로 재생.
 *       결합 중에도 영웅 전투 갱신은 돈다 (입력 분기: 해골병 MAISKB00 □ → MAISKB02, 끝 AUTO: 사티로스 431 → 435) — 한쪽이 새 기술을 시작하면
 *       다른 쪽도 같은 ID 로 따라간다 (원작 SYNC(18) 분기, {@link #follow}). 영웅이 이끄는 동기 (영웅 기술 플래그 0x20, 사티로스 435) 는
 *       끊긴 뒤에도 새 기술 시작 때 다시 묶는다 (0x2097A8)</li>
 *   <li>정렬: 어느 쪽 기술이든 이벤트 22 창 안이면 원작 0x204BF0 ({@link SyncPull}) — 그 쪽이 상대를 끈다
 *       (영웅 221 MAISKB02 [0–0.76], 421 MAISIRKILL [0.44–0.91] = 영웅이 적을). 그런 기술의 창 밖은 원작처럼 위치를 건드리지 않는다.
 *       이벤트 22 가 없는 처형 (사이클롭스·미노타우로스·하피·고르곤·메두사 … 디스크 전체 조사, 37 문서 §11) 은 원작도 정렬이 없다
 *       (이벤트 19 = 동기 유지만 — 잡기 적중 위치 + 두 쪽 루트 모션 {@link devple.gow.engine.anim.RootMotion}, 37 문서 §11.4).
 *       결합 중 영웅 루트 모션 (수직 포함) 은 여기서, 적은 자기 갱신에서. 결합 없는 적 기술 끌기는 {@link #lonePull}</li>
 *   <li>적 기술에 SCRIPT SCR_RepeatPress + 재생 속도 0 → {@link RepeatPressQte} (idx = 템플릿). 영웅 기술에 있으면 (사티로스 432/435) 속도와 무관</li>
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
	/** 원작 0x204BF0 끌기 (+0x1BC 비율). */
	private final SyncPull pull = new SyncPull();
	/** 마지막 갱신에서 끈 쪽: 1 = 적이 영웅을, 2 = 영웅이 적을, 0 = 없음. */
	private int pulling;

	/** 마지막 갱신의 끌기 (1 = 적 → 영웅, 2 = 영웅 → 적, 0 = 없음). */
	public int pulling() {
		return pulling;
	}

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

	/** 결합 상대 (잡고 있는 적, 원작 combatant +0x84) — 없으면 null. */
	public EnemyFighter partner() {
		return partner;
	}

	public RepeatPressQte qte() {
		return qte;
	}

	/** 동기 기술 플래그 (CombatFile 기술 +0x0E): 0x20 = 시작 시 공격 대상과 동기 연결 (적·보스 쪽), 0x40 = 연결만 받음 (영웅 쪽) — 0x209790. */
	static final int SYNC_FLAGS = 0x60;

	/** 결합 시작: 두 쪽 MAI* 애니, 또는 적 기술 (플래그 0x20) 과 같은 ID 의 영웅 기술 (아기 히드라 821–824 ↔ 영웅 006 의 821–824). */
	private static boolean linkable(Combatant hero, Combatant enemy) {
		String hc = hero.clipName(), ec = enemy.clipName();
		if (hc != null && ec != null && hc.toLowerCase().startsWith("mai") && ec.toLowerCase().startsWith("mai")) {
			return true;
		}
		return hero.active() && enemy.active() && (enemy.move().flags() & 0x20) != 0 && hero.move().id() == enemy.move().id();
	}

	/** 결합 유지: 적 MAI* 애니 또는 동기 플래그 기술 (원작: 0x60 이 없는 기술이 시작되면 +0x84 = 0, 0x2050D8). */
	private static boolean stillLinked(Combatant enemy) {
		String ec = enemy.clipName();
		return ec != null && (ec.toLowerCase().startsWith("mai") || enemy.active() && (enemy.move().flags() & SYNC_FLAGS) != 0);
	}

	/** @return 결합 중이면 true (게임 쪽은 영웅 이동/전투 갱신을 건너뛴다) */
	public boolean update(Input in, EnemyFighter target, Tweaks targetTweaks, float dt) {
		String hc = heroCombat.clipName();
		if (target != null) {
			// 적 세트에 없는 처형 애니(예 사이클롭스 MAICYW*)는 영웅 세트의 같은 애니로 시간·이름을 맞춘다
			target.combat().setClipFallback(heroCombat::animationByHash);
		}
		// 영웅이 이끄는 동기 (영웅 기술 플래그 0x20, 적에 같은 ID — 사티로스 431 끝 → 영웅 AUTO 435 → 사티로스 SYNC 분기로 435): 원작은 0x20 기술
		// 시작 때 공격 대상과 다시 연결 (0x2097A8). 새로 시작한 영웅 기술에서만 (끊긴 뒤 같은 기술로 다시 묶지 않음)
		boolean heroLead = partner == null && target != null && !target.dead() && heroCombat.active()
				&& (heroCombat.move().flags() & 0x20) != 0 && heroCombat.moveSerial() != leadSerial
				&& target.combat().data().move(heroCombat.move().id()) != null
				&& !(target.combat().active() && target.combat().move().id() == heroCombat.move().id());
		leadSerial = heroCombat.moveSerial();
		if (partner == null && target != null && (heroLead || linkable(heroCombat, target.combat()))) {
			partner = target;
			partnerTweaks = targetTweaks;
			lastEnemyClip = null;
			lastResult = null;
			qteKey = Integer.MIN_VALUE;
			justLinked = true;
			if (heroLead) {
				target.combat().forceMove(heroCombat.move().id()); // 적 SYNC 분기: 같은 ID 로 따라온다
				followed = 1;
			}
			followHero = heroCombat.moveSerial();
			followEnemy = target.combat().moveSerial();
			sessionPassed = sessionFailed = 0;
			pull.link(); // 0x209830: 동기 연결 시작 → 비율 0.1
			linkFloor = hero.position.y;
		}
		if (partner == null) {
			lonePull(target);
			return false;
		}
		String ec = partner.combat().clipName();
		if (!stillLinked(partner.combat())) {
			partner.rootHold = false;
			partner = null;
			qte = null;
			timed = null;
			timedEvent = null;
			pull.unlink();
			pulling = 0;
			followed = 0;
			hero.rootHold = false;
			hero.resetRoot();
			return false;
		}
		// 영웅 전투 갱신 (원작: 동기 중에도 영웅 Combat_Update 가 돈다 — 입력 분기 (해골병 MAISKB00 □ → MAISKB02), 끝 AUTO (사티로스 431 → 432/435)).
		// 시간은 아래에서 애니로 진행
		if (!justLinked) {
			heroCombat.update(in, dt); // 결합 틱에는 장면이 이미 갱신했다
		}
		justLinked = false;
		follow();
		hc = heroCombat.clipName();
		if (!ec.equals(lastEnemyClip)) {
			lastEnemyClip = ec;
			Animation a = heroAnim.find(ec);
			if (a != null && (hc == null || !hc.equalsIgnoreCase(ec))) {
				heroAnim.playClip(a, 0, 1f);
			}
		}
		int key = partner.combat().moveSerial() * 31 + heroCombat.moveSerial();
		if (key != qteKey) {
			qteKey = key;
			var rp = partner.combat().activeScript(RepeatPressQte.SCR_REPEAT_PRESS);
			if ((rp != null || partner.combat().hasScriptEvent()) && partner.combat().move().speed() == 0) {
				qte = new RepeatPressQte(partner, heroCombat, heroAnim, partnerTweaks, rp != null ? rp.index() : -1);
			} else if ((rp = heroCombat.activeScript(RepeatPressQte.SCR_REPEAT_PRESS)) != null && partnerTweaks != null) {
				// 영웅 기술의 연타 (사티로스 MAISATSTRUGGLE 432/435, idx 130/131 — 템플릿은 적 WAD): 게이지가 두 쪽 진행률, 결과 = 적 Pass/Fail Move
				qte = new RepeatPressQte(partner, heroCombat, heroAnim, partnerTweaks, rp.index());
			} else {
				qte = null;
			}
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
		if (followed == 1) {
			matchTime(heroAnim, partner.animator());
		} else if (followed == 2) {
			matchTime(partner.animator(), heroAnim);
		}
		followed = 0;
		align();
		return true;
	}

	/**
	 * 동기 정렬 + 영웅 루트 모션 (결합 중 영웅 이동 갱신은 장면이 건너뛰므로 여기서). 이벤트 22 <b>또는 19</b> 창: 원작 0x204BF0 (적 기술 → 영웅을,
	 * 영웅 기술 → 적을 끈다 — 0x2441E0(+0x1AC, 0x24) 의 0x4 = 이벤트 19) — 끌리는 쪽은 루트 모션 없음 (0x1EC058). 영웅 기술 진행률 = 영웅 애니 시간 / 길이.
	 * 창 밖은 잡기 적중 자리 + 두 쪽 루트 모션 (이동·회전, 37 문서 §11.4–11.5).
	 */
	private void align() {
		pulling = 0;
		CombatFile.Move hm = heroCombat.active() ? heroCombat.move() : null;
		Animation cur = heroAnim.current();
		float hp = cur != null && cur.duration > 0 ? Math.min(1f, heroAnim.time() / cur.duration) : 0;
		boolean enemyPulls = SyncPull.linkedPullWindow(partner.combat()) != null;
		boolean heroPulls = !enemyPulls && hm != null && SyncPull.linkedPullWindow(hm, hp) != null;
		// 영웅 루트 모션: 물리가 꺼져 있어 수직도 (사이클롭스 등에 올라탐), 잡은 자리 바닥 아래로는 내려가지 않게 [근사]
		hero.prevPosition.set(hero.position);
		hero.rootHold = enemyPulls;
		hero.applyRoot(true);
		hero.position.y = Math.max(linkFloor, hero.position.y);
		if (heroPulls && !partner.rootHold) {
			partner.undoRootMotion(); // 적은 이번 틱에 이미 갱신됨 — 끌리는 첫 프레임부터 D = 단위 (0x1EBF8C)
		}
		partner.rootHold = heroPulls; // 다음 틱부터는 적 갱신이 막는다
		if (enemyPulls) {
			float[] y = {hero.yaw};
			pull.step(SyncPull.target(partner.pos, partner.yaw, partner.animator(), partner.scale, heroAnim, 1f), hero.position, y);
			hero.yaw = y[0];
			hero.prevPosition.set(hero.position);
			pulling = 1;
		} else if (heroPulls) {
			float[] y = {partner.yaw};
			pull.step(SyncPull.target(hero.position, hero.yaw, heroAnim, 1f, partner.animator(), partner.scale), partner.pos, y);
			partner.yaw = y[0];
			partner.prevPos.set(partner.pos);
			pulling = 2;
		}
	}

	private int qteKey = Integer.MIN_VALUE, followHero = -1, followEnemy = -1;
	private boolean justLinked;
	private int leadSerial = -1;
	/** 이번 틱 같은 ID 따라가기: 1 = 적이 영웅을, 2 = 영웅이 적을 ({@link #matchTime}). */
	private int followed;

	/**
	 * 같은 ID 기술 따라가기 (원작 SYNC(18) 분기 — 동기 상대가 새 기술을 시작하면 같은 ID 로, 37 문서 §11.5): 적이 새 기술 (실패 분기·Pass/Fail Move)
	 * → 영웅도 같은 ID, 영웅이 새 기술 (입력·AUTO 분기) → 적도 같은 ID. 상대 데이터에 그 ID 가 없으면 따라가지 않는다 (이름 같은 애니로 맞춤).
	 */
	private void follow() {
		Combatant ec = partner.combat();
		boolean enemyNew = ec.moveSerial() != followEnemy, heroNew = heroCombat.moveSerial() != followHero;
		int em = ec.active() ? ec.move().id() : -1, hm = heroCombat.active() ? heroCombat.move().id() : -1;
		if (enemyNew && em >= 0 && em != hm && (ec.move().flags() & SYNC_FLAGS) != 0 && heroCombat.data().move(em) != null) {
			heroCombat.forceMove(em);
			followed = 2;
		} else if (heroNew && hm >= 0 && em != hm && (heroCombat.move().flags() & SYNC_FLAGS) != 0 && ec.data().move(hm) != null) {
			ec.forceMove(hm);
			followed = 1;
		}
		followEnemy = ec.moveSerial();
		followHero = heroCombat.moveSerial();
	}

	/**
	 * 따라간 쪽 애니 시간 = 이끈 쪽 (원작: SYNC 분기로 같은 프레임에 같은 ID 를 0 부터 — 엔진은 이끈 쪽이 이번 틱에 이미 진행해 따라간 쪽이
	 * 1–2 틱 늦던 것, 해골 220 → 223 / 221). 두 쪽 기술 속도가 같아 (220 1.5, 221·223 1.0) 이후 같은 시간으로 간다.
	 */
	private static void matchTime(Animator from, Animator to) {
		if (from.current() != null && to.current() != null) {
			to.seek(Math.min(from.time(), to.current().duration));
		}
	}

	/** 결합 시작 때 영웅 높이 (루트 모션 수직 하한). */
	private float linkFloor;
	/** 결합 없는 끌기의 연결 (적 기술 순번). */
	private EnemyFighter lonePuller;
	private int loneSerial = -1;

	/** 결합 없는 끌기 후보 (장면의 모든 적). 없으면 update 의 대상만. */
	private java.util.function.Supplier<? extends Iterable<EnemyFighter>> pullers;

	/** 결합 없는 끌기 후보 = 장면의 적 전부 (원작: 끄는 쪽은 자기 공격 대상이 영웅인 적 — 가장 가까운 적이 아니다). */
	public void setPullers(java.util.function.Supplier<? extends Iterable<EnemyFighter>> s) {
		pullers = s;
	}

	/** 이 적이 지금 영웅을 끌 수 있는지: 플래그 0x60 기술 + 이벤트 22 창 + 동기 상대 = 영웅. */
	private boolean pullsHero(EnemyFighter en) {
		Combatant ec = en != null ? en.combat() : null;
		return ec != null && ec.active() && (ec.move().flags() & SYNC_FLAGS) != 0 && !en.dead()
				&& SyncPull.pullWindow(ec) != null && ec.target() != null
				&& ec.target().position().distanceSquared(hero.position) < 1f;
	}

	/**
	 * 결합(애니 잠금) 없이 적 기술의 이벤트 22 창이 영웅을 끈다 — 원작은 동기 연결 (+0x84) 이 플래그 0x20 기술 시작 때 <b>그 적의 공격 대상</b>
	 * (combatant +0x80, 0x2097A8: +0x84 가 비어 있으면 +0x84 = +0x80, 기술 플래그 0x8 이면 +0x80 = 0) 과 맺어지고 영웅 기술과 무관하다
	 * (아기 히드라 825 BHABABYGRAB [0–0.22]: 영웅은 COMBO5A → GRABREJECT). 끄는 적 = 공격 대상이 영웅인 적 중 이미 끄는 적, 없으면 처음 찾은 적
	 * [확실: 연결 규칙 / 여러 적이 동시에 끄는 경우는 원작도 마지막 0x204BF0 이 이김 — 엔진은 하나만].
	 */
	private void lonePull(EnemyFighter target) {
		pulling = 0;
		if (!pullsHero(lonePuller)) {
			EnemyFighter found = null;
			if (pullers != null) {
				for (EnemyFighter en : pullers.get()) {
					if (pullsHero(en)) {
						found = en;
						break;
					}
				}
			} else if (pullsHero(target)) {
				found = target;
			}
			if (found == null) {
				if (lonePuller != null) {
					hero.rootHold = false;
					lonePuller = null;
					pull.unlink();
				}
				return;
			}
			target = found;
		} else {
			target = lonePuller;
		}
		Combatant ec = target.combat();
		if (target != lonePuller || ec.moveSerial() != loneSerial) {
			if (target != lonePuller) {
				pull.link(); // 0x209830: 새 연결 → 0.1
			}
			lonePuller = target;
			loneSerial = ec.moveSerial();
		}
		if (!hero.rootHold) {
			hero.undoRoot(); // 영웅은 이번 틱에 이미 갱신됨 — 끌리는 첫 프레임부터 D = 단위 (0x1EBF8C)
		}
		float[] y = {hero.yaw};
		pull.step(SyncPull.target(target.pos, target.yaw, target.animator(), target.scale, heroAnim, 1f), hero.position, y);
		hero.yaw = y[0];
		hero.prevPosition.set(hero.position);
		hero.vy = 0;
		hero.rootHold = true; // 다음 틱부터 영웅 루트 모션 없음 (끌리는 쪽)
		pulling = 1;
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
