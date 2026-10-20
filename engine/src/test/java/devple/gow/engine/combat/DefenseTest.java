package devple.gow.engine.combat;

import devple.gow.tools.content.Content;
import devple.gow.tools.content.Region;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 10 문서 §4.4 방어/패리/회피: L1 홀드 → PARRY01(48) → BLOCK(33), 오른쪽 스틱 → EVADE*(514–517) 시작 0–0.2 무적. */
class DefenseTest {

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

	private record Rig(Combatant c, HeroController ctl, List<Integer> moves) {
	}

	private static Rig rig() {
		Animator an = new Animator(sk, set, tw, "/Animation/goHero/");
		HeroController ctl = new HeroController(an);
		CombatOwner owner = new CombatOwner() {
			public float hp() { return 100; }
			public boolean onGround() { return true; }
			public int bladeLevel() { return 3; }
			public boolean godMode() { return false; }
			public boolean artemisEquipped() { return false; }
			public int selectedMagic() { return 0; }
			public int classId() { return -1; }
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
		return new Rig(c, ctl, moves);
	}

	private static void run(Rig r, ScriptedInput in, float secs, Runnable each) {
		float dt = 1 / 60f;
		for (float t = 0; t < secs; t += dt) {
			in.advance(dt);
			r.c().update(in, dt);
			if (r.c().active()) {
				r.ctl().updateCombat(r.c(), in, 0, dt, null);
			} else {
				r.ctl().update(in, 0, dt);
			}
			if (each != null) {
				each.run();
			}
		}
	}

	@Test
	void blockHoldThenBlocksHit() {
		Rig r = rig();
		ScriptedInput in = new ScriptedInput(0, List.of(new ScriptedInput.Step(0.1f, 3f, PadButton.L1)));
		run(r, in, 1.5f, null);
		assertEquals(48, r.moves().getFirst(), "L1 → PARRY01: " + r.moves());
		assertTrue(r.c().guarding(), "방어 자세(guard=1) 유지: " + r.moves());
		// 적 공격이 막히면 이벤트 16 → BLOCKREACTION 계열
		CombatFile.Attack hit = new CombatFile.Attack(0, 0, new float[9], 0, 1, -1, 0, 5, 0, 0, 0, 0);
		Combatant enemy = rig().c();
		assertEquals(Hittable.Result.BLOCKED, r.c().takeHit(hit, enemy, 5));
		int before = r.moves().size();
		run(r, in, 0.2f, null);
		assertTrue(r.moves().size() > before, "막음 반응 기술: " + r.moves());
	}

	@Test
	void evadeHasInvulnerableStart() {
		Rig r = rig();
		ScriptedInput in = new ScriptedInput(0, List.of());
		in.stick(new ScriptedInput.Stick(0.1f, 0.1f, true, 0, 1)); // 오른쪽 스틱 앞
		boolean[] invul = {false};
		run(r, in, 0.25f, () -> invul[0] |= r.c().invulnerable());
		assertFalse(r.moves().isEmpty(), "회피 기술 없음");
		int m = r.moves().getFirst();
		assertTrue(m >= 514 && m <= 517, "EVADE* (514–517): " + r.moves());
		assertTrue(invul[0], "회피 시작 구간 무적(이벤트 9)");
	}
}
