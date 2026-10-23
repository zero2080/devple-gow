package devple.gow.engine.combat;

import devple.gow.engine.anim.Animator;
import devple.gow.engine.anim.HeroController;
import devple.gow.engine.game.Tweaks;
import devple.gow.engine.input.PadButton;
import devple.gow.engine.input.ScriptedInput;
import devple.gow.tools.anim.AnimationSet;
import devple.gow.tools.anim.Skeleton;
import devple.gow.tools.combat.CombatFile;
import devple.gow.tools.content.Content;
import devple.gow.tools.content.Region;
import devple.gow.tools.wad.WadFile;
import org.joml.Vector3f;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 방어 체계 (10 문서 §7): OnContact 0x203AF0 의 방어 분기 (각도 무관·방어 불가·밀어내기 0x2039E0), 패리 PARRY01 → PARRY02, 가드 브레이크,
 * 이벤트 조건 (0x206030 — 막힘 5 / 적중 1·2), 적 NO_BLOCK (이벤트 12) 창과 피격 반응 중 방어 (0x1CBFF8).
 */
class DefenseSystemTest {

	private static final float DT = 1 / 60f;
	private static WadFile hero, perm;
	private static Tweaks tw;
	private static AnimationSet set;
	private static Skeleton sk;

	@BeforeAll
	static void load() throws Exception {
		Path rom = Path.of(System.getProperty("gow.rom", "../gow-rom"));
		Assumptions.assumeTrue(Files.exists(Content.file(rom, "R_HERO0.WAD")), "gow-rom 없음");
		hero = WadFile.load(Content.file(rom, "R_HERO0.WAD"));
		perm = WadFile.load(Content.file(rom, Region.of(rom).perm()));
		tw = new Tweaks();
		tw.load(perm);
		tw.load(hero);
		sk = Skeleton.loadAll(hero).stream().filter(s -> s.name.equals("hero")).findFirst().orElseThrow();
		set = AnimationSet.load(hero, "ANM_hero");
	}

	private record Rig(Combatant c, HeroController ctl, List<Integer> moves, Animator an) {
	}

	private static Rig rig(Vector3f at, float yaw) {
		Animator an = new Animator(sk, set, tw, "/Animation/goHero/");
		HeroController ctl = new HeroController(an);
		ctl.position.set(at);
		ctl.yaw = yaw;
		CombatOwner owner = new CombatOwner() {
			public float hp() { return 100; }
			public boolean onGround() { return true; }
			public int bladeLevel() { return 3; }
			public boolean godMode() { return false; }
			public boolean artemisEquipped() { return false; }
			public int selectedMagic() { return 0; }
			public int classId() { return 0; }
			public Vector3f position() { return ctl.position; }
			public float yaw() { return ctl.yaw; }
		};
		Combatant c = new Combatant(new CombatData(perm, CombatData.heroFiles(), set), an, owner);
		List<Integer> moves = new ArrayList<>();
		c.onLog(s -> {
			if (s.startsWith("기술 ")) {
				moves.add(Integer.parseInt(s.split(" ")[1]));
			}
		});
		return new Rig(c, ctl, moves, an);
	}

	private static void run(Rig r, ScriptedInput in, float secs) {
		for (float t = 0; t < secs; t += DT) {
			in.advance(DT);
			r.c().update(in, DT);
			if (r.c().active()) {
				r.ctl().updateCombat(r.c(), in, 0, DT, null);
			} else {
				r.ctl().update(in, 0, DT);
			}
		}
	}

	/** 공격 판정 하나: 방어 밀어내기 (+0x0C) = (0, 0, 1000), 피해 5, 막혔을 때 반응 blockReact. */
	private static CombatFile.Attack attack(int blockReact) {
		float[] imp = {0, 0, 500, 0, 0, 500, 0, 0, 1000};
		return new CombatFile.Attack(0, 0, imp, 0, 1, -1, 0, 5, 0, blockReact, 0, 0);
	}

	private static ScriptedInput holdL1() {
		return new ScriptedInput(0, List.of(new ScriptedInput.Step(0.05f, 5f, PadButton.L1)));
	}

	@Test
	void parryWindowThenBlockReaction() {
		// L1 누른 직후 PARRY01 (48) 중에 막으면 PARRY02 (59, 반격 — 공격 판정 part 9, 자세 태그 7, 전역 시간 0.3 초 × 0.05)
		Rig r = rig(new Vector3f(), 0);
		ScriptedInput in = holdL1();
		run(r, in, 0.1f);
		assertEquals(48, r.c().move().id(), "L1 → PARRY01: " + r.moves());
		Combatant foe = rig(new Vector3f(0, 0, -40), (float) Math.PI).c();
		assertEquals(Hittable.Result.BLOCKED, r.c().takeHit(attack(0), foe, 5));
		run(r, in, 0.05f);
		assertEquals(59, r.c().move().id(), "PARRY01 중 막음 → PARRY02: " + r.moves());

		// PARRY01 이 끝날 때까지 누르고 있으면 BLOCK (33), 그때 막으면 BLOCKREACTION (34)
		Rig b = rig(new Vector3f(), 0);
		ScriptedInput in2 = holdL1();
		for (int i = 0; i < 240 && (!b.c().active() || b.c().move().id() != 33); i++) {
			run(b, in2, DT);
		}
		assertEquals(33, b.c().move().id(), "PARRY01 → BLOCK: " + b.moves());
		assertEquals(Hittable.Result.BLOCKED, b.c().takeHit(attack(0), foe, 5));
		run(b, in2, 0.05f);
		assertEquals(34, b.c().move().id(), "BLOCK 중 막음 → BLOCKREACTION: " + b.moves());
	}

	@Test
	void guardBreakAndUnblockable() {
		Rig b = rig(new Vector3f(), 0);
		ScriptedInput in = holdL1();
		for (int i = 0; i < 240 && (!b.c().active() || b.c().move().id() != 33); i++) {
			run(b, in, DT);
		}
		Combatant foe = rig(new Vector3f(0, 0, -40), (float) Math.PI).c();
		// 막혔을 때 반응 1 (해골 GRABHERO00·GRABMISS, 벽 도약) → BLOCKBREAK (35)
		assertEquals(Hittable.Result.BLOCKED, b.c().takeHit(attack(1), foe, 5));
		run(b, in, 0.05f);
		assertEquals(35, b.c().move().id(), "가드 브레이크: " + b.moves());

		// 방어 불가 (공격자 기술 flags & 2 — 영웅 COMBO5A 22 = 0x106, 사이클롭스 전 공격): 방어 자세여도 피해·반응
		Rig g = rig(new Vector3f(), 0);
		ScriptedInput in2 = holdL1();
		for (int i = 0; i < 240 && (!g.c().active() || g.c().move().id() != 33); i++) {
			run(g, in2, DT);
		}
		Combatant breaker = rig(new Vector3f(0, 0, -40), (float) Math.PI).c();
		assertTrue(breaker.forceMove(22));
		assertEquals(2, breaker.move().flags() & 2, "COMBO5A 방어 불가");
		assertEquals(Hittable.Result.HIT, g.c().takeHit(attack(0), breaker, 5));
	}

	@Test
	void blockIsOmnidirectionalAndSlidesBack() {
		// 0x203AF0 에는 각도 검사가 없다: 뒤에서 맞아도 막는다. 밀어내기 = 공격 +0x0C (0,0,1000) → 공격자 반대쪽 1000/100×16 = 160 단위/초
		Rig b = rig(new Vector3f(), 0); // 정면 −Z
		ScriptedInput in = holdL1();
		for (int i = 0; i < 240 && (!b.c().active() || b.c().move().id() != 33); i++) {
			run(b, in, DT);
		}
		Combatant behind = rig(new Vector3f(0, 0, 40), 0).c(); // 등 뒤 (+Z) 에서 앞으로
		assertEquals(Hittable.Result.BLOCKED, b.c().takeHit(attack(0), behind, 5));
		Vector3f v = new Vector3f(b.c().impulse());
		assertEquals(160f, v.length(), 0.5f, "방어 밀어내기 속도: " + v);
		assertTrue(v.z < -159f, "공격자 반대쪽 (−Z) 으로: " + v);
		// 지상 마찰 (Sold_000 +0x88 = 3 → 3·9.82·16 = 471 단위/초²): 160²/(2·471) ≈ 27 단위, 0.34 초
		float dist = 0, time = 0;
		for (int i = 0; i < 120; i++) {
			Vector3f d = b.c().stepImpulse(DT, true);
			if (d.lengthSquared() > 0) {
				time += DT;
			}
			dist += d.length();
		}
		assertEquals(27.2f, dist, 2f, "미끄러진 거리");
		assertEquals(0.34f, time, 0.05f, "미끄러진 시간");
		assertEquals(0f, b.c().impulse().length(), 1e-3f);
	}

	@Test
	void blockedOnlyEventsNeedABlock() {
		// DASHMULTISTAB (57): HIT_PAUSE 0.06 (조건 0) + 막힘 조건 (5) HIT_PAUSE 0.04 · CAMERA_SHAKE 0 · FORCE_FEEDBACK 0.
		// 0x206030: 조건 5 는 마지막 막힘 프레임 (+0xA8) 이 직전 프레임일 때만 — 예전 엔진은 휘두를 때마다 막힘 흔들림을 냈다
		Rig r = rig(new Vector3f(), 0);
		List<Integer> shakes = new ArrayList<>();
		r.c().onCameraShake(shakes::add);
		assertTrue(r.c().forceMove(57));
		ScriptedInput idle = new ScriptedInput(0, List.of());
		for (int i = 0; i < 120 && r.c().active() && r.c().move().id() == 57; i++) {
			r.c().update(idle, DT);
			r.an().update(DT);
		}
		assertTrue(shakes.isEmpty(), "맞힌 것도 막힌 것도 없으면 조건 1·5 흔들림 없음: " + shakes);

		// 막는 표적: 첫 막힘 다음 프레임에 흔들림 0 (조건 5)
		Rig s = rig(new Vector3f(), 0);
		List<Integer> shakes2 = new ArrayList<>();
		s.c().onCameraShake(shakes2::add);
		TrainingDummy wall = new TrainingDummy(new Vector3f(0, 0, -30), 1000, 0);
		wall.block = true;
		s.c().setTarget(wall);
		assertTrue(s.c().forceMove(57));
		for (int i = 0; i < 120 && s.c().active() && s.c().move().id() == 57; i++) {
			s.c().update(idle, DT);
			s.an().update(DT);
		}
		assertTrue(shakes2.contains(0), "막힘 흔들림 (템플릿 0): " + shakes2);
		assertFalse(shakes2.contains(1), "적중 흔들림 (템플릿 1) 은 적중 때만: " + shakes2);
	}

	@Test
	void godMeterOnlyOnHit() {
		// COMBO3 계열의 GOD_METER_ADD 는 조건 1 (적중 프레임) — 예전 엔진은 접촉 전에 이벤트를 처리해 hitThisFrame 이 늘 거짓이었다
		Rig r = rig(new Vector3f(), 0);
		TrainingDummy d = new TrainingDummy(new Vector3f(0, 0, -30), 1000, 0);
		r.c().setTarget(d);
		assertTrue(r.c().forceMove(57));
		ScriptedInput idle = new ScriptedInput(0, List.of());
		for (int i = 0; i < 120 && r.c().active() && r.c().move().id() == 57; i++) {
			r.c().update(idle, DT);
			r.an().update(DT);
		}
		assertTrue(d.hp < 1000, "맞음");
		assertTrue(r.c().godMeter > 0.1f, "적중 → 신 게이지 (0.05 × 적중 수): " + r.c().godMeter);
	}

	/** 영웅 대역 (적의 대상): 위치·전투원만. */
	private static Hittable heroStandIn(Rig h) {
		return new Hittable() {
			public Vector3f position() { return h.ctl().position; }
			public float hp() { return 100; }
			public int classId() { return 0; }
			public boolean invulnerable() { return false; }
			public Combatant combatant() { return h.c(); }
			public Result receive(CombatFile.Attack a, Combatant at, float d, Vector3f kn) { return Result.IGNORED; }
		};
	}

	@Test
	void enemyBlocksDuringLightHitReactionButNotInNoBlockWindow() throws Exception {
		Path rom = Path.of(System.getProperty("gow.rom", "../gow-rom"));
		EnemyFactory.Kind min = EnemyFactory.load(Content.file(rom, "R_MIN.WAD"), perm, -1);
		Rig h = rig(new Vector3f(), 0);
		assertTrue(h.c().forceMove(7)); // BLOCKLAUNCHER — BlockMod 050 "Move 1": 9 회 이상이면 늘 방어
		CombatFile.Attack a = attack(0);

		// 가벼운 피격 HITFRONT (116, guard 2, NO_BLOCK 없음): 원작 0x1CBFF8 은 기술 중인지 보지 않는다 → 맞는 도중에도 막는다
		EnemyFighter f = min.spawn();
		f.pos.set(0, 0, -60);
		f.setFoe(heroStandIn(h));
		for (int i = 0; i < 9; i++) {
			f.brain().slot().history.onMoveStart(7);
		}
		assertTrue(f.combat().forceMove(116));
		assertFalse(f.combat().noBlock(), "HITFRONT 에는 NO_BLOCK 이 없다");
		assertEquals(Hittable.Result.BLOCKED, f.receive(a, h.c(), 5, new Vector3f()));
		assertTrue(f.combat().guarding(), "방어 기술: " + f.combat().move().id());
		// 방어 밀어내기: R_MIN Sold_050 질량 100, CombatFileSet 050 배율 1 → 160 단위/초, 영웅 반대쪽 (−Z)
		Vector3f v = f.combat().impulse();
		assertEquals(160f, v.length(), 1f, "미노타우로스 방어 밀어내기: " + v);
		assertTrue(v.z < 0, "영웅 반대쪽: " + v);
		float hp = f.hp();

		// 무거운 피격 HITSPIN (102, NO_BLOCK 전 구간): 막지 못한다
		EnemyFighter g = min.spawn();
		g.pos.set(0, 0, -60);
		g.setFoe(heroStandIn(h));
		for (int i = 0; i < 9; i++) {
			g.brain().slot().history.onMoveStart(7);
		}
		assertTrue(g.combat().forceMove(102));
		assertTrue(g.combat().noBlock(), "HITSPIN NO_BLOCK");
		assertEquals(Hittable.Result.HIT, g.receive(a, h.c(), 5, new Vector3f()));
		assertEquals(hp, f.hp(), 1e-3f, "막은 쪽은 피해 없음");
	}

	@Test
	void heroImpulseDefaultsMatchData() throws Exception {
		// Combatant 기본값 = 크라토스 Sold_000 (+0x84 질량 100, +0x88 마찰 3, +0x1C 비트 3 없음), CombatFileSet 000 배율 1/1/1
		assertEquals(AiData.Physics.HERO, AiData.load(perm).physics(0));
		String n = "/TweakTemplates/CombatFileSet/000/";
		for (int h : new int[]{0x35F40B1B, 0x7A4A8930, 0xDF4375E3}) {
			assertEquals(1f, Float.intBitsToFloat(tw.rawHash(n, h).orElseThrow()), 1e-6f);
		}
		// 사이클롭스 Sold_040 질량 1000, 해골 궁수 031 밀리지 않음
		Path rom = Path.of(System.getProperty("gow.rom", "../gow-rom"));
		assertEquals(1000f, AiData.load(WadFile.load(Content.file(rom, "R_CYW.WAD"))).physics(40).mass());
		assertTrue(AiData.load(WadFile.load(Content.file(rom, "R_SKA.WAD"))).physics(31).immovable());
	}
}
