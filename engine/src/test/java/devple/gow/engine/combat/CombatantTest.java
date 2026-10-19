package devple.gow.engine.combat;

import devple.gow.engine.anim.Animator;
import devple.gow.engine.anim.HeroController;
import devple.gow.engine.game.Tweaks;
import devple.gow.engine.input.PadButton;
import devple.gow.engine.input.ScriptedInput;
import devple.gow.tools.anim.AnimationSet;
import devple.gow.tools.anim.Skeleton;
import devple.gow.tools.wad.WadFile;
import org.joml.Vector3f;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 10 문서 §4.4 영웅 콤보 표를 창 없이(헤드리스) 재현. */
class CombatantTest {

	private record Run(List<Integer> moves, List<Float> damages) {
	}

	private Run run(int blade, List<ScriptedInput.Step> steps, float seconds) throws Exception {
		Path rom = Path.of(System.getProperty("gow.rom", "../gow-rom"));
		Assumptions.assumeTrue(Files.exists(rom.resolve("R_HERO0.WAD")), "gow-rom 없음");
		WadFile heroWad = WadFile.load(rom.resolve("R_HERO0.WAD"));
		WadFile perm = WadFile.load(rom.resolve("R_PERMK.WAD"));
		Tweaks tw = new Tweaks();
		tw.load(perm);
		tw.load(heroWad);
		Skeleton sk = Skeleton.loadAll(heroWad).stream().filter(s -> s.name.equals("hero")).findFirst().orElseThrow();
		AnimationSet set = AnimationSet.load(heroWad, "ANM_hero");
		Animator animator = new Animator(sk, set, tw, "/Animation/goHero/");
		HeroController ctl = new HeroController(animator);
		TrainingDummy dummy = new TrainingDummy(new Vector3f(0, 0, -50), 1000, 10);
		List<Integer> moves = new ArrayList<>();
		List<Float> dmg = new ArrayList<>();
		CombatOwner owner = new CombatOwner() {
			public float hp() { return 100; }
			public boolean onGround() { return true; }
			public int bladeLevel() { return blade; }
			public boolean godMode() { return false; }
			public boolean artemisEquipped() { return false; }
			public int selectedMagic() { return 0; }
			public int classId() { return -1; }
			public Vector3f position() { return ctl.position; }
			public float yaw() { return ctl.yaw; }
		};
		Combatant c = new Combatant(new CombatData(perm, CombatData.heroFiles(), set), animator, owner, new DamageRules(tw));
		c.setTarget(dummy);
		c.onLog(s -> {
			if (s.startsWith("기술 ")) {
				moves.add(Integer.parseInt(s.split(" ")[1]));
			} else if (s.contains("적중")) {
				dmg.add(Float.parseFloat(s.replaceAll(".*피해 ([0-9.]+).*", "$1")));
			}
		});
		ScriptedInput in = new ScriptedInput(0, steps);
		float dt = 1 / 60f;
		for (float t = 0; t < seconds; t += dt) {
			in.advance(dt);
			c.update(in, dt);
			if (c.active()) {
				ctl.updateCombat(c, in, 0, dt, dummy.pos);
			} else {
				ctl.update(in, 0, dt);
			}
			dummy.update(dt);
			ctl.separate(dummy.pos, 10);
		}
		return new Run(moves, dmg);
	}

	private static List<ScriptedInput.Step> presses(PadButton b, float first, float gap, int n) {
		List<ScriptedInput.Step> l = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			l.add(new ScriptedInput.Step(first + i * gap, 0.05f, b));
		}
		return l;
	}

	@Test
	void squareChainCombo3() throws Exception {
		Run r = run(2, presses(PadButton.SQUARE, 0.2f, 0.3f, 4), 3);
		assertEquals(List.of(71, 72, 73, 74), r.moves().subList(0, 4), "□□□□ → COMBO3A..3D");
		// 기본 피해 2,2,2,3+3 × 블레이드 레벨 2 배율 2.0 (Normal, 코스튬 0)
		assertEquals(List.of(4f, 4f, 4f, 6f, 6f), r.damages());
	}

	@Test
	void triangleIsCombo6A() throws Exception {
		Run r = run(2, presses(PadButton.TRIANGLE, 0.2f, 1, 1), 1.5f);
		assertEquals(32, r.moves().getFirst());
		assertEquals(List.of(12f, 12f), r.damages());
	}

	@Test
	void dashNeedsBladeLevel1() throws Exception {
		assertTrue(run(0, presses(PadButton.R1, 0.2f, 1, 1), 1).moves().stream().noneMatch(m -> m == 6));
		assertEquals(6, run(1, presses(PadButton.R1, 0.2f, 1, 1), 1).moves().getFirst(), "R1 → DASH (레벨 ≥1)");
	}
}
