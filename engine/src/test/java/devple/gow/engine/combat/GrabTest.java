package devple.gow.engine.combat;

import devple.gow.engine.anim.Animator;
import devple.gow.engine.anim.HeroController;
import devple.gow.engine.game.Tweaks;
import devple.gow.engine.input.PadButton;
import devple.gow.engine.input.ScriptedInput;
import devple.gow.tools.anim.AnimationSet;
import devple.gow.tools.anim.Skeleton;
import devple.gow.tools.combat.CombatFile;
import devple.gow.tools.wad.WadFile;
import org.joml.Vector3f;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 미노타우로스 잡기 처형: HP 를 잡기 원(25) 아래로 → ○ GRAB → 양쪽 MAIMIN01 → MAIMIN02 연타 QTE
 * → 성공 402(MAIMIN03) / 실패 403(MAIMIN04).
 */
class GrabTest {

	private record Result(List<Integer> heroMoves, List<Integer> enemyMoves, RepeatPressQte.State qte) {
	}

	private Result run(boolean mash) throws Exception {
		Path rom = Path.of(System.getProperty("gow.rom", "../gow-rom"));
		Assumptions.assumeTrue(Files.exists(rom.resolve("R_MIN.WAD")), "gow-rom 없음");
		WadFile hero = WadFile.load(rom.resolve("R_HERO0.WAD"));
		WadFile perm = WadFile.load(rom.resolve("R_PERMK.WAD"));
		Tweaks tw = new Tweaks();
		tw.load(perm);
		tw.load(hero);
		Skeleton sk = Skeleton.loadAll(hero).stream().filter(s -> s.name.equals("hero")).findFirst().orElseThrow();
		AnimationSet set = AnimationSet.load(hero, "ANM_hero");
		Animator an = new Animator(sk, set, tw, "/Animation/goHero/");
		HeroController ctl = new HeroController(an);
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
		CombatData cd = new CombatData(perm, CombatData.heroFiles(), set);
		Combatant c = new Combatant(cd, an, owner);
		EnemyFactory.Kind k = EnemyFactory.load(rom.resolve("R_MIN.WAD"), perm, -1);
		set.attachAll(k.assets().wad());
		cd.reindexAnimations();
		EnemyFighter en = k.spawn();
		en.pos.set(0, 0, -50);
		en.hp = 20; // 잡기 원(25) 아래
		en.setFoe(new Hittable() {
			public Vector3f position() { return ctl.position; }
			public float hp() { return 100; }
			public int classId() { return 0; }
			public int stanceTag() { return c.stanceTag(); }
			public boolean invulnerable() { return true; }
			public Result receive(CombatFile.Attack a, Combatant at, float d, Vector3f kn) { return Result.IGNORED; }
		});
		c.setTarget(en);
		List<Integer> hm = new ArrayList<>(), em = new ArrayList<>();
		c.onLog(s -> {
			if (s.startsWith("기술 ")) {
				hm.add(Integer.parseInt(s.split(" ")[1]));
			}
		});
		en.combat().onLog(s -> {
			if (s.startsWith("기술 ")) {
				em.add(Integer.parseInt(s.split(" ")[1]));
			}
		});
		List<ScriptedInput.Step> st = new ArrayList<>();
		st.add(new ScriptedInput.Step(0.2f, 0.05f, PadButton.CIRCLE));
		if (mash) {
			for (int i = 0; i < 60; i++) {
				st.add(new ScriptedInput.Step(4.5f + i * 0.12f, 0.05f, PadButton.CIRCLE));
			}
		}
		ScriptedInput in = new ScriptedInput(0, st);
		GrabSync sync = new GrabSync(ctl, c);
		RepeatPressQte.State qs = null;
		float dt = 1 / 60f;
		for (float t = 0; t < 14f; t += dt) {
			in.advance(dt);
			boolean locked = sync.active();
			if (!locked) {
				c.update(in, dt);
				if (c.active()) {
					ctl.updateCombat(c, in, 0, dt, en.pos);
				} else {
					ctl.update(in, 0, dt);
				}
			}
			en.update(dt);
			sync.update(in, en, k.tweaks(), dt);
			if (sync.qte() != null) {
				qs = sync.qte().state();
			}
			if (!sync.active()) {
				ctl.separate(en.pos, 22);
			}
		}
		return new Result(hm, em, qs);
	}

	@Test
	void mashingWins() throws Exception {
		Result r = run(true);
		assertTrue(r.heroMoves().contains(400), "영웅 MAIMIN01: " + r.heroMoves());
		assertTrue(r.enemyMoves().containsAll(List.of(400, 401)), "적 MAIMIN01→02: " + r.enemyMoves());
		assertEquals(RepeatPressQte.State.PASSED, r.qte());
		assertTrue(r.enemyMoves().contains(402), "성공 → MAIMIN03(402): " + r.enemyMoves());
	}

	@Test
	void notMashingFails() throws Exception {
		Result r = run(false);
		assertEquals(RepeatPressQte.State.FAILED, r.qte());
		assertTrue(r.enemyMoves().contains(403), "실패 → MAIMIN04(403): " + r.enemyMoves());
	}
}
