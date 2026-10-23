package devple.gow.engine.game;

import devple.gow.engine.fx.EffectSystem;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * 떨어진 오브 (원작 Orb_Spawn 0x226518 / Orb_Update 0x226710, 11 문서 §3). OrbEmitter 가 정한 개수만큼 원형으로 튀어나가
 * 착지점에 떠 있다가, 영웅이 Attract Radius (×16) 안에 오면 빨려 들어가 <b>도착하는 순간</b> 합산된다 — 붉은 바가 하나씩 찬다.
 * 모양은 Orb 템플릿의 GO (goWeaponOrb 등, FXC/PTC 파티클 덩어리) 를 이펙트로 재생해 표시 위치를 따라가게 한다.
 * <ul>
 *   <li>상태 1 비행 (Spawn Time × (rand + 0.5) 안에 착지점에 닿는 포물선, 매 프레임 남은 시간으로 다시 계산) → 2 떠 있음</li>
 *   <li>2 → 4 흡수: 영웅과의 거리 &lt; Attract Radius × 16, 시간 = Attract Time, 목표 = 영웅 + 16 (매 프레임), Pickup Sound 재생</li>
 *   <li>4 끝 → 합산 (Orb_Value 0x226460 = Points × 코스튬 배율, {@link PlayerState#pickupOrb}) 후 제거</li>
 *   <li>흔들림 (모든 상태): 위상 += dt·Freq·2π, 표시 높이 = 기준 + (Y Offset + sin(위상)·진폭) × 16</li>
 *   <li>수명 (×(rand + 0.5)) 이 지나면 사라짐, 마지막 1 초 투명도 = 남은 수명. 영웅이 죽으면 전부 사라짐. 동시에 {@link #MAX} 개 (0x2A2678 &lt; 0x1E)</li>
 * </ul>
 * [근사: 시작점의 Emitter Radius 오프셋 축, 슬롯 순서 (원작은 남은 개수 가중 난수 0x2257C0)]
 */
public final class OrbPickups {

	public static final int MAX = 30;
	private static final int H_FREQ = 0xb6aab6b0, H_AMP = 0xb60d39a0, H_YOFF = 0xf0864672, H_LIFE = 0x117c872e,
			H_ATTRACT_TIME = 0xd0053b6c, H_ATTRACT_RADIUS = 0x3a05c371, H_SOUND = 0x40b798cb;

	/** Orb 템플릿 (등록 0x2261C8). */
	record Template(int type, float points, String go, String sound, float freq, float amp, float yOffset, float life,
					float spawnTime, float attractRadius, float attractTime, float gravity) {

		static Template of(Tweaks tw, int id) {
			String n = String.format("/TweakTemplates/Orb/%03d/", id);
			return new Template(tw.i16(n, "Type", 2), tw.f(n, "Points", 0), tw.s(n, "GO Name", null), tw.sHash(n, H_SOUND, null),
					f(tw, n, H_FREQ, 0.5f), f(tw, n, H_AMP, 0.15f), f(tw, n, H_YOFF, 0.4f), f(tw, n, H_LIFE, 10f),
					tw.f(n, "Spawn Time", 1f), f(tw, n, H_ATTRACT_RADIUS, 20f), f(tw, n, H_ATTRACT_TIME, 1f), tw.f(n, "Gravity", 9.82f));
		}

		private static float f(Tweaks tw, String node, int hash, float def) {
			var v = tw.rawHash(node, hash);
			return v.isPresent() ? Float.intBitsToFloat(v.getAsInt()) : def;
		}
	}

	/** 떠 있는 오브 하나. */
	public static final class Orb {
		final Template t;
		final Vector3f pos = new Vector3f(), target = new Vector3f(), shown = new Vector3f();
		int state = 1;
		float timer, duration, life, age, phase, alpha = 1;
		EffectSystem.Effect fx;

		Orb(Template t) {
			this.t = t;
		}

		public Vector3f shown() {
			return shown;
		}

		public int state() {
			return state;
		}
	}

	/** 합산·소리·이펙트 (게임 쪽). */
	public interface Sink {
		void credit(int type, float points);

		void sound(String name, Vector3f at);

		/** GO 이펙트 시작 (없으면 null — 그때는 보이지 않고 동작만). */
		EffectSystem.Effect show(String go, EffectSystem.Anchor anchor);
	}

	private final List<Orb> live = new ArrayList<>();
	private final java.util.Map<Integer, Template> templates = new java.util.HashMap<>();
	private final Random rnd;

	public OrbPickups(Random rnd) {
		this.rnd = rnd;
	}

	public List<Orb> live() {
		return live;
	}

	/**
	 * OrbEmitter 하나 방출 (0x2258D8): 오브 N 개를 2π/N 간격 방향으로, 착지점 = 목표 + 방향 × Target Radius × 16 × rand.
	 * 상한을 넘는 오브는 만들지 않는다 (그만큼 사라짐).
	 */
	public void emit(Tweaks tw, String emitterNode, List<OrbDrops.Orb> orbs, Vector3f at, Sink sink) {
		if (orbs.isEmpty()) {
			return;
		}
		float targetR = emitterNode != null ? tw.f(emitterNode, "Target Radius", 5f) * 16f : 80f;
		float emitR = emitterNode != null ? tw.f(emitterNode, "Emitter Radius", 0f) * 16f : 0f;
		int n = orbs.size();
		for (int i = 0; i < n && live.size() < MAX; i++) {
			OrbDrops.Orb o = orbs.get(i);
			Template t = templates.computeIfAbsent(o.template(), id -> Template.of(tw, id));
			Orb b = new Orb(t);
			double a = i * Math.PI * 2 / n;
			float dx = (float) Math.sin(a), dz = (float) Math.cos(a);
			b.pos.set(at).add(dx * emitR, 16f, dz * emitR);
			float r = targetR * rnd.nextFloat();
			b.target.set(at.x + dx * r, at.y, at.z + dz * r);
			b.duration = Math.max(0.05f, t.spawnTime() * (rnd.nextFloat() + 0.5f));
			b.life = t.life() * (rnd.nextFloat() + 0.5f);
			b.phase = rnd.nextFloat() * (float) (Math.PI * 2);
			b.shown.set(b.pos);
			if (t.go() != null) {
				b.fx = sink.show(t.go(), out -> {
					out.translation(b.shown);
					return b.state != 8;
				});
			}
			live.add(b);
		}
	}

	/** 한 틱 (원작은 1/60 고정). heroAlive 가 false 면 전부 사라진다. */
	public void update(float dt, Vector3f hero, boolean heroAlive, Sink sink) {
		for (Iterator<Orb> it = live.iterator(); it.hasNext(); ) {
			Orb b = it.next();
			if (!heroAlive) {
				remove(b);
				it.remove();
				continue;
			}
			Template t = b.t;
			b.age += dt;
			if (b.age >= b.life) {
				remove(b);
				it.remove();
				continue;
			}
			b.alpha = Math.max(0, Math.min(1, b.life - b.age));
			if (b.state == 2) {
				float ar = t.attractRadius() * 16f;
				if (b.pos.distanceSquared(hero) < ar * ar) {
					b.state = 4;
					b.timer = 0;
					b.duration = Math.max(0.05f, t.attractTime());
					if (t.sound() != null) {
						sink.sound(t.sound(), hero);
					}
				}
			}
			if (b.state == 4) {
				b.target.set(hero).add(0, 16f, 0);
			}
			if (b.state == 1 || b.state == 4) {
				b.timer += dt;
				float rem = b.duration - b.timer;
				if (rem <= 0) {
					b.pos.set(b.target);
					if (b.state == 4) {
						sink.credit(t.type(), t.points());
						remove(b);
						it.remove();
						continue;
					}
					b.state = 2;
				} else {
					// 남은 시간 안에 닿는 포물선 (수직 = Δy/r + ½·g·r, g = Gravity × 16)
					float g = t.gravity() * 16f;
					float vx = (b.target.x - b.pos.x) / rem, vz = (b.target.z - b.pos.z) / rem;
					float vy = (b.target.y - b.pos.y) / rem + 0.5f * g * rem;
					b.pos.add(vx * dt, vy * dt - 0.5f * g * dt * dt, vz * dt);
				}
			}
			b.phase += dt * t.freq() * (float) (Math.PI * 2);
			b.shown.set(b.pos.x, b.pos.y + (t.yOffset() + (float) Math.sin(b.phase) * t.amp()) * 16f, b.pos.z);
			if (b.fx != null) {
				b.fx.alpha = b.alpha;
				b.fx.rateScale = b.alpha; // 0x226C40: 방출량 × 오브 alpha
			}
		}
	}

	private static void remove(Orb b) {
		b.state = 8;
		if (b.fx != null) {
			b.fx.stop();
		}
	}

	public void clear() {
		live.forEach(OrbPickups::remove);
		live.clear();
	}
}
