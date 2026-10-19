package devple.gow.engine.combat;

import devple.gow.engine.anim.Animator;
import devple.gow.engine.asset.AnimatedModel;
import devple.gow.engine.game.Tweaks;
import devple.gow.tools.anim.AnimationSet;
import devple.gow.tools.anim.Skeleton;
import devple.gow.tools.combat.CombatFile.Attack;
import devple.gow.tools.wad.WadFile;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.List;
import java.util.Random;

/**
 * 적 1체 = 원작 데이터(스켈레톤/애니/CombatFile/AI1 템플릿/결정 트리) + 원작 AI({@link AiBrain}, 17 문서).
 * <ul>
 *   <li>기술·피격 반응·사망: 적 CombatFile 그대로 — 원작과 같은 Combatant</li>
 *   <li>수치: TWK {@code /TweakTemplates/AI1/NNN/} ({@link AiTemplate})</li>
 *   <li>의사결정: TWK_DTre 결정 트리 + 공격 토큰 + 포위 이동 + BlockMod ({@link AiBrain}). 결정 트리가 없는 적
 *       (스크립트 구동 보스 등)만 예전 단순 규칙(접근 → 무작위 공격)으로 대체</li>
 * </ul>
 */
public final class EnemyFighter implements Hittable, CombatOwner {

	/** 회전 속도 (rad/s) [근사: 원작 0x1CE240 회전 보간 값 미확인]. */
	static final float TURN_RATE = 8f;

	private final Combatant combat;
	private final Animator anim;
	private final int classId;
	private final AiTemplate tmpl;
	private final AiBrain brain;
	private final Tweaks tw;
	private final float maxHp;
	private final float circleHp;
	private final float aiDamageMult;
	private final int[] attackMoves;
	private final String walkClip;
	private final String idleClip;
	private final float walkSpeed;
	/** 달리기 클립 (없으면 null) / 초당 이동량. 원거리 접근에 사용. */
	private final String runClip;
	private final float runSpeed;
	/** 모델 크기 = ScaleCenter/100 + ScaleRange/100 × (0.5 − rand) (0x1CEE00). */
	public final float scale;
	private final Random rnd;
	public final Vector3f pos = new Vector3f();
	public final Vector3f prevPos = new Vector3f();
	public float yaw;
	private float prevYaw;
	public float hp;
	private float think = 1f;
	private Hittable foe;
	private float deadTime;
	private static int spawnCount;

	/**
	 * @param ai1         AI1 템플릿 번호 (미노타우로스 Lv1 = 50)
	 * @param combatFiles 적 CombatFile 이름들 (TWK_CombatFile_050 ...)
	 */
	public EnemyFighter(WadFile wad, Tweaks tw, Skeleton sk, AnimationSet set, int ai1, List<String> combatFiles,
						int[] attackMoves, int blockMove, String idleClip, String walkClip, float walkSpeed) {
		this(wad, tw, sk, set, ai1, combatFiles, attackMoves, blockMove, idleClip, walkClip, walkSpeed, "/Animation/goMIN/");
	}

	public EnemyFighter(WadFile wad, Tweaks tw, Skeleton sk, AnimationSet set, int ai1, List<String> combatFiles,
						int[] attackMoves, int blockMove, String idleClip, String walkClip, float walkSpeed, String animNode) {
		this(wad, tw, sk, set, ai1, combatFiles, attackMoves, blockMove, idleClip, walkClip, walkSpeed, animNode, AiData.load(wad));
	}

	public EnemyFighter(WadFile wad, Tweaks tw, Skeleton sk, AnimationSet set, int ai1, List<String> combatFiles,
						int[] attackMoves, int blockMove, String idleClip, String walkClip, float walkSpeed, String animNode,
						AiData ai) {
		this.classId = ai1;
		this.tw = tw;
		this.tmpl = new AiTemplate(tw, ai1);
		this.maxHp = Math.max(tmpl.hitPoints, tmpl.maxHitPoints) & 0xFFFF;
		this.hp = tmpl.hitPoints & 0xFFFF;
		this.aiDamageMult = tmpl.damageMult;
		this.circleHp = tmpl.circleHp; // 65535 = 잡기 원 없음
		this.attackMoves = attackMoves;
		this.idleClip = idleClip;
		this.walkClip = walkClip;
		this.anim = new Animator(sk, set, tw, animNode);
		// 이동 속도: 루프 이동 클립의 zeroJoint Z × 15fps (25 문서, 크라토스와 같은 규칙). 없으면 주어진 값
		float ws = clipSpeed(anim, walkClip);
		this.walkSpeed = ws > 1f ? ws : walkSpeed;
		String rc = null;
		for (String cand : new String[]{"runfront", "runforward", "run"}) {
			for (devple.gow.tools.anim.AnimationSet.Group g : set.groups) {
				for (devple.gow.tools.anim.Animation an : g.acts) {
					if (rc == null && an.hasSkeletalData() && an.name.toLowerCase(java.util.Locale.ROOT).startsWith(cand)) {
						rc = an.name;
					}
				}
			}
		}
		float rs = rc != null ? clipSpeed(anim, rc) : 0;
		this.runClip = rs > this.walkSpeed ? rc : null;
		this.runSpeed = rs;
		long seed = 7L + 7919L * (spawnCount++);
		this.rnd = new Random(seed);
		this.scale = tmpl.scaleCenter / 100f + tmpl.scaleRange / 100f * (0.5f - rnd.nextFloat());
		this.combat = new Combatant(new CombatData(wad, combatFiles, set), anim, this);
		DecisionTree tree = ai != null ? ai.tree(tmpl.decisionTree) : null;
		AiData.Soldier sold = ai != null ? ai.soldier(tmpl.soldier) : null;
		this.brain = new AiBrain(this, tmpl, tree, AiData.blockMod(tw, tmpl.blockMod), sold, seed);
		setDifficulty(1);
		anim.play(idleClip, true);
	}

	/** 난이도 0..3 (Easy..Impossible): "AI Recovery Time Mult" 를 공격 후 대기에 적용. */
	public void setDifficulty(int d) {
		float m = tw.f("/GlobGame/" + DamageRules.DIFFICULTY[Math.max(0, Math.min(3, d))] + "/", "AI Recovery Time Mult", 1f);
		brain.setRecoveryScale(() -> m);
	}

	public AiBrain brain() {
		return brain;
	}

	public AiTemplate template() {
		return tmpl;
	}

	public int ai1() {
		return classId;
	}

	public float maxHp() {
		return maxHp;
	}

	public Combatant combat() {
		return combat;
	}

	public Animator animator() {
		return anim;
	}

	public void setFoe(Hittable f) {
		foe = f;
		combat.setTarget(f);
		brain.setTarget(f);
	}

	/** 공격자 배율 = AI1 Damage Multiplier × (영웅 쪽 받는 피해 배율은 영웅이 적용). */
	public void setIncomingScale(java.util.function.Supplier<Float> heroIncoming) {
		combat.setDamageScale(() -> aiDamageMult * heroIncoming.get());
	}

	public void update(float dt) {
		prevPos.set(pos);
		prevYaw = yaw;
		combat.update(null, dt);
		if (hp <= 0) {
			deadTime += dt;
			brain.update(dt);
			anim.update(dt);
			return;
		}
		if (brain.tree != null) {
			brain.update(dt);
			if (!combat.active()) {
				if (!Float.isNaN(brain.face)) {
					yaw = turn(yaw, brain.face, TURN_RATE * dt);
				}
				float sp = brain.move.length();
				if (sp > 0.01f) {
					boolean run = runClip != null && brain.wantsRun();
					anim.play(run ? runClip : walkClip, false);
					float v = run ? runSpeed : walkSpeed;
					pos.add(brain.move.x * v * dt, 0, brain.move.z * v * dt);
				} else {
					anim.play(idleClip, false);
				}
			}
		} else if (!combat.active() && foe != null && attackMoves.length > 0) {
			// 결정 트리 없는 적: 예전 단순 규칙 (접근 → 무작위 공격)
			Vector3f d = new Vector3f(foe.position()).sub(pos);
			float dist = (float) Math.hypot(d.x, d.z);
			yaw = (float) Math.atan2(-d.x, -d.z);
			if (dist > 60) {
				anim.play(walkClip, false);
				pos.add(d.x / dist * walkSpeed * dt, 0, d.z / dist * walkSpeed * dt);
			} else {
				anim.play(idleClip, false);
				if ((think -= dt) <= 0) {
					think = 0.8f + rnd.nextFloat();
					combat.request(attackMoves[rnd.nextInt(attackMoves.length)]);
				}
			}
		}
		if (combat.active() && combat.move().speed() == 0) {
			// 미니게임 기술: 진행은 QTE 게이지가 setProgress 로 지정
		} else if (combat.active() && combat.hitPause() <= 0) {
			anim.update(dt);
		} else if (!combat.active()) {
			anim.update(dt);
		}
	}

	/**
	 * 루프 이동 클립의 초당 이동량. zeroJoint Z × 15 (크라토스 규칙, 25 문서)가 0 이면(적 걷기 클립은 제자리 루프)
	 * 디딘 발(가장 낮은 발 관절)이 뒤로 미끄러지는 속도로 추정한다 [근사: 원작 적 이동 속도 출처 미분석].
	 */
	static float clipSpeed(Animator anim, String clip) {
		Skeleton sk = anim.skeleton();
		int zj = sk.indexOf("zeroJoint");
		devple.gow.tools.anim.Animation a = clip != null ? anim.find(clip) : null;
		if (a == null || !a.hasSkeletalData()) {
			return 0;
		}
		if (zj >= 0) {
			float z = Math.abs(a.sample(sk, 0, true).pos[zj][2]) * devple.gow.engine.anim.HeroController.ROOT_FPS;
			if (z > 1f) {
				return z;
			}
		}
		List<Integer> feet = new java.util.ArrayList<>();
		for (Skeleton.Joint j : sk.joints) {
			String n = j.name().toLowerCase(java.util.Locale.ROOT);
			if (n.contains("toe") || n.contains("metatarsal") || n.contains("foot") || n.contains("ankle")) {
				feet.add(j.index());
			}
		}
		if (feet.isEmpty() || a.duration <= 0) {
			return 0;
		}
		int n = 48;
		float dt = a.duration / n, slide = 0, time = 0;
		int prevFoot = -1;
		float prevZ = 0;
		for (int i = 0; i <= n; i++) {
			float[][] w = a.sample(sk, i * dt, true).world(sk);
			int low = feet.getFirst();
			for (int f : feet) {
				if (w[f][13] < w[low][13]) {
					low = f;
				}
			}
			if (low == prevFoot) {
				slide += Math.abs(w[low][14] - prevZ);
				time += dt;
			}
			prevFoot = low;
			prevZ = w[low][14];
		}
		return time > 0 ? slide / time : 0;
	}

	public float walkSpeed() {
		return walkSpeed;
	}

	private static float turn(float from, float to, float max) {
		float d = (float) Math.IEEEremainder(to - from, Math.PI * 2);
		return from + Math.max(-max, Math.min(max, d));
	}

	public boolean dead() {
		return hp <= 0;
	}

	public float deadTime() {
		return deadTime;
	}

	public Matrix4f world(Matrix4f out, float alpha) {
		Vector3f p = new Vector3f(prevPos).lerp(pos, alpha);
		return out.translation(p).rotateY(yaw).scale(scale); // 모델 -Z 정면 (34 문서 §4), ScaleCenter
	}

	// ---- Hittable ----

	@Override
	public Vector3f position() {
		return pos;
	}

	@Override
	public float hp() {
		return hp;
	}

	@Override
	public int classId() {
		return classId;
	}

	@Override
	public int stanceTag() {
		return combat.stanceTag();
	}

	@Override
	public boolean grabbable() {
		return circleHp < 65535 && hp > 0 && hp <= circleHp;
	}

	@Override
	public HitVolumes hitVolumes() {
		return combat.hitVolumes();
	}

	@Override
	public boolean invulnerable() {
		return hp <= 0 || combat.invulnerable();
	}

	@Override
	public Result receive(Attack attack, Combatant attacker, float damage, Vector3f knock) {
		// 원작 Combat_OnContact(0x204124): 방어 자세가 아니고 방어 불가 공격이 아니면 owner 의 방어 판단(0x1CBFF8) 먼저
		boolean fromFoe = attacker != null && foe != null && attacker.owner() != null
				&& attacker.owner().position().distanceSquared(foe.position()) < 1f;
		if (fromFoe) {
			brain.observeTargetCombat(attacker);
		}
		brain.onHit();
		boolean unblockable = attacker != null && attacker.move() != null && (attacker.move().flags() & 0x2) != 0;
		if (!combat.guarding() && !unblockable && brain.tree != null) {
			brain.tryBlock(attacker != null && attacker.move() != null ? attacker.move().id() : -1, fromFoe);
		}
		Result r = combat.takeHit(attack, attacker, damage);
		if (r == Result.HIT) {
			pos.add(knock.x / 400f, 0, knock.z / 400f);
		}
		return r;
	}

	// ---- CombatOwner ----

	@Override
	public boolean onGround() {
		return true;
	}

	@Override
	public int bladeLevel() {
		return 0;
	}

	@Override
	public boolean godMode() {
		return false;
	}

	@Override
	public boolean artemisEquipped() {
		return false;
	}

	@Override
	public int selectedMagic() {
		return 0;
	}

	@Override
	public float yaw() {
		return yaw;
	}

	@Override
	public boolean applyDamage(float damage) {
		float before = hp;
		hp = Math.max(0, hp - damage);
		return circleHp < 65535 && before > circleHp && hp <= circleHp && hp > 0;
	}
}
