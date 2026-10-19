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
 * 적 1체 = 원작 데이터(스켈레톤/애니/CombatFile/AI1 템플릿) + 단순 AI.
 * <ul>
 *   <li>기술·피격 반응·사망: 적 CombatFile (미노타우로스 050–053) 그대로 — 원작과 같은 Combatant</li>
 *   <li>수치: TWK {@code /TweakTemplates/AI1/NNN/} HitPoints, Damage Multiplier, Block(%)</li>
 *   <li>의사결정(언제 어떤 공격을 요청할지)은 원작 AI 코드가 미분석이라 단순 규칙: 접근 → 사거리 안이면 공격 목록에서 무작위 요청, 피격 시 Block% 로 방어</li>
 * </ul>
 */
public final class EnemyFighter implements Hittable, CombatOwner {

	private final Combatant combat;
	private final Animator anim;
	private final int classId;
	private final float maxHp;
	private final float circleHp;
	private final float blockChance;
	private final float aiDamageMult;
	private final int[] attackMoves;
	private final int blockMove;
	private final String walkClip;
	private final String idleClip;
	private final float walkSpeed;
	private final Random rnd = new Random(7);
	public final Vector3f pos = new Vector3f();
	public final Vector3f prevPos = new Vector3f();
	public float yaw;
	private float prevYaw;
	public float hp;
	private float think = 1f;
	private Hittable foe;
	private float deadTime;

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
		String node = String.format("/TweakTemplates/AI1/%03d/", ai1);
		this.classId = ai1;
		this.maxHp = tw.i(node, "HitPoints", 100) & 0xFFFF;
		this.hp = maxHp;
		this.blockChance = tw.f(node, "Block", 0) / 100f;
		this.aiDamageMult = tw.f(node, "Damage Multiplier", 1f);
		this.circleHp = tw.i(node, "Circle Health Threshold", 65535) & 0xFFFF; // 65535 = 잡기 원 없음
		this.attackMoves = attackMoves;
		this.blockMove = blockMove;
		this.idleClip = idleClip;
		this.walkClip = walkClip;
		this.walkSpeed = walkSpeed;
		this.anim = new Animator(sk, set, tw, animNode);
		this.combat = new Combatant(new CombatData(wad, combatFiles, set), anim, this);
		anim.play(idleClip, true);
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
			anim.update(dt);
			return;
		}
		if (!combat.active() && foe != null) {
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
		if (combat.active() && combat.hitPause() <= 0) {
			anim.update(dt);
		} else if (!combat.active()) {
			anim.update(dt);
		}
	}

	public boolean dead() {
		return hp <= 0;
	}

	public float deadTime() {
		return deadTime;
	}

	public Matrix4f world(Matrix4f out, float alpha) {
		Vector3f p = new Vector3f(prevPos).lerp(pos, alpha);
		return out.translation(p).rotateY(yaw + (float) Math.PI);
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
	public boolean invulnerable() {
		return hp <= 0 || combat.invulnerable();
	}

	@Override
	public Result receive(Attack attack, Combatant attacker, float damage, Vector3f knock) {
		// AI1 Block%: 공격 중이 아니면 확률적으로 방어 기술 요청 (원작 AI 판단의 단순화)
		if (!combat.active() && blockMove >= 0 && rnd.nextFloat() < blockChance) {
			combat.request(blockMove);
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
