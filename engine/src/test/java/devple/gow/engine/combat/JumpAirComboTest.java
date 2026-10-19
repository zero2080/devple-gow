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

import static org.junit.jupiter.api.Assertions.*;

/** × 점프 → 공중에서 □ = AIRH1(13) (10 문서 §4.4 공중 행), 착지 후 지상 복귀. */
class JumpAirComboTest {

	@Test
	void jumpThenAirSquare() throws Exception {
		Path rom = Path.of(System.getProperty("gow.rom", "../gow-rom"));
		Assumptions.assumeTrue(Files.exists(rom.resolve("R_HERO0.WAD")), "gow-rom 없음");
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
			public boolean onGround() { return ctl.grounded; }
			public int bladeLevel() { return 1; }
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
		ScriptedInput in = new ScriptedInput(0, List.of(
				new ScriptedInput.Step(0.1f, 0.05f, PadButton.CROSS),
				new ScriptedInput.Step(0.35f, 0.05f, PadButton.SQUARE)));
		float dt = 1 / 60f, maxY = 0;
		boolean landed = false;
		for (float t = 0; t < 3f; t += dt) {
			in.advance(dt);
			c.update(in, dt);
			if (!c.active() && in.pressed(PadButton.CROSS)) {
				ctl.jump(false);
			}
			if (c.active()) {
				ctl.updateCombat(c, in, 0, dt, null);
			} else {
				ctl.update(in, 0, dt);
			}
			// 평지 물리 (게임 씬과 같은 중력)
			if (!ctl.grounded || ctl.vy > 0) {
				ctl.vy -= 9.82f * 16 * dt;
				ctl.position.y += ctl.vy * dt;
				if (ctl.position.y <= 0) {
					ctl.position.y = 0;
					ctl.vy = 0;
					ctl.grounded = true;
					landed = t > 0.2f;
				}
			}
			maxY = Math.max(maxY, ctl.position.y);
		}
		assertTrue(maxY > 30 && maxY < 60, "점프 정점 " + maxY);
		assertTrue(moves.contains(13), "공중 □ = AIRH1(13): " + moves);
		assertTrue(landed);
	}
}
