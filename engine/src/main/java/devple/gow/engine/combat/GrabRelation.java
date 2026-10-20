package devple.gow.engine.combat;

import devple.gow.engine.anim.Animator;
import devple.gow.engine.anim.HeroController;
import devple.gow.engine.game.Tweaks;
import devple.gow.engine.input.PadButton;
import devple.gow.engine.input.ScriptedInput;
import devple.gow.tools.anim.Animation;
import devple.gow.tools.anim.AnimationSet;
import devple.gow.tools.anim.Skeleton;
import devple.gow.tools.combat.CombatFile;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.List;
import java.util.function.Supplier;

/**
 * 잡기 처형의 설계 관계와 "어디서 ○ 를 눌러야 그 관계가 나오는지" (37 문서 §11.5).
 * <p>
 * 원작은 이벤트 19 만인 처형에서 위치를 맞추지 않는다 [확실: 코드] — 오브젝트 위치를 바꾸는 곳은 루트 모션 (0x1EBD78) 과 이벤트 22 창의
 * 0x204BF0 (끌리는 쪽 combatant +0x00 변위, 0x205088) 뿐이고 그 밖에 combatant 변위를 쓰는 곳은 초기화 0x2030B8 뿐이다.
 * 잡기 적중 판정도 거리 조건이 없다: GRAB (영웅 200) 의 공격 2 개 (부위 1 몸 [0.05–0.40], 부위 9 extra1 [0.27–0.32]) 가 상대 구에 처음 닿는
 * 프레임 (0x13B688 모든 구 쌍 → 0x203150 → 0x209AB0) 에 HIT_CONFIRM → 처형 기술. GRAB 은 루트 모션으로 앞으로 나간다 (zero z 1.8 → −30.7)
 * — 멀리서 누르면 크레토스가 달려들어 <b>몸이 처음 닿는 거리</b>에서 잡고, 그 거리가 대부분의 처형 설계 관계와 같다 (사티로스 37.4 / 해골병 27.6 /
 * 켄타우로스 40.6 / 케르베로스 43.3 …). 너무 가까이서 누르면 바로 닿아 관계가 그만큼 어긋난다 (원작도 같다).
 * <p>
 * 이 클래스: 설계 관계 (처형 0 프레임 synchJoint 일치), 처형 기술 조회, 그리고 그 관계가 나오는 시작 거리를 헤드리스 모의로 찾는다
 * (HeroTestScene 배치·시험용 — 원작에는 없는 계산).
 */
public final class GrabRelation {

	private GrabRelation() {
	}

	/** 영웅 GRAB 기술 (A200 ○). */
	public static final int GRAB_MOVE = 200;

	/**
	 * 영웅 GRAB 의 HIT_CONFIRM 분기 중 이 적에게 걸리는 처형 기술 (원작 Match 조건: 상대 종류·상대 HP·잡기 가능 0x10). 없으면 null.
	 */
	public static CombatFile.Move finisher(CombatData hero, int enemyClass, float enemyHp) {
		CombatFile.Move grab = hero.move(GRAB_MOVE);
		if (grab == null) {
			return null;
		}
		for (CombatFile.Branch b : grab.branches()) {
			if (b.input() != CombatInput.EV_HIT_CONFIRM || b.target() < 0 || b.matchA() != enemyClass) {
				continue;
			}
			if (enemyHp < b.argMin() || enemyHp > b.argMax()) {
				continue;
			}
			// 대상 조건 (10 문서 §2.4): 0x40 이 없으면 대상 잡기 가능 == (0x10) — 잡기 원이 뜬 적은 0x10 분기 (GRABREJECT 는 0x10 없음)
			if ((b.flags() & 0x40) == 0 && (b.flags() & 0x10) == 0) {
				continue;
			}
			return hero.move(b.target());
		}
		return null;
	}

	/** 처형 0 프레임의 설계 관계: 적 오브젝트 기준 영웅 오브젝트 (synchJoint 일치, 0x204BF0 의 목표와 같은 식, 적 배율 포함). */
	public static Matrix4f relation(Skeleton heroSk, Animation heroFinish, Skeleton enemySk, Animation enemyFinish, float enemyScale) {
		return synch(enemySk, enemyFinish, enemyScale).mul(synch(heroSk, heroFinish, 1f).invert());
	}

	private static Matrix4f synch(Skeleton sk, Animation a, float scale) {
		int zj = sk.indexOf("zeroJoint"), sj = sk.indexOf("synchJoint");
		if (sj < 0 || a == null) {
			return new Matrix4f();
		}
		float[][] w = a.sample(sk, 0, false).world(sk);
		Matrix4f s = new Matrix4f().set(w[sj]);
		if (zj >= 0) {
			s = new Matrix4f().set(w[zj]).invert().mul(s);
		}
		return s.setTranslation(s.getTranslation(new Vector3f()).mul(scale));
	}

	/** 모의 한 번에 쓰는 새 영웅·적 (서로 독립된 애니·전투 상태). */
	public record Trial(HeroController ctl, Combatant hero, EnemyFighter enemy, Tweaks enemyTweaks) {

		/** 영웅 (같은 데이터로 새 애니·전투) + 적 kind.spawn(). 적은 HP 5 (잡기 원 아래), AI 정지. */
		public static Trial create(Skeleton heroSk, AnimationSet heroSet, Tweaks heroTweaks, CombatData heroData,
								   Supplier<HitVolumes> heroVolumes, EnemyFactory.Kind kind) {
			HeroController ctl = new HeroController(new Animator(heroSk, heroSet, heroTweaks, "/Animation/goHero/"));
			Combatant[] self = new Combatant[1];
			Combatant c = new Combatant(heroData, ctl.animator(), new CombatOwner() {
				public float hp() { return 100; }
				public boolean onGround() { return true; }
				public int bladeLevel() { return 1; }
				public boolean godMode() { return false; }
				public boolean artemisEquipped() { return false; }
				public int selectedMagic() { return 0; }
				public int classId() { return 0; }
				public Vector3f position() { return ctl.position; }
				public float yaw() { return ctl.yaw; }
			});
			self[0] = c;
			HitVolumes v = heroVolumes != null ? heroVolumes.get() : null;
			if (v != null) {
				c.setHitVolumes(v);
			}
			EnemyFighter en = kind.spawn();
			en.hp = 5;
			en.holdAi = true;
			en.setFoe(new Hittable() {
				public Vector3f position() { return ctl.position; }
				public float hp() { return 100; }
				public int classId() { return 0; }
				public int stanceTag() { return self[0].stanceTag(); }
				public boolean invulnerable() { return true; }
				public Result receive(CombatFile.Attack a, Combatant at, float d, Vector3f kn) { return Result.IGNORED; }
			});
			c.setTarget(en);
			return new Trial(ctl, c, en, kind.tweaks());
		}
	}

	/**
	 * 시작 거리 하나의 결과.
	 *
	 * @param start       ○ 를 누를 때 영웅–적 거리 (적 정면 쪽 설계 방향)
	 * @param relYaw      설계 관계의 영웅 요 − 적 요
	 * @param error       결합 0.5 초 뒤 두 synchJoint 월드 거리 (설계 관계와의 차이)
	 * @param link        결합 순간 영웅–적 거리
	 * @param hitProgress 적중 순간 GRAB 진행률 (−1 = 결합 없음)
	 * @param finisher    처형 기술 ID
	 */
	public record Result(float start, float relYaw, float design, float error, float link, float hitProgress, int finisher) {
	}

	/** 설계 관계 (적 기준 영웅 오브젝트) — 처형 기술이 없으면 null. */
	public static Matrix4f relation(Trial t) {
		CombatData hd = t.hero().data();
		CombatFile.Move fin = finisher(hd, t.enemy().classId(), t.enemy().hp);
		if (fin == null || hd.animation(fin) == null) {
			return null;
		}
		Animation hf = hd.animation(fin);
		Animation ef = t.enemy().combat().animationByHash(fin.animHash());
		return relation(t.ctl().animator().skeleton(), hf, t.enemy().animator().skeleton(), ef != null ? ef : hf, t.enemy().scale);
	}

	/**
	 * 영웅을 적 (원점, 요 0) 의 설계 방향 start 거리에 두고 ○ — GameScene 순서 (영웅 → 적 → GrabSync), 결합 전에는 몸 캡슐 밀어내기
	 * ({@link BodyPush}) 를 영웅에. 결합 0.5 초 뒤 두 synchJoint 거리.
	 */
	public static Result run(Trial t, float start) {
		return run(t, start, true);
	}

	/**
	 * @param push 생물 밀어내기 (0x1E2DD8) — 원작 규칙 = true. false 는 비교용 (설계 관계가 GRAB 첫 접촉에서 나오는지만 본다).
	 */
	public static Result run(Trial t, float start, boolean push) {
		Matrix4f rel = relation(t);
		if (rel == null) {
			return null;
		}
		Vector3f tp = rel.getTranslation(new Vector3f());
		Vector3f f = rel.transformDirection(new Vector3f(0, 0, -1));
		float relYaw = (float) Math.atan2(-f.x, -f.z);
		float design = (float) Math.hypot(tp.x, tp.z);
		EnemyFighter en = t.enemy();
		en.pos.set(0, 0, 0);
		en.prevPos.set(en.pos);
		en.yaw = 0;
		Vector3f dir = design > 1e-3f ? new Vector3f(tp.x, 0, tp.z).div(design) : new Vector3f(0, 0, -1);
		t.ctl().position.set(dir.mul(start, new Vector3f()));
		t.ctl().prevPosition.set(t.ctl().position);
		t.ctl().yaw = relYaw;
		final float dt = 1 / 60f;
		ScriptedInput in = new ScriptedInput(0, List.of(new ScriptedInput.Step(0.05f, 0.05f, PadButton.CIRCLE)));
		GrabSync sync = new GrabSync(t.ctl(), t.hero());
		BodyPush.HeroBody heroBody = new BodyPush.HeroBody(t.ctl(), () -> false, t.hero());
		List<BodyPush.Body> heroOnly = List.of(heroBody);
		en.setCrowd(push ? () -> heroOnly : List::of); // 적도 자기 갱신에서 영웅 밖으로 자신을 민다 (0x1E2DD8, Sold +0x1C 비트 1 이면 안 함)
		int after = -1;
		float link = -1, hitProg = -1;
		int fin = finisher(t.hero().data(), en.classId(), en.hp).id();
		for (int i = 0; i < 240; i++) {
			in.advance(dt);
			float gp = t.hero().active() && t.hero().move().id() == GRAB_MOVE ? t.hero().progress() : -1;
			if (!sync.active()) {
				t.hero().update(in, dt);
				if (t.hero().active()) {
					t.ctl().updateCombat(t.hero(), in, 0, dt, en.pos);
				} else {
					t.ctl().update(in, 0, dt);
				}
				float y = t.ctl().position.y;
				heroBody.refresh();
				if (push && heroBody.pushesSelf()) {
					BodyPush.push(heroBody, t.ctl().position, List.of(en), null);
				}
				t.ctl().position.y = y; // 지상 이동은 밀린 뒤 바닥에 붙는다 (0x1EA5F0 → 0x1E3298) — 바닥 없는 모의라 높이 유지
			}
			en.update(dt);
			sync.update(in, en, t.enemyTweaks(), dt);
			if (sync.active() && after < 0) {
				after = 0;
				hitProg = gp;
				link = new Vector3f(t.ctl().position).sub(en.pos).setComponent(1, 0).length();
			}
			if (after >= 0 && ++after == 30) {
				float e = synchWorld(t.ctl().position, t.ctl().yaw, t.ctl().animator(), 1f)
						.distance(synchWorld(en.pos, en.yaw, en.animator(), en.scale));
				return new Result(start, relYaw, design, e, link, hitProg, fin);
			}
		}
		return new Result(start, relYaw, design, Float.NaN, -1, -1, fin);
	}

	/**
	 * 설계 관계가 가장 잘 나오는 시작 거리 (5 단위로 훑고 ±4 를 1 단위로) — 각 시도는 새 {@link Trial}.
	 *
	 * @return 가장 작은 오차의 결과 (처형 기술이 없으면 null)
	 */
	public static Result search(Supplier<Trial> trials, float from, float to) {
		return search(trials, from, to, true);
	}

	/** {@link #search(Supplier, float, float)} — push = 생물 밀어내기 ({@link #run(Trial, float, boolean)}). */
	public static Result search(Supplier<Trial> trials, float from, float to, boolean push) {
		Result best = null;
		for (float d = from; d <= to; d += 5) {
			Result r = run(trials.get(), d, push);
			if (r == null) {
				return null;
			}
			if (!Float.isNaN(r.error()) && (best == null || r.error() < best.error())) {
				best = r;
			}
		}
		if (best == null) {
			return null;
		}
		float c = best.start();
		for (float d = c - 4; d <= c + 4; d += 1) {
			Result r = run(trials.get(), d, push);
			if (r != null && !Float.isNaN(r.error()) && r.error() < best.error()) {
				best = r;
			}
		}
		return best;
	}

	/** 오브젝트 (위치·요·배율) 의 synchJoint 월드 위치 (zeroJoint 기준 — 0x1F7110). */
	public static Vector3f synchWorld(Vector3f pos, float yaw, Animator an, float scale) {
		return new Matrix4f().translation(pos).rotateY(yaw).mul(SyncPull.synchRel(an, scale)).getTranslation(new Vector3f());
	}
}
