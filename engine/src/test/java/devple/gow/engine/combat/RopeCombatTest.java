package devple.gow.engine.combat;

import devple.gow.engine.anim.Animator;
import devple.gow.engine.game.Tweaks;
import devple.gow.engine.input.PadButton;
import devple.gow.engine.input.ScriptedInput;
import devple.gow.tools.anim.AnimationSet;
import devple.gow.tools.anim.Skeleton;
import devple.gow.tools.content.Content;
import devple.gow.tools.content.Region;
import devple.gow.tools.wad.WadFile;
import org.joml.Vector3f;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 수평 줄 싸움 (43 §34, 영상 4865–4940): 영웅 분기 허용 상태가 줄 (0x80, Combat_StateMask 0x207DC0 의 +0x174 0x400 · 0x20000) 이면 □ = ROPEHOHA (51, 발차기
 * 피해 12 부위 7), △ = ROPEPOWER01 (68, 피해 20 부위 3, 자세 태그 79 → 줄 해골 ROPEDECAP), 피격 = ROPEHOHR1 (135). 지상 (0x2) 에서는 줄 기술이 나오지 않는다.
 */
class RopeCombatTest {

	private static Path rom() {
		Path rom = Path.of(System.getProperty("gow.rom", "../gow-rom"));
		Assumptions.assumeTrue(Files.exists(Content.file(rom, "R_HERO0.WAD")), "gow-rom 없음");
		return rom;
	}

	private record Run(List<Integer> moves, Combatant c) {
	}

	private static Run press(int mask, PadButton b, boolean hitReact) throws Exception {
		Path rom = rom();
		WadFile hero = WadFile.load(Content.file(rom, "R_HERO0.WAD"));
		WadFile perm = WadFile.load(Content.file(rom, Region.of(rom).perm()));
		Tweaks tw = new Tweaks();
		tw.load(perm);
		tw.load(hero);
		Skeleton sk = Skeleton.loadAll(hero).stream().filter(s -> s.name.equals("hero")).findFirst().orElseThrow();
		AnimationSet set = AnimationSet.load(hero, "ANM_hero");
		Animator an = new Animator(sk, set, tw, "/Animation/goHero/");
		Vector3f pos = new Vector3f();
		CombatOwner owner = new CombatOwner() {
			public float hp() { return 100; }
			public boolean onGround() { return mask == 0x2; }
			public int stateMask() { return mask; }
			public int bladeLevel() { return 2; }
			public boolean godMode() { return false; }
			public boolean artemisEquipped() { return false; }
			public int selectedMagic() { return 0; }
			public int classId() { return -1; }
			public Vector3f position() { return pos; }
			public float yaw() { return 0; }
		};
		Combatant c = new Combatant(new CombatData(perm, CombatData.heroFiles(), set), an, owner);
		List<Integer> moves = new ArrayList<>();
		c.onLog(s -> {
			if (s.startsWith("기술 ")) {
				moves.add(Integer.parseInt(s.split(" ")[1]));
			}
		});
		ScriptedInput in = new ScriptedInput(0, b != null ? List.of(new ScriptedInput.Step(0.1f, 0.05f, b)) : List.of());
		float dt = 1 / 60f;
		for (int i = 0; i < 36; i++) {
			in.advance(dt);
			if (hitReact && i == 6) {
				c.fire(CombatInput.EV_HIT, 0, c); // 줄 해골 ROPEHOHA 피격 (반응 0)
			}
			c.update(in, dt);
		}
		return new Run(moves, c);
	}

	@Test
	void ropeMovesNeedTheRopeState() throws Exception {
		assertEquals(List.of(51), press(0x80, PadButton.SQUARE, false).moves(), "줄 □ = ROPEHOHA");
		assertEquals(List.of(68), press(0x80, PadButton.TRIANGLE, false).moves(), "줄 △ = ROPEPOWER01");
		assertEquals(List.of(135), press(0x80, null, true).moves(), "줄 피격 = ROPEHOHR1");
		List<Integer> ground = press(0x2, PadButton.SQUARE, false).moves();
		assertFalse(ground.isEmpty());
		assertFalse(ground.contains(51), "지상 □ 는 줄 기술이 아님: " + ground);
		List<Integer> air = press(0x4, PadButton.SQUARE, false).moves();
		assertFalse(air.contains(51), "공중 □ 도 아님: " + air);
		// ROPEHOHA: 발 (부위 7) 공격 피해 12, ROPEPOWER01: 칼 (부위 3) 20 · 자세 태그 79
		Combatant c = press(0x80, PadButton.SQUARE, false).c();
		assertTrue(c.move() == null || c.move().id() == 51);
	}
}
