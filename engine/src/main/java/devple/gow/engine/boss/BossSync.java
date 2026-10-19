package devple.gow.engine.boss;

import devple.gow.engine.anim.HeroController;
import devple.gow.engine.combat.Combatant;
import devple.gow.engine.combat.EnemyFighter;
import devple.gow.engine.combat.GrabSync;
import devple.gow.engine.combat.RepeatPressQte;
import devple.gow.engine.combat.TimedPressQte;
import devple.gow.engine.game.Tweaks;
import devple.gow.engine.input.Input;

import java.util.function.IntPredicate;

/**
 * 보스 동기 기술 (원작 SYNC(18) 분기 + 같은 ID 기술 쌍). 영웅 CombatFile 006(800번대: 히드라 BHA*)·002(605–623: 아레스 MAIBAR*)와
 * 보스 CombatFile 이 같은 기술 ID 를 가진다 — 한쪽이 동기 ID 기술을 시작하면 다른 쪽도 같은 ID 로 들어간다.
 * <ul>
 *   <li>RepeatPress(SCRIPT 0xE513E9CB, idx = 템플릿) 가 있는 기술: {@link RepeatPressQte} 게이지가 두 쪽 진행률. 결과 Pass/Fail Move
 *       는 보스에 요청되고 영웅은 다음 틱에 같은 ID 로 따라간다 (히드라 807 → 809/808, 811 → 812/816, 아레스 606 → 608/607)</li>
 *   <li>TimedPress(0xF980EF69): 이벤트 창 = 프롬프트, 실패 → 같은 창의 REQUEST0 실패 분기 (히드라 810 [0.39–0.47] → 813)</li>
 *   <li>정렬: {@link Align#BOSS_TO_HERO} = 사람 크기 보스(아레스)를 영웅 기준점에 (GrabSync 와 같은 규칙),
 *       {@link Align#NONE} = 고정 보스(히드라, 바다에 고정) — 동기 애니는 각자 원점 기준 [추정]</li>
 * </ul>
 */
public final class BossSync {

	/**
	 * SYNCH = 두 애니의 synchJoint 를 맞춘다: 영웅 = 보스 · S_보스 · S_영웅⁻¹ (수평 위치·요). 예 포옹 MAIEVIHug*: 가족 xmc synch
	 * (0, 0, −16) 180°, 영웅 (0, 0, −32) 0° → 영웅은 가족 정면 48 에서 가족을 마주 봄. 분신 잡기 EVIGrabHero*(evi (0,0,−32) 0°,
	 * 영웅 (0,0,−32) 180°)도 같은 규칙이면 64 앞 [추정: synchJoint 의미].
	 */
	public enum Align { NONE, BOSS_TO_HERO, HERO_TO_BOSS, SYNCH }

	private final IntPredicate syncId;
	private final Align align;
	private int heroSerial = -1, bossSerial = -1;
	private RepeatPressQte qte;
	private int qteSerial = -1;
	private RepeatPressQte.State lastResult;
	private GrabSync timedHelper;
	/** 마지막 쌍 (기술 ID, 없으면 -1). */
	private int paired = -1;
	private org.joml.Vector3f anchor;

	public BossSync(IntPredicate syncId, Align align) {
		this.syncId = syncId;
		this.align = align;
	}

	/** 지금 영웅과 보스가 같은 동기 기술 중인지. */
	public boolean active() {
		return paired >= 0;
	}

	public int pairedMove() {
		return paired;
	}

	public RepeatPressQte qte() {
		return qte;
	}

	public RepeatPressQte.State lastResult() {
		return lastResult;
	}

	/** 진행 중인 타이밍 프롬프트 (HUD). */
	public TimedPressQte timed() {
		return timedHelper != null ? timedHelper.timed() : null;
	}

	public int timedPassed() {
		return timedHelper != null ? timedHelper.timedPassed : 0;
	}

	public int timedFailed() {
		return timedHelper != null ? timedHelper.timedFailed : 0;
	}

	/** 두 쪽 전투 갱신 뒤 매 틱. */
	public void update(Input in, HeroController ctl, Combatant hero, EnemyFighter boss, Tweaks tw, float dt) {
		if (timedHelper == null) {
			timedHelper = new GrabSync(ctl, hero);
		}
		Combatant bc = boss.combat();
		int hm = hero.active() ? hero.move().id() : -1;
		int bm = bc.active() ? bc.move().id() : -1;
		boolean heroNew = hero.moveSerial() != heroSerial, bossNew = bc.moveSerial() != bossSerial;
		if (bossNew && syncId.test(bm) && hm != bm) {
			follow(hero, bm);
		} else if (heroNew && syncId.test(hm) && hm != bm && !boss.dead()) {
			follow(bc, hm);
		}
		hm = hero.active() ? hero.move().id() : -1;
		bm = bc.active() ? bc.move().id() : -1;
		heroSerial = hero.moveSerial();
		bossSerial = bc.moveSerial();
		paired = syncId.test(bm) && hm == bm ? bm : -1;
		if (paired < 0) {
			qte = null;
			if (anchor != null) {
				// 동기 끝: 원래 서 있던 곳으로 (HERO_TO_BOSS — 동기 애니는 보스 원점 기준)
				ctl.position.set(anchor);
				ctl.prevPosition.set(anchor);
				anchor = null;
			}
			return;
		}
		if (align == Align.BOSS_TO_HERO) {
			boss.pos.set(ctl.position);
			boss.prevPos.set(ctl.prevPosition);
			boss.yaw = ctl.yaw;
		} else if (align == Align.HERO_TO_BOSS) {
			if (anchor == null) {
				anchor = new org.joml.Vector3f(ctl.position);
			}
			// 영웅 동기 애니의 zeroJoint = 보스 원점 기준 영웅 위치 (예 BHAbossGrabLeft (−40, 682, 432): 높이 682 = 갑판 위 단상) [추정]
			var sk = ctl.skeleton();
			int zj = sk.indexOf("zeroJoint");
			org.joml.Vector3f off = new org.joml.Vector3f();
			if (zj >= 0 && ctl.animator().current() != null) {
				float[] w = ctl.animator().pose().world(sk)[zj];
				off.set(w[12], w[13], w[14]);
			}
			// 영웅 zeroJoint 는 보스 프레임에서 z 만 뒤집힌 위치다: 보스 물기 애니의 synchJoint(물기가 떨어지는 단상 지점)와 대응 —
			// BHAbossBiteMiddle (1, 679, -382) <-> 영웅 BHAbossKill zero (1, 682, 383), BiteLeft (-74, 679, -449) <-> BHAbossGrabLeft
			// zero (-40, 682, 432), BiteRight (69, 679, -451) <-> GrabRight (52, 682, 436). 전엔 180° 회전이라 좌우(x)가 뒤바뀌어 잡기 카메라가
			// 머리 쪽으로 돌면 크레토스가 화면 밖(카메라 축에서 45-57°)이었다 [추정: 대응 관계는 데이터]. 영웅은 보스를 마주 본다 (요 + π).
			float yaw = boss.yaw + (float) Math.PI;
			off.z = -off.z;
			off.rotateY(boss.yaw).add(boss.pos);
			ctl.position.set(off);
			ctl.prevPosition.set(off);
			ctl.yaw = yaw;
			ctl.vy = 0;
		}
		if (align == Align.SYNCH) {
			float[] sb = synch(boss.animator()), sh = synch(ctl.animator());
			if (sb != null && sh != null) {
				float ab = (float) Math.atan2(sb[8], sb[10]), ah = (float) Math.atan2(sh[8], sh[10]);
				// S_h⁻¹(0) = −R(−ah)·p_h → S_b → 보스 월드
				org.joml.Vector3f v = new org.joml.Vector3f(-sh[12], 0, -sh[14]).rotateY(-ah).rotateY(ab).add(sb[12], 0, sb[14]);
				v.rotateY(boss.yaw).add(boss.pos.x, 0, boss.pos.z);
				ctl.position.set(v.x, ctl.position.y, v.z);
				ctl.prevPosition.set(ctl.position);
				ctl.yaw = boss.yaw + ab - ah;
			}
		}
		// 연타: 보스 기술(또는 영웅 기술)의 SCR_RepeatPress
		var rp = bc.activeScript(RepeatPressQte.SCR_REPEAT_PRESS);
		if (rp == null) {
			rp = hero.activeScript(RepeatPressQte.SCR_REPEAT_PRESS);
		}
		if (rp != null && (qte == null || qteSerial != bc.moveSerial())) {
			qte = new RepeatPressQte(boss, hero, ctl.animator(), tw, rp.index());
			qteSerial = bc.moveSerial();
		}
		if (qte != null && qteSerial == bc.moveSerial()) {
			RepeatPressQte.State s = qte.update(in, dt);
			if (s == RepeatPressQte.State.RUNNING) {
				hero.setProgress(qte.meter());
			} else {
				lastResult = s;
			}
		}
		// 타이밍 버튼
		timedHelper.updateTimed(boss, tw, in);
	}

	/** 현재 자세의 synchJoint 행렬 (없으면 null). */
	private static float[] synch(devple.gow.engine.anim.Animator an) {
		int j = an.skeleton().indexOf("synchJoint");
		return j < 0 || an.current() == null ? null : an.pose().world(an.skeleton())[j];
	}

	private static void follow(Combatant c, int move) {
		if (!c.request(move)) {
			c.forceMove(move);
		}
	}
}
