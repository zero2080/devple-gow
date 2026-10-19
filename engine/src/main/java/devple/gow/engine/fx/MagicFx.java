package devple.gow.engine.fx;

import devple.gow.engine.asset.RenderModel;
import devple.gow.engine.combat.HadesSouls;
import devple.gow.engine.combat.Hittable;
import devple.gow.engine.combat.Magic;
import devple.gow.engine.combat.ZeusBolt;
import devple.gow.engine.render.Camera;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * 마법 시각 효과 (35 문서 §6) — 단순 파티클 근사.
 * 원작 GO 이펙트(goBolt/goStdBolt 꼬리, goBoltHit, goEyeBeam, goDedRed/goDeadHit/goDeadDie, *stonedbrk 파편)는
 * 마법 WAD(R_THBLT/R_MEDHD/R_RDEAD)의 FXC/PTC 로 정의되지만 GO 부착·애니 구동이 미구현이라 색·수명만 비슷한 파티클로 대신한다.
 */
public final class MagicFx implements AutoCloseable {

	private final ParticleSystem ps = new ParticleSystem(4096);
	private final Map<Object, ParticleSystem.Emitter> trails = new IdentityHashMap<>();
	private final Map<String, ParticleDef> defs = new HashMap<>();
	private final List<float[]> bursts = new ArrayList<>(); // [time left] 이미터와 짝
	private final List<ParticleSystem.Emitter> burstEmitters = new ArrayList<>();
	private ParticleSystem.Emitter beam;
	private final Map<Hittable, ParticleSystem.Emitter> stones = new IdentityHashMap<>();

	private static ParticleDef def(String name, float rate, float life, float box, float spreadV, float up, float size0, float size1,
								   float[] c0, float[] c1, float stretch, float gravity) {
		return new ParticleDef(name, rate, life, 0.3f, new float[]{box, box, box}, new float[]{0, up, 0},
				new float[]{spreadV, spreadV, spreadV}, new float[]{0, -gravity, 0}, 1.5f, size0, size1, c0, c1, 0, stretch, 0,
				null, 1, RenderModel.Blend.ADD);
	}

	public MagicFx() {
		defs.put("bolt", def("bolt", 240, 0.25f, 4, 30, 0, 9, 2, new float[]{1, 1, 0.7f, 1}, new float[]{0.4f, 0.6f, 1, 0}, 0, 0));
		defs.put("boltHit", def("boltHit", 900, 0.35f, 6, 260, 40, 12, 2, new float[]{1, 1, 0.8f, 1}, new float[]{0.3f, 0.5f, 1, 0}, 0.04f, 0));
		defs.put("soul", def("soul", 120, 0.5f, 5, 15, 10, 14, 4, new float[]{1, 0.35f, 0.1f, 0.9f}, new float[]{0.4f, 0, 0, 0}, 0, -20));
		defs.put("soulHit", def("soulHit", 500, 0.4f, 6, 150, 30, 12, 3, new float[]{1, 0.4f, 0.1f, 1}, new float[]{0.3f, 0, 0, 0}, 0, 0));
		// 광선: 원뿔 모드 (반각 ≈ 10.4°, 15m 를 1초에) — goEyeBeam 근사
		defs.put("beam", new ParticleDef("beam", 220, 1f, 0.1f, new float[]{2, 2, 2}, new float[3], new float[3], new float[3], 0,
				12, 30, new float[]{0.45f, 1, 0.35f, 0.3f}, new float[]{0.1f, 0.5f, 0.1f, 0}, 0.05f, 0, 0, null, 1,
				RenderModel.Blend.ADD, 0.115f, 15 * Magic.UNIT, 40, 0, 0, 0));
		defs.put("flash", def("flash", 1500, 0.3f, 20, 400, 0, 20, 40, new float[]{0.7f, 1, 0.6f, 1}, new float[]{0.2f, 0.5f, 0.2f, 0}, 0, 0));
		defs.put("stone", def("stone", 25, 0.8f, 18, 8, 15, 5, 3, new float[]{0.6f, 0.6f, 0.55f, 0.6f}, new float[]{0.3f, 0.3f, 0.3f, 0}, 0, 0));
		defs.put("shatter", new ParticleDef("shatter", 1200, 1.1f, 0.3f, new float[]{14, 25, 14}, new float[]{0, 120, 0},
				new float[]{180, 120, 180}, new float[]{0, -600, 0}, 0.5f, 7, 5, new float[]{0.65f, 0.63f, 0.58f, 1},
				new float[]{0.45f, 0.43f, 0.4f, 0.8f}, 0, 0, 0, null, 1, RenderModel.Blend.ALPHA));
	}

	/** 원작 GO 이펙트 이름 → 짧은 폭발 (0.1초 생성). */
	public void effect(String name, Vector3f at) {
		String k = switch (name) {
			case "goBoltHit" -> "boltHit";
			case "goDeadHit", "goDeadDie" -> "soulHit";
			case "goMedHeadFlash" -> "flash";
			case "shatter" -> "shatter";
			default -> null;
		};
		if (k == null) {
			return;
		}
		ParticleSystem.Emitter e = ps.add(defs.get(k), at.x, at.y + (k.equals("shatter") ? 30 : 0), at.z);
		burstEmitters.add(e);
		bursts.add(new float[]{0.1f});
	}

	/** 매 틱: 투사체·영혼 꼬리, 메두사 광선, 석상 먼지. */
	public void update(Magic m, Vector3f heroPos, float heroYaw, List<? extends Hittable> enemies, float dt, Vector3f camEye) {
		List<Object> live = new ArrayList<>();
		for (ZeusBolt.Bolt b : m.zeus.bolts()) {
			trail(b, "bolt", b.pos);
			live.add(b);
		}
		for (HadesSouls.Soul s : m.hades.souls()) {
			trail(s, "soul", s.pos);
			live.add(s);
		}
		trails.entrySet().removeIf(en -> {
			if (!live.contains(en.getKey())) {
				ps.emitters().remove(en.getValue());
				return true;
			}
			return false;
		});
		if (m.medusa.beamOn()) {
			if (beam == null) {
				beam = ps.add(defs.get("beam"), 0, 0, 0);
			}
			// 광선: 머리를 든 손(눈) 위치에서 정면 원뿔로 [근사: rWeapIH 관절 대신 고정 높이]
			float ay = m.medusa.aimYaw();
			float fx = -(float) Math.sin(ay), fz = -(float) Math.cos(ay);
			beam.pos.set(heroPos.x + fx * 15, heroPos.y + 50, heroPos.z + fz * 15);
			beam.dir.set(fx, 0, fz);
		} else if (beam != null) {
			ps.emitters().remove(beam);
			beam = null;
		}
		for (Hittable h : enemies) {
			boolean st = h.petrified();
			ParticleSystem.Emitter e = stones.get(h);
			if (st && e == null) {
				stones.put(h, e = ps.add(defs.get("stone"), 0, 0, 0));
			}
			if (e != null) {
				e.pos.set(h.position()).add(0, 35, 0);
				if (!st) {
					ps.emitters().remove(e);
					stones.remove(h);
				}
			}
		}
		for (int i = bursts.size() - 1; i >= 0; i--) {
			if ((bursts.get(i)[0] -= dt) <= 0) {
				burstEmitters.get(i).active = false;
				if (bursts.get(i)[0] < -2f) {
					ps.emitters().remove(burstEmitters.get(i));
					burstEmitters.remove(i);
					bursts.remove(i);
				}
			}
		}
		ps.update(dt, camEye);
	}

	private void trail(Object key, String def, Vector3f pos) {
		ParticleSystem.Emitter e = trails.computeIfAbsent(key, k -> ps.add(defs.get(def), pos.x, pos.y, pos.z));
		e.pos.set(pos);
	}

	public void render(Camera cam, float aspect) {
		ps.render(cam, aspect);
	}

	@Override
	public void close() {
		ps.close();
	}
}
