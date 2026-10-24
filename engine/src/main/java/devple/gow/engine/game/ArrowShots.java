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
		/** 멈춘 까닭 (기록·시험): 물체 이름, "적", "영웅", "벽", "수명". */
		public String hit;
		EffectSystem.Effect fx, trail;
		/** 적이 쏜 화살 ({@link #fireAt}): 쏜 전투원 (영웅만 맞는다), Arrow 템플릿 노드. 레벨 GO 화살이면 null. */
		public Combatant owner;
		public String arrowNode;

		Shot(float damage, float life, float gravity, float impulse, String hitGo, Set<String> skip) {
			this.damage = damage;
			this.life = life;
			this.gravity = gravity;
			this.impulse = impulse;
			this.hitGo = hitGo;
			this.skip = skip;
		}

		/** 적이 쏜 화살 피해 (Arrow "Damage" s16). */
		public float damage() {
			return damage;
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

	/** ArrowEmitter "#1bf49679" (이미터 +0x20): 대상점에 더하는 무작위 구 점의 반지름 (m) — 궁수 0.1, less_accurate 1.75, 폭발 화살 1.5 [확실: 0x2228E0]. */
	static final int EMITTER_SCATTER = 0x1bf49679;
	/** Arrow "#9fdb479c" (Arrow +0x2C): 0 이면 대상 속도로 앞질러 겨눈다 (0x222A30 → 0x222A44) [확실: 분기 / 추정: 0 이 아닌 쪽 = 퍼짐 각]. */
	static final int ARROW_NO_LEAD = 0x9fdb479c;
	/** Arrow 수명 키 (#117c872e, 초). */
	static final int ARROW_LIFE = 0x117c872e;

	/**
	 * 적이 대상에게 쏘는 화살 (원작 0x222710 대상 있음: SCR_ArcherShoot 0x18D7F8 · TARGET_FX 0x206788) [확실: 흐름·상수 / 근사: 맞음 판정·피해].
	 * 이미터 Count 발마다: 겨눌 점 = 대상점 (+ 대상 속도 × 거리 / (Velocity·16), Arrow #9fdb479c = 0 일 때) + 단위 구 무작위 점 × #1bf49679·16 →
	 * 탄도 해 {@link #launch} (Velocity m/s, Gravity m/s² — Gravity Flag 일 때) 로 속도. 날아가는 GO = "GO Name", 꼬리 = "Trail Name" (goStdArrow = 붉은 줄).
	 * 맞는 것은 영웅·벽뿐 (적끼리는 안 맞는다 [추정: 원작 충돌 마스크 미해석]).
	 *
	 * @param origin 이미터 "Aim Joint" 월드 위치 (없으면 쏜 적 몸 중심)
	 * @param target 대상점 (영웅 몸 중심)
	 */
	public List<Shot> fireAt(Tweaks tw, int emitter, Vector3f origin, Vector3f target, Vector3f targetVel, Combatant owner, EffectSystem fx,
							 java.util.Random rnd) {
		List<Shot> out = new ArrayList<>();
		String en = String.format(Locale.ROOT, "/TweakTemplates/ArrowEmitter/%03d/", emitter);
		if (tw == null || tw.s(en, "Instance Name", null) == null || origin == null || target == null) {
			return out;
		}
		int arrow = tw.i16(en, "Arrow Template", -1);
		String an = String.format(Locale.ROOT, "/TweakTemplates/Arrow/%03d/", arrow);
		if (tw.s(an, "Instance Name", null) == null) {
			return out;
		}
		float speed = tw.f(an, "Velocity", 60f); // m/s
		boolean grav = (tw.raw(an, "Gravity Flag").orElse(0) & 0xff) != 0;
		float gravity = grav ? tw.f(an, "Gravity", 9.82f) : 0f; // m/s²
		var lifeKey = tw.rawHash(an, ARROW_LIFE);
		float life = lifeKey.isPresent() ? Float.intBitsToFloat(lifeKey.getAsInt()) : 10f;
		boolean lead = tw.rawHash(an, ARROW_NO_LEAD).orElse(0) == 0;
		var sc = tw.rawHash(en, EMITTER_SCATTER);
		float scatter = (sc.isPresent() ? Float.intBitsToFloat(sc.getAsInt()) : 0f) * 16f;
		int count = Math.max(1, tw.i(en, "Count", 1));
		for (int k = 0; k < count; k++) {
			Vector3f aim = new Vector3f(target);
			if (lead && targetVel != null && speed > 0) {
				float t = origin.distance(target) / (speed * 16f); // 0x222A6C: |대상 − 시작| / (Velocity·16)
				aim.fma(t, targetVel);
			}
			if (scatter > 0 && rnd != null) { // 0x2228E0: z = 2u − 1, φ = 2πv, (√(1−z²) cos φ, √(1−z²) sin φ, z)
				float z = 2f * rnd.nextFloat() - 1f, phi = (float) (2 * Math.PI) * rnd.nextFloat(), r = (float) Math.sqrt(1 - z * z);
				aim.add(r * (float) Math.cos(phi) * scatter, r * (float) Math.sin(phi) * scatter, z * scatter);
			}
			Shot s = new Shot(tw.i16(an, "Damage", 0), life, gravity * 16f, tw.f(an, "Impulse", 0f) * 16f, null, null);
			s.owner = owner;
			s.arrowNode = an;
			s.pos.set(origin);
			s.prev.set(origin);
			s.vel.set(launch(origin, aim, speed, gravity));
			s.rot.set(lookAlong(s.vel));
			String go = tw.s(an, "GO Name", null), trail = tw.s(an, "Trail Name", null);
			if (fx != null) {
				EffectSystem.Anchor follow = o -> {
					o.set(s.rot).setTranslation(s.pos);
					return !s.dead;
				};
				s.fx = go != null ? fx.play(go, follow) : null;
				s.trail = trail != null ? fx.play(trail, follow) : null;
			}
			shots.add(s);
			out.add(s);
		}
		return out;
	}

	/**
	 * 탄도 해 (원작 0x2223C8): 미터 공간 (차이 / 16) 에서 발사각 θ 를 0 부터 π/4 걸음으로 8 번 반으로 줄이며 찾는다 — t = 수평 거리 / (v cos θ),
	 * y(t) = v sin θ · t − g t² / 2 가 높이 차보다 크면 θ 를 줄이고 아니면 키운다 (θ &gt; π/4 이고 낮으면 도착 때 내려가는 중 (|v sin θ| &lt; g t) 일 때만 줄임)
	 * [확실: 상수 0.7854 · 0.5 · 8 회]. 반환 = 단위/초 (×16).
	 */
	static Vector3f launch(Vector3f from, Vector3f to, float speed, float gravity) {
		float dx = (to.x - from.x) / 16f, dy = (to.y - from.y) / 16f, dz = (to.z - from.z) / 16f;
		float d = (float) Math.sqrt(dx * dx + dz * dz);
		if (speed <= 0) {
			return new Vector3f();
		}
		if (d < 1e-5f) {
			return new Vector3f(0, Math.signum(dy == 0 ? 1 : dy) * speed * 16f, 0);
		}
		float theta = 0, step = 0.7854f, c = 1, s = 0;
		for (int i = 0; i < 8; i++) {
			c = (float) Math.cos(theta);
			s = (float) Math.sin(theta);
			float t = d / (c * speed);
			float y = -gravity * 0.5f * t * t + s * speed * t;
			if (theta <= 0.7854f) {
				theta += dy < y ? -step : step;
			} else if (dy < y) {
				theta -= step;
			} else {
				theta += Math.abs(s * speed) < Math.abs(gravity * t) ? -step : step;
			}
			step *= 0.5f;
		}
		// 방향은 마지막으로 평가한 θ 의 cos·sin (0x222524: 반복 끝의 걸음 조정 뒤 다시 구하지 않는다)
		return new Vector3f(dx / d * c, s, dz / d * c).mul(speed * 16f);
	}

	/** +Z = 진행 방향인 회전 (화살 GO 는 +Z 로 난다). */
	static Matrix4f lookAlong(Vector3f v) {
		Vector3f z = new Vector3f(v);
		if (z.lengthSquared() < 1e-8f) {
			z.set(0, 0, 1);
		}
		z.normalize();
		Vector3f up = Math.abs(z.y) > 0.99f ? new Vector3f(1, 0, 0) : new Vector3f(0, 1, 0);
		Vector3f x = new Vector3f(up).cross(z).normalize();
		Vector3f y = new Vector3f(z).cross(x);
		return new Matrix4f().set(x.x, x.y, x.z, 0, y.x, y.y, y.z, 0, z.x, z.y, z.z, 0, 0, 0, 0, 1);
	}

	/** 적 화살이 영웅에 맞음: (화살, 맞은 점). */
	public interface HeroHit {
		void hit(Shot shot, Vector3f at);
	}

	/**
	 * 맞을 수 있는 것들 (없는 것은 null). hero/onHero = 적이 쏜 화살의 대상 (영웅 몸 구). otherBreakables / otherWorld / otherOnBreak = 다른 슬롯
	 * 레벨의 부서지는 물체 — 원작은 두 슬롯 GO 가 한 세계에 있다 (ATHN05B 발리스타 볼트 → ATHN05BB BrkbDoor, 43 §22).
	 */
	public record Targets(Breakables breakables, ScriptWorld world, Consumer<String> onBreak, List<? extends EnemyFighter> enemies,
						  Combatant attacker, LevelCollision col, LevelCollision otherCol, Platforms bodies, Platforms otherBodies,
						  EffectSystem fx, Hittable hero, HeroHit onHero, Breakables otherBreakables, ScriptWorld otherWorld,
						  Consumer<String> otherOnBreak) {

		public Targets(Breakables breakables, ScriptWorld world, Consumer<String> onBreak, List<? extends EnemyFighter> enemies,
					   Combatant attacker, LevelCollision col, LevelCollision otherCol, Platforms bodies, Platforms otherBodies,
					   EffectSystem fx) {
			this(breakables, world, onBreak, enemies, attacker, col, otherCol, bodies, otherBodies, fx, null, null);
		}

		public Targets(Breakables breakables, ScriptWorld world, Consumer<String> onBreak, List<? extends EnemyFighter> enemies,
					   Combatant attacker, LevelCollision col, LevelCollision otherCol, Platforms bodies, Platforms otherBodies,
					   EffectSystem fx, Hittable hero, HeroHit onHero) {
			this(breakables, world, onBreak, enemies, attacker, col, otherCol, bodies, otherBodies, fx, hero, onHero, null, null, null);
		}
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
		boolean bestOther = false;
		EnemyFighter bestEnemy = null;
		String why = null;
		if (s.owner != null) { // 적이 쏜 화살: 영웅 몸 구 · 벽만
			s.rot.set(lookAlong(s.vel)); // 탄도를 따라 기울어진다
			heroStep(s, t, dir, len);
			return;
		}
		for (Breakables br : new Breakables[]{t.breakables(), t.otherBreakables()}) {
			if (br == null) {
				continue;
			}
			for (Breakables.Item it : br.items()) {
				if (it.broken()) {
					continue;
				}
				float u = segSphere(s.prev, dir, len, it.center, it.radius);
				if (u < bestT) {
					bestT = u;
					bestBreak = it;
					bestOther = br == t.otherBreakables();
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
			boolean broke = bestOther ? t.otherBreakables().damage(bestBreak, s.damage, t.otherWorld(), t.otherOnBreak())
					: t.breakables().damage(bestBreak, s.damage, t.world(), t.onBreak());
			if (broke) {
				System.out.println("화살이 부숨: " + bestBreak.owner + (bestOther ? " (다른 슬롯)" : ""));
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

	/** 영웅 몸 구 폴백 (피격 볼륨을 모를 때): 중심 = 발 + 몸 높이 ½ (2.2 m × 0.5 × 16, 궁수가 겨누는 점과 같음), 반지름 1 m [근사]. */
	static final float HERO_FALLBACK_CENTRE = 17.6f, HERO_FALLBACK_RADIUS = 16f;

	/** 적 화살 한 틱: 이전→현재 선분이 정적 충돌 (두 슬롯) · 움직이는 몸 보다 먼저 영웅 몸 구에 닿으면 맞음 [근사: 원작 충돌 마스크]. */
	private void heroStep(Shot s, Targets t, Vector3f dir, float len) {
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
			NavWorld.Contact c = ps.segment(s.prev, s.pos, 0);
			if (c != null && c.t() * len < wallT) {
				wallT = c.t() * len;
			}
		}
		float heroT = Float.MAX_VALUE;
		Hittable hero = t.hero();
		if (hero != null && !hero.invulnerable()) {
			var hv = hero.hitVolumes();
			List<float[]> spheres = hv != null ? hv.ownSpheres() : null; // 몸 구 (부착 무기 GO 의 구 제외)
			if (spheres == null || spheres.isEmpty()) {
				Vector3f p = hero.position();
				spheres = List.of(new float[]{p.x, p.y + HERO_FALLBACK_CENTRE, p.z, HERO_FALLBACK_RADIUS});
			}
			for (float[] sp : spheres) {
				heroT = Math.min(heroT, segSphere(s.prev, dir, len, new Vector3f(sp[0], sp[1], sp[2]), sp[3]));
			}
		}
		if (heroT < wallT && heroT != Float.MAX_VALUE) {
			Vector3f at = new Vector3f(s.prev).fma(heroT, dir);
			if (t.onHero() != null) {
				t.onHero().hit(s, at);
			}
			stop(s, "영웅", at, t);
		} else if (wallT != Float.MAX_VALUE) {
			stop(s, "벽", new Vector3f(s.prev).fma(wallT, dir), t);
		}
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
		if (s.trail != null) {
			s.trail.stop();
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
