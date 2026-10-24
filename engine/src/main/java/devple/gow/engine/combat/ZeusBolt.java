package devple.gow.engine.combat;

import devple.gow.engine.game.Tweaks;
import devple.gow.engine.input.Input;
import devple.gow.engine.input.PadButton;
import devple.gow.tools.combat.CombatFile.Attack;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * 제우스의 분노 (Thunderbolt, 마법 5). 원작 SCR_*Thunderbolt 0x19FF60–0x1A0FF0 + Arrow/ArrowEmitter 0x222710–0x2249A0 (35 문서 §3).
 * <ul>
 *   <li>던지기 = 영웅 기술의 SCRIPT 이벤트 {@code SCR_ThrowThunderbolt}(BOLTTHROW 0.55, BOLTTHROWL 0.33, AIRBOLTTHROW 0.30) — 기술 인스턴스당 1발</li>
 *   <li>차지: <b>△</b> 를 누르고 있는 시간 / Charge Time ≥ Ch1·Ch2 Threshold → Ch1/Ch2 이미터 (단계 1·2만, Allow Charging).
 *       [확실] SCR_ChargeThunderbolt 0x1A07C8 / SCR_TriggerThunderbolt 0x1A0F90 이 패드 현재 워드 (+0x28) &amp; 0x10 을 본다 — Pad_Read 0x17C384
 *       비트 4 = △ (10 문서 §16). 메시지 741 "제우스의 힘 [L2] 홀드 + [△] 홀드". □ (0x80, 새로 누름) 는 던지기 요청 (0x1A0F20, 큐 최대 2) 이다.
 *       예전 엔진은 □ 로 차지했다.</li>
 *   <li>MP: 던질 때 Stage Magic Drain (차지 샷은 Ch1/Ch2 Magic Drain 으로 <b>대체</b>, 0x1DCA58)</li>
 *   <li>투사체: Velocity(m/s) × 16 유닛, 수명 #117c872e(5초), 중력 없음(직선), 표적 중심 조준, 이전→현재 위치 선분 스윕 판정</li>
 *   <li>명중: Arrow Damage + Ground Impulse Away; 차지 샷은 Concussion(반경 m × 16) 범위 충격</li>
 * </ul>
 */
public final class ZeusBolt {

	/** SCR_ThrowThunderbolt 의 Hash127Upper. */
	public static final int SCR_THROW = 0x96ede590;
	/** 표적 중심 높이 / 선분 판정 반경 (유닛) [근사: 원작은 충돌 마스크 0x631FF0 광선]. */
	static final float CENTER_Y = 40f, HIT_RADIUS = 28f;
	/** 손 위치 근사 (rWeapIH 관절 대신, 유닛). */
	static final float HAND_Y = 55f, HAND_FWD = 20f;

	/** 날아가는 번개 1발. */
	public static final class Bolt {
		public final Vector3f pos = new Vector3f(), prev = new Vector3f(), vel = new Vector3f();
		public final int level;
		final float damage, life, impulse, concRadius;
		/** Arrow 템플릿 노드 (명중 플래시 필드). */
		String arrowNode;
		public float age;
		public boolean dead;
		public Hittable hit;

		Bolt(int level, float damage, float life, float impulse, float concRadius) {
			this.level = level;
			this.damage = damage;
			this.life = life;
			this.impulse = impulse;
			this.concRadius = concRadius;
		}
	}

	/** 차지 버튼 = 패드 비트 0x10 = △ (0x1A07CC, 0x1A0F94) [확실]. */
	public static final PadButton CHARGE_BUTTON = PadButton.TRIANGLE;

	private final Tweaks tw;
	private final List<Bolt> bolts = new ArrayList<>();
	private float charge;
	private int firedSerial = -1;
	/** 마지막 발사의 차지 단계 (0 일반, 1 Ch1, 2 Ch2) / 비용. 테스트·HUD 용. */
	public int lastLevel = -1;
	public float lastDrain;

	public ZeusBolt(Tweaks tw) {
		this.tw = tw;
	}

	public List<Bolt> bolts() {
		return bolts;
	}

	/**
	 * @param stage 0..2
	 * @return 이번 틱에 소모한 MP
	 */
	public float update(Combatant c, Input in, List<? extends Hittable> targets, int stage, float mp, float dt,
						Magic.Sink sink) {
		float used = 0;
		String clip = c.clipName();
		boolean boltMove = clip != null && clip.toLowerCase().contains("bolt");
		String node = "/Player/Thunderbolt/";
		String st = "Stage " + stage + " ";
		if (!boltMove) {
			charge = 0;
		} else if (in != null && in.held(CHARGE_BUTTON) && allowCharge(node, st)) {
			charge += dt;
		}
		if (boltMove && c.activeScript(SCR_THROW) != null && c.moveSerial() != firedSerial) {
			firedSerial = c.moveSerial();
			float ct = Math.max(1e-3f, tw.f(node, st + "Charge Time", 0.5f));
			float frac = Math.min(1f, charge / ct);
			int level = 0;
			if (allowCharge(node, st)) {
				float t2 = tw.f(node, st + "Ch2 Threshold", 0), t1 = tw.f(node, st + "Ch1 Threshold", 0);
				if (t2 > 0 && frac >= t2) {
					level = 2;
				} else if (t1 > 0 && frac >= t1) {
					level = 1;
				}
			}
			String pre = level == 0 ? "" : "Ch" + level + " ";
			int emitter = tw.i16(node, st + pre + "Arrow Emitter", 2);
			float drain = tw.f(node, st + pre + "Magic Drain", 5f);
			charge = 0;
			if (mp >= drain || level == 0) {
				used = Math.min(mp, drain);
				lastLevel = level;
				lastDrain = drain;
				fire(c, emitter, level, nearest(c, targets), sink);
			}
		}
		step(c, targets, dt, sink);
		return used;
	}

	/** bool 필드 (하위 바이트만 유효, 덤프의 0.50000006 = 참). */
	private boolean allowCharge(String node, String st) {
		return (tw.i(node, st + "Allow Charging", 0) & 0xFF) != 0;
	}

	private static Hittable nearest(Combatant c, List<? extends Hittable> targets) {
		Hittable t = c.target();
		if (t != null && !t.invulnerable() && t.hp() > 0) {
			return t;
		}
		Hittable best = null;
		float bd = Float.MAX_VALUE;
		for (Hittable h : targets) {
			if (h.hp() <= 0) {
				continue;
			}
			float d = h.position().distanceSquared(c.owner().position());
			if (d < bd) {
				bd = d;
				best = h;
			}
		}
		return best;
	}

	/** ArrowEmitter 발사 (0x222710): 표적이 있으면 중심 조준, 없으면 정면. */
	void fire(Combatant c, int emitter, int level, Hittable target, Magic.Sink sink) {
		String en = String.format("/TweakTemplates/ArrowEmitter/%03d/", emitter);
		int arrow = tw.i16(en, "Arrow Template", 2);
		String an = String.format("/TweakTemplates/Arrow/%03d/", arrow);
		float v = tw.f(an, "Velocity", 60f) * Magic.UNIT;
		var life = tw.rawHash(an, 0x117c872e);
		float lifetime = life.isPresent() ? Float.intBitsToFloat(life.getAsInt()) : 5f;
		int conc = tw.i16(an, "Concussion", 0);
		float concR = 0;
		if (conc > 0) {
			concR = tw.f(String.format("/TweakTemplates/Concussion/%03d/", conc), "Start Radius", 2f) * Magic.UNIT;
		}
		Bolt b = new Bolt(level, tw.i16(an, "Damage", 8), lifetime, tw.f(an, "Ground Impulse Away", 300f), concR);
		b.arrowNode = an;
		Vector3f p = c.owner().position();
		float yaw = c.owner().yaw();
		Vector3f fwd = new Vector3f((float) -Math.sin(yaw), 0, (float) -Math.cos(yaw));
		b.pos.set(p).add(0, HAND_Y, 0).fma(HAND_FWD, fwd);
		b.prev.set(b.pos);
		if (target != null) {
			b.vel.set(target.position()).add(0, CENTER_Y, 0).sub(b.pos);
			if (b.vel.lengthSquared() < 1e-4f) {
				b.vel.set(fwd);
			}
		} else {
			b.vel.set(fwd);
		}
		b.vel.normalize().mul(v);
		bolts.add(b);
		sink.sound("SND_BOLT_FIRE", b.pos);
	}

	private void step(Combatant c, List<? extends Hittable> targets, float dt, Magic.Sink sink) {
		for (Bolt b : bolts) {
			if (b.dead) {
				continue;
			}
			b.age += dt;
			if (b.age > b.life) {
				b.dead = true;
				continue;
			}
			b.prev.set(b.pos);
			b.pos.fma(dt, b.vel);
			for (Hittable h : targets) {
				if (h.invulnerable() || h.hp() <= 0 && !h.petrified()) {
					continue;
				}
				Vector3f ctr = new Vector3f(h.position()).add(0, CENTER_Y, 0);
				if (segDist(b.prev, b.pos, ctr) <= HIT_RADIUS) {
					impact(c, b, h, targets, sink);
					break;
				}
			}
		}
		bolts.removeIf(b -> b.dead);
	}

	/**
	 * 명중 화면 플래시 (0x2249A0 → 0x224108: 0x195BA8(+0x164 Fog Template, +0x15C Bright, +0x160 Dark, +0x168 Flash Freq,
	 * f12 = +0x16C #8ae891cf 지속)). R_PERMK: thunderbolt 0/0/−1/1/0, ch1 9 "dimWhiteAdd"/0/−1/6/0.4, ch2 8/0/−1/20/0 (45 문서 §1.4).
	 */
	static final int ARROW_FLASH_DURATION = 0x8ae891cf;

	void flash(String an, Magic.Sink sink) {
		if (an == null) {
			return;
		}
		var d = tw.rawHash(an, ARROW_FLASH_DURATION);
		sink.screenFlash(tw.i16(an, "Fog Template", -1), tw.i16(an, "Bright Camera Filter", -1), tw.i16(an, "Dark Camera Filter", -1),
				tw.i16(an, "Flash Freq", 1), d.isPresent() ? Float.intBitsToFloat(d.getAsInt()) : 0f);
	}

	private void impact(Combatant c, Bolt b, Hittable h, List<? extends Hittable> targets, Magic.Sink sink) {
		b.dead = true;
		b.hit = h;
		Vector3f dir = new Vector3f(b.vel.x, 0, b.vel.z);
		if (dir.lengthSquared() > 1e-6f) {
			dir.normalize();
		}
		h.receive(HIT, c, b.damage, new Vector3f(dir).mul(b.impulse));
		sink.hit(h, b.damage);
		sink.effect("goBoltHit", b.pos);
		flash(b.arrowNode, sink);
		if (b.concRadius > 0) {
			// Concussion 템플릿: 충격점 반경 안의 다른 대상에 충격 (피해값은 덤프 손상으로 미확인 → 0)
			for (Hittable o : targets) {
				if (o != h && !o.invulnerable() && o.hp() > 0 && o.position().distance(b.pos.x, o.position().y, b.pos.z) <= b.concRadius) {
					Vector3f away = new Vector3f(o.position()).sub(b.pos).setComponent(1, 0);
					if (away.lengthSquared() > 1e-6f) {
						away.normalize();
					}
					o.receive(HIT, c, 0, away.mul(1000f));
				}
			}
		}
	}

	/** 점 q 와 선분 ab 의 거리. */
	static float segDist(Vector3f a, Vector3f b, Vector3f q) {
		Vector3f ab = new Vector3f(b).sub(a);
		float l2 = ab.lengthSquared();
		float t = l2 < 1e-8f ? 0 : Math.max(0, Math.min(1, new Vector3f(q).sub(a).dot(ab) / l2));
		return new Vector3f(a).fma(t, ab).distance(q);
	}

	/** 번개 피격 판정 (반응 0, Context 16). */
	static final Attack HIT = new Attack(0, 0, new float[9], 0, 1, -1, 0, 0, 0, 0, 0, 0);
}
