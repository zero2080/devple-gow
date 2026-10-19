package devple.gow.engine.combat;

import devple.gow.engine.anim.Animator;
import devple.gow.engine.anim.HeroController;
import devple.gow.engine.game.GameRules;
import devple.gow.engine.game.PlayerState;
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

/** 포세이돈의 분노: 마법 버튼 홀드 → M513 → 루프 → MP 33 소모, 범위 피해 (10 문서 §10). */
class MagicTest {

	@Test
	void poseidonsRage() throws Exception {
		Path rom = Path.of(System.getProperty("gow.rom", "../gow-rom"));
		Assumptions.assumeTrue(Files.exists(rom.resolve("R_HERO0.WAD")), "gow-rom 없음");
		WadFile hero = WadFile.load(rom.resolve("R_HERO0.WAD"));
		WadFile perm = WadFile.load(rom.resolve("R_PERMK.WAD"));
		Tweaks tw = new Tweaks();
		tw.load(perm);
		tw.load(hero);
		PlayerState ps = new PlayerState(GameRules.load(perm));
		ps.addItem(3, 1); // 포세이돈 획득 → MP 레벨 1 (최대 100)
		assertEquals(100f, ps.mp);
		Magic magic = new Magic(tw, ps);
		assertEquals(33f, magic.cost(3));
		Skeleton sk = Skeleton.loadAll(hero).stream().filter(s -> s.name.equals("hero")).findFirst().orElseThrow();
		AnimationSet set = AnimationSet.load(hero, "ANM_hero");
		Animator an = new Animator(sk, set, tw, "/Animation/goHero/");
		HeroController ctl = new HeroController(an);
		CombatOwner owner = new CombatOwner() {
			public float hp() { return 100; }
			public boolean onGround() { return true; }
			public int bladeLevel() { return 1; }
			public boolean godMode() { return false; }
			public boolean artemisEquipped() { return false; }
			public int selectedMagic() { return 3; }
			public int classId() { return -1; }
			public boolean hasMagic(int id) { return magic.has(id); }
			public void useMagic(int id) { magic.use(id); }
			public Vector3f position() { return ctl.position; }
			public float yaw() { return ctl.yaw; }
		};
		Combatant c = new Combatant(new CombatData(perm, CombatData.heroFiles(), set), an, owner);
		TrainingDummy dummy = new TrainingDummy(new Vector3f(0, 0, -60), 500, 1000);
		c.setTarget(dummy);
		List<Integer> moves = new ArrayList<>();
		c.onLog(s -> {
			if (s.startsWith("기술 ")) {
				moves.add(Integer.parseInt(s.split(" ")[1]));
			}
		});
		ScriptedInput in = new ScriptedInput(0, List.of(new ScriptedInput.Step(0.1f, 2.5f, PadButton.R2)));
		float dt = 1 / 60f;
		for (float t = 0; t < 3.5f; t += dt) {
			in.advance(dt);
			c.update(in, dt);
			if (c.active()) {
				ctl.updateCombat(c, in, 0, dt, dummy.pos);
			} else {
				ctl.update(in, 0, dt);
			}
			magic.update(c, dt);
			dummy.update(dt);
		}
		assertEquals(513, moves.getFirst(), "AIRLIGHTNINGSTART: " + moves);
		assertEquals(67f, ps.mp, 0.01f);
		assertTrue(dummy.hp < 500, "범위 피해 " + dummy.hp + " moves " + moves);
	}
}
