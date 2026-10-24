package devple.gow.engine.game;

import devple.gow.engine.combat.Combatant;
import devple.gow.engine.combat.EnemyFighter;
import devple.gow.engine.combat.Hittable;
import devple.gow.engine.fx.EffectSystem;
import devple.gow.engine.level.LevelCollision;
import devple.gow.engine.nav.NavWorld;
import devple.gow.engine.nav.Platforms;
import devple.gow.engine.script.ScriptWorld;
import devple.gow.tools.combat.CombatFile.Attack;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * GO 의 화살 이미터 (Creator 비헤이비어, 템플릿 = ArrowEmitter 번호) 가 스크립트 이벤트 1020 을 받아 쏘는 발사체 (43 문서 §14) [확실: 흐름 / 근사: 맞음 판정].
 * <pre>
 * 원작 메시지 처리 0x267698 (vtable 0x2C4B94): 이벤트 0x3FC (1020) → 0x231590 (비헤이비어 정의 +4 = ArrowEmitter 번호, BHV +0x20) →
 *   ArrowEmitter 표 0x2A2660 → 0x222710(이미터, …, GO, 대상 없음, 관절 −1): "Aim Joint" 관절 (해시로 찾음, 0x138360) 월드 행렬,
 *   Count 발 (Spread Angle 0 이면 그 관절 +Z), Arrow 템플릿 (0x2A2664): Velocity × 16 유닛/초, Gravity (+0x34 비트 1 일 때만, 0x22278C),
 *   수명 #117c872e, Damage (s16, +0x3A), GO Name (날아가는 GO), Hit Effect (맞은 자리 GO).
 * 데이터: ATHN02C gobiggun1crank = PushPull 202 "AthnGun" + Creator 200 → ArrowEmitter/200 "athensgun" → Arrow/099 "AthensGun"
 *   (Velocity 60 → 960/초, Damage 10000, goBigarrow, goBrokenArrowB, Aim Joint "arrowjoint"); 스크립트 BigGun1Crank-EvtArrow 1020(200).
 *   맞는 쪽: Brkb 950 "Breakable door" (breakableDoor01/02) 와 962 "Brkb Wall" (BreakSecret) 의 Hit Points 10000 = 한 발.
 * </pre>
 * 판정 [근사: 원작은 충돌 마스크 광선]: 틱마다 이전→현재 선분이 (1) 부서지는 물체 구 (Breakables 중심·반지름), (2) 적 몸 구 (피격 볼륨),
 * (3) 정적 충돌 (두 슬롯), (4) 움직이는 몸 CDV (문 등, 쏜 GO 와 그 부모·형제 몸은 뺀다) 중 가장 먼저 닿는 것에서 멈춘다.
 */
public final class ArrowShots {

	/** 날아가는 화살 하나. */
	public static final class Shot {
		public final Vector3f pos = new Vector3f(), prev = new Vector3f(), vel = new Vector3f();
		final Matrix4f rot = new Matrix4f();
		final float damage, life, gravity, impulse;
		final String hitGo;
		final Set<String> skip;
		public float age;
		public boolean dead;
		/** 멈춘 까닭 (기록·시험): 물체 이름, "적", "벽", "수명". */
		public String hit;
		EffectSystem.Effect fx;

		Shot(float damage, float life, float gravity, float impulse, String hitGo, Set<String> skip) {
			this.damage = damage;
			this.life = life;
			this.gravity = gravity;
			this.impulse = impulse;
			this.hitGo = hitGo;
			this.skip = skip;
		}
	}

	private final List<Shot> shots = new ArrayList<>();
	/** 마지막으로 멈춘 화살 (시험·기록). */
	public Shot last;

	public List<Shot> shots() {
		return shots;
	}

	/**
	 * 발사 (0x222710). aim = 이미터 "Aim Joint" 월드 행렬 (행벡터, +Z = 행 2). skip = 쏜 GO 와 부모·형제 (자기 CDV 에 걸리지 않게).
	 *
	 * @return 쏜 화살 (템플릿이 없으면 null)
	 */
	public Shot fire(Tweaks tw, int emitter, float[] aim, Set<String> skip, EffectSystem fx) {
		String en = String.format(Locale.ROOT, "/TweakTemplates/ArrowEmitter/%03d/", emitter);
		if (tw == null || tw.s(en, "Instance Name", null) == null || aim == null) {
			return null;
		}
		int arrow = tw.i16(en, "Arrow Template", -1);
		String an = String.format(Locale.ROOT, "/TweakTemplates/Arrow/%03d/", arrow);
		float speed = tw.f(an, "Velocity", 60f) * 16f;
		var lifeKey = tw.rawHash(an, 0x117c872e);
		float life = lifeKey.isPresent() ? Float.intBitsToFloat(lifeKey.getAsInt()) : 10f;
		// Gravity 는 +0x34 비트 1 ("Gravity Flag", 값의 하위 바이트 — 다른 비트 키와 같은 꼴) 일 때만 (0x22278C) [추정: 하위 바이트]
		boolean grav = (tw.raw(an, "Gravity Flag").orElse(0) & 0xff) != 0;
		float gravity = grav ? tw.f(an, "Gravity", 9.82f) * 16f : 0f;
		Shot s = new Shot(tw.i16(an, "Damage", 0), life, gravity, tw.f(an, "Impulse", 0f) * 16f, tw.s(an, "Hit Effect", null), skip);
		s.pos.set(aim[12], aim[13], aim[14]);
		s.prev.set(s.pos);
		s.vel.set(aim[8], aim[9], aim[10]);
		if (s.vel.lengthSquared() < 1e-8f) {
			s.vel.set(0, 0, 1);
		}
		s.vel.normalize().mul(speed);
		s.rot.set(aim).setTranslation(0, 0, 0);
		s.rot.normalize3x3();
		String go = tw.s(an, "GO Name", null);
		if (fx != null && go != null) {
			s.fx = fx.play(go, out -> {
				out.set(s.rot).setTranslation(s.pos);
				return !s.dead;
			});
		}
		shots.add(s);
		System.out.printf(Locale.ROOT, "화살 발사: 이미터 %d → Arrow %d (%.0f/초, 피해 %.0f) at (%.0f, %.0f, %.0f) 방향 (%.2f, %.2f, %.2f)%n", emitter, arrow,
				speed, s.damage, s.pos.x, s.pos.y, s.pos.z, s.vel.x / speed, s.vel.y / speed, s.vel.z / speed);
		return s;
	}

	/** 맞을 수 있는 것들 (없는 것은 null). */
	public record Targets(Breakables breakables, ScriptWorld world, Consumer<String> onBreak, List<? extends EnemyFighter> enemies,
						  Combatant attacker, LevelCollision col, LevelCollision otherCol, Platforms bodies, Platforms otherBodies,
						  EffectSystem fx) {
	}

	/** 피격 기록 (반응 0). */
	static final Attack HIT = new Attack(0, 0, new float[9], 0, 1, -1, 0, 0, 0, 0, 0, 0);

	public void update(float dt, Targets t) {
		for (Shot s : shots) {
			if (s.dead) {
				continue;
			}
			s.age += dt;
			s.prev.set(s.pos);
			s.vel.y -= s.gravity * dt;
			s.pos.fma(dt, s.vel);
			step(s, t);
			if (!s.dead && s.age >= s.life) {
				stop(s, "수명", null, t);
			}
		}
		shots.removeIf(s -> s.dead);
	}

	private void step(Shot s, Targets t) {
		Vector3f d = new Vector3f(s.pos).sub(s.prev);
		float len = d.length();
		if (len < 1e-5f) {
			return;
		}
		Vector3f dir = new Vector3f(d).div(len);
		float bestT = Float.MAX_VALUE;
		Breakables.Item bestBreak = null;
		EnemyFighter bestEnemy = null;
		String why = null;
		if (t.breakables() != null) {
			for (Breakables.Item it : t.breakables().items()) {
				if (it.broken()) {
					continue;
				}
				float u = segSphere(s.prev, dir, len, it.center, it.radius);
				if (u < bestT) {
					bestT = u;
					bestBreak = it;
					why = it.owner;
				}
			}
		}
		if (t.enemies() != null) {
			for (EnemyFighter f : t.enemies()) {
				if (f.dead() || f.invulnerable()) {
					continue;
				}
				var hv = f.hitVolumes();
				List<float[]> spheres = hv != null ? hv.spheres(-1) : List.of();
				if (spheres.isEmpty()) {
					spheres = List.of(new float[]{f.pos.x, f.pos.y + 40, f.pos.z, 28});
				}
				for (float[] sp : spheres) {
					float u = segSphere(s.prev, dir, len, new Vector3f(sp[0], sp[1], sp[2]), sp[3]);
					if (u < bestT) {
						bestT = u;
						bestEnemy = f;
						bestBreak = null;
						why = "적";
					}
				}
			}
		}
		float wallT = Float.MAX_VALUE;
		for (LevelCollision c : new LevelCollision[]{t.col(), t.otherCol()}) {
			if (c == null) {
				continue;
			}
			LevelCollision.Hit h = c.raycast(s.prev, dir, len);
			if (h != null && h.distance() < wallT) {
				wallT = h.distance();
			}
		}
		for (Platforms ps : new Platforms[]{t.bodies(), t.otherBodies()}) {
			if (ps == null) {
				continue;
			}
			NavWorld.Contact c = segmentSkipping(ps, s.prev, s.pos, s.skip);
			if (c != null && c.t() * len < wallT) {
				wallT = c.t() * len;
			}
		}
		if (wallT < bestT) {
			stop(s, "벽", new Vector3f(s.prev).fma(wallT, dir), t);
			return;
		}
		if (bestT == Float.MAX_VALUE) {
			return;
		}
		Vector3f at = new Vector3f(s.prev).fma(bestT, dir);
		if (bestBreak != null) {
			if (t.breakables().damage(bestBreak, s.damage, t.world(), t.onBreak())) {
				System.out.println("화살이 부숨: " + bestBreak.owner);
			}
		} else if (bestEnemy != null) {
			Vector3f knock = new Vector3f(dir.x, 0, dir.z);
			if (knock.lengthSquared() > 1e-6f) {
				knock.normalize().mul(s.impulse);
			}
			bestEnemy.receive(HIT, t.attacker(), s.damage, knock);
		}
		stop(s, why, at, t);
	}

	/** 쏜 GO 의 몸은 뺀 CDV 선분 접촉 (가장 가까운 것). */
	private static NavWorld.Contact segmentSkipping(Platforms ps, Vector3f from, Vector3f to, Set<String> skip) {
		if (skip == null || skip.isEmpty()) {
			return ps.segment(from, to, 0);
		}
		List<String> off = new ArrayList<>();
		for (Platforms.Body b : ps.bodies()) {
			if (skip.contains(b.owner) && b.solid) {
				off.add(b.owner);
				b.solid = false;
			}
		}
		try {
			return ps.segment(from, to, 0);
		} finally {
			for (String o : off) {
				ps.body(o).solid = true;
			}
		}
	}

	private void stop(Shot s, String why, Vector3f at, Targets t) {
		s.dead = true;
		s.hit = why;
		if (at != null) {
			s.pos.set(at);
		}
		if (s.fx != null) {
			s.fx.stop();
		}
		if (at != null && s.hitGo != null && t.fx() != null) {
			t.fx().play(s.hitGo, EffectSystem.Anchor.of(new Matrix4f(s.rot).setTranslation(at)));
		}
		last = s;
		System.out.printf(Locale.ROOT, "화살 멈춤: %s at (%.0f, %.0f, %.0f) %.2f 초%n", why, s.pos.x, s.pos.y, s.pos.z, s.age);
	}

	/** 선분 (o + u·dir, 0 ≤ u ≤ len) 이 구에 처음 들어가는 u (없으면 MAX_VALUE). 시작점이 이미 안이면 0. */
	static float segSphere(Vector3f o, Vector3f dir, float len, Vector3f c, float r) {
		float mx = o.x - c.x, my = o.y - c.y, mz = o.z - c.z;
		float b = mx * dir.x + my * dir.y + mz * dir.z;
		float cc = mx * mx + my * my + mz * mz - r * r;
		if (cc <= 0) {
			return 0;
		}
		if (b > 0) {
			return Float.MAX_VALUE;
		}
		float disc = b * b - cc;
		if (disc < 0) {
			return Float.MAX_VALUE;
		}
		float u = -b - (float) Math.sqrt(disc);
		return u >= 0 && u <= len ? u : Float.MAX_VALUE;
	}
}
