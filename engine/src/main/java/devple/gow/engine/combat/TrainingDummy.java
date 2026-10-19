package devple.gow.engine.combat;

import devple.gow.tools.combat.CombatFile.Attack;
import org.joml.Vector3f;

/**
 * 연습용 표적. 원작 적(AI1 템플릿 + 적 CombatFile)을 붙이기 전 피격 파이프라인 검증용.
 * HP/경직 한계 기본값 = 미노타우로스 Lv1 (10 문서 §13: HP 90).
 */
public final class TrainingDummy implements Hittable {

	public final Vector3f pos;
	public final float maxHp;
	public float hp;
	public final int poise;
	public float accum;
	public float flash;
	public float respawn;
	public boolean block;
	private final Vector3f vel = new Vector3f();

	public TrainingDummy(Vector3f pos, float maxHp, int poise) {
		this.pos = pos;
		this.maxHp = maxHp;
		this.hp = maxHp;
		this.poise = poise;
	}

	public void update(float dt) {
		flash = Math.max(0, flash - dt);
		pos.add(vel.x * dt, 0, vel.z * dt);
		vel.mul((float) Math.pow(0.02, dt));
		if (hp <= 0 && (respawn -= dt) <= 0) {
			hp = maxHp;
		}
	}

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
		return -1;
	}

	@Override
	public boolean invulnerable() {
		return hp <= 0;
	}

	@Override
	public boolean guarding() {
		return block;
	}

	@Override
	public boolean receive(float damage, Attack attack, boolean unblockable, boolean forceReact, Vector3f knock) {
		if (block && !unblockable) {
			return false;
		}
		accum += damage;
		hp = Math.max(0, hp - damage);
		boolean react = forceReact || accum > poise || hp <= 0;
		if (react) {
			accum = 0;
			// 충격 벡터 단위는 미확정 → 1/400 스케일로 시각화만
			vel.add(knock.x / 400f, 0, knock.z / 400f);
			flash = 0.2f;
		}
		if (hp <= 0) {
			respawn = 2f;
		}
		return true;
	}
}
