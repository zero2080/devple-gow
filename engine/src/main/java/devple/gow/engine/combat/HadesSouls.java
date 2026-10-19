package devple.gow.engine.combat;

import devple.gow.engine.game.Tweaks;
import devple.gow.tools.combat.CombatFile.Attack;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * 하데스의 군대 (RaiseTheDead, 마법 6). 원작 SCR_RaiseTheDead 0x19DE90 → 매 프레임 0x19DAC0 (영혼 슬롯 6개 0x2FF9D0, 35 문서 §4).
 * <ul>
 *   <li>발동 = 영웅 기술 SCRIPT 이벤트 {@code SCR_RaiseTheDead}(RAISETHEDEAD 0.58 / AIRRAISETHEDEAD 0.72). 기존 영혼은 제거</li>
 *   <li>영혼 N = Num Dead Guys(3/4/6), 영혼마다 Magic Drain 소모 (합계 75 = 시전 조건 0x1D9510)</li>
 *   <li>초기 속도 = 반경 방향 (cos 2πi/N, 0, sin 2πi/N) × Max Velocity</li>
 *   <li>표적: 영웅에서 Max Reach × 16 안의 적을 차례로 배정(적보다 영혼이 많으면 순환). 표적이 없거나 죽으면 크레토스 주위 궤도점</li>
 *   <li>조향: vel += dir(목표) × MaxVel × Homing × dt → |vel| = MaxVel, 위치 += vel × 16 × dt</li>
 *   <li>명중: 거리 &lt; 2m(32) 이고 마지막 명중 후 0.5초 경과 → Damage Per Hit, 누적 ≥ Max Damage 면 소멸. 전체 Max Time(15초) 후 소멸</li>
 * </ul>
 */
public final class HadesSouls {

	/** SCR_RaiseTheDead 의 Hash127Upper. */
	public static final int SCR_RAISE = 0x139d9006;
	static final float HIT_DIST = 2f * Magic.UNIT, COOLDOWN = 0.5f, CENTER_Y = 40f;

	/** 영혼 1개. */
	public static final class Soul {
		public final Vector3f pos = new Vector3f(), vel = new Vector3f();
		public Hittable target;
		public float dealt, cooldown = 100f;
		public boolean dead;
	}

	private final Tweaks tw;
	private final List<Soul> souls = new ArrayList<>();
	private int firedSerial = -1;
	private float timer, maxTime, maxDamage, perHit, maxVel, reach, homing;
	private float ta, tb, tc;
	private int next;
	/** 누적 명중 수 (테스트용). */
	public int hits;

	public HadesSouls(Tweaks tw) {
		this.tw = tw;
	}

	public List<Soul> souls() {
		return souls;
	}

	public boolean active() {
		return !souls.isEmpty();
	}

	/** @return 이번 틱 MP 소모 */
	public float update(Combatant c, List<? extends Hittable> targets, int stage, float dt, Magic.Sink sink) {
		float used = 0;
		if (c.activeScript(SCR_RAISE) != null && c.moveSerial() != firedSerial) {
			firedSerial = c.moveSerial();
			used = spawn(c.owner().position(), stage, sink);
		}
		if (!souls.isEmpty()) {
			step(c, targets, dt, sink);
		}
		return used;
	}

	/** 영혼 생성. @return 소모 MP (영혼당 Stage Magic Drain) */
	public float spawn(Vector3f at, int stage, Magic.Sink sink) {
		String n = "/Player/RaiseTheDead/";
		String st = "Stage " + stage + " ";
		int num = Math.max(1, Math.min(6, tw.i16(n, st + "Num Dead Guys", 3)));
		float drain = tw.f(n, st + "Magic Drain", 25f);
		maxTime = tw.f(n, st + "Max Time", 15f);
		maxDamage = tw.f(n, st + "Max Damage", 125f);
		perHit = tw.f(n, st + "Damage Per Hit", 10f);
		maxVel = tw.f(n, st + "Max Velocity", 12.5f);
		reach = tw.f(n, st + "Max Reach", 25f) * Magic.UNIT;
		homing = tw.f(n, st + "Homing Amount", 10f);
		souls.clear();
		timer = 0;
		next = 0;
		for (int i = 0; i < num; i++) {
			Soul s = new Soul();
			double ph = Math.PI * 2 * i / num;
			s.pos.set(at).add(0, CENTER_Y, 0);
			s.vel.set((float) Math.cos(ph), 0, (float) Math.sin(ph)).mul(maxVel);
			souls.add(s);
		}
		sink.sound("SND_RAISEDEAD_SHOT", at);
		return drain * num;
	}

	private void step(Combatant c, List<? extends Hittable> targets, float dt, Magic.Sink sink) {
		timer += dt;
		ta += (float) (Math.PI / 2) * dt;
		tb += 4.19f * dt;
		tc += 2.97f * dt;
		Vector3f hero = c.owner().position();
		List<Hittable> inReach = new ArrayList<>();
		for (Hittable h : targets) {
			if (h.hp() > 0 && !h.invulnerable() && h.position().distance(hero) <= reach) {
				inReach.add(h);
			}
		}
		for (Soul s : souls) {
			if (s.target != null && (s.target.hp() <= 0 || s.target.invulnerable())) {
				s.target = null;
			}
			if (s.target == null && !inReach.isEmpty()) {
				s.target = inReach.get(next++ % inReach.size());
			}
			Vector3f goal = s.target != null ? new Vector3f(s.target.position()).add(0, CENTER_Y, 0)
					: new Vector3f(hero).add(new Vector3f((float) (3 * Math.sin(ta)), (float) (2 + Math.sin(tb)),
					(float) (3 * Math.sin(tc))).mul(Magic.UNIT));
			Vector3f d = new Vector3f(goal).sub(s.pos);
			if (d.lengthSquared() > 1e-6f) {
				s.vel.fma(maxVel * homing * dt, d.normalize());
			}
			if (s.vel.lengthSquared() > 1e-8f) {
				s.vel.normalize().mul(maxVel);
			}
			s.pos.fma(Magic.UNIT * dt, s.vel);
			s.cooldown += dt;
			if (s.target != null && s.cooldown > COOLDOWN
					&& s.pos.distance(new Vector3f(s.target.position()).add(0, CENTER_Y, 0)) < HIT_DIST) {
				Vector3f away = new Vector3f(s.vel.x, 0, s.vel.z);
				if (away.lengthSquared() > 1e-6f) {
					away.normalize().mul(200f);
				}
				s.target.receive(HIT, c, perHit, away);
				sink.hit(s.target, perHit);
				sink.effect("goDeadHit", s.pos);
				hits++;
				s.cooldown = 0;
				s.dealt += perHit;
				if (s.dealt >= maxDamage) {
					s.dead = true;
					sink.effect("goDeadDie", s.pos);
				}
			}
		}
		souls.removeIf(s -> s.dead);
		if (timer > maxTime) {
			souls.forEach(s -> sink.effect("goDeadDie", s.pos));
			souls.clear();
		}
	}

	/** 영혼 피격 판정 (반응 0, Hit Context 19). */
	static final Attack HIT = new Attack(0, 0, new float[9], 0, 1, -1, 0, 0, 0, 0, 0, 0);
}
