package devple.gow.engine.game;

import devple.gow.tools.content.Content;
import devple.gow.tools.content.Region;
import devple.gow.engine.anim.Animator;
import devple.gow.engine.anim.HeroController;
import devple.gow.engine.combat.CombatData;
import devple.gow.engine.combat.CombatOwner;
import devple.gow.engine.combat.Combatant;
import devple.gow.engine.input.PadButton;
import devple.gow.engine.input.ScriptedInput;
import devple.gow.engine.script.ScriptHost;
import devple.gow.engine.script.ScriptWorld;
import devple.gow.tools.anim.AnimationSet;
import devple.gow.tools.anim.Skeleton;
import devple.gow.tools.level.LevelData;
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

/** 41 문서: ARENA06 항아리 36 개 (Breakable / Brkb 000) — 영웅 공격으로 부수면 Marker(101) 파괴 → 스크립트 카운터 LevelData0. */
class BreakablesTest {

	@Test
	void heroBreaksPotsAndScriptCounts() throws Exception {
		Path rom = Path.of(System.getProperty("gow.rom", "../gow-rom"));
		Assumptions.assumeTrue(Files.exists(Content.file(rom, "ARENA06.WAD")), "gow-rom 없음");
		WadFile perm = WadFile.load(Content.file(rom, Region.of(rom).perm())), hero = WadFile.load(Content.file(rom, "R_HERO0.WAD"));
		LevelData lv = new LevelData(WadFile.load(Content.file(rom, "ARENA06.WAD")));
		Tweaks tw = new Tweaks();
		tw.load(perm);
		tw.load(hero);
		tw.load(lv.wad);
		Breakables b = new Breakables(lv, tw);
		assertEquals(36, b.items().size(), "항아리 36 개");
		assertTrue(b.items().stream().allMatch(i -> i.hp == 1f && i.radius > 4 && i.radius < 80),
				"Brkb 000 Hit Points 1: " + b.items().stream().map(i -> i.hp + "/" + i.radius).distinct().toList());

		ScriptHost host = new ScriptHost();
		ScriptWorld w = new ScriptWorld(lv, host, null);
		w.start();
		w.tick(0.1f, 0, 0, 0);

		Skeleton sk = Skeleton.loadAll(hero).stream().filter(s -> s.name.equals("hero")).findFirst().orElseThrow();
		AnimationSet set = AnimationSet.load(hero, "ANM_hero");
		Animator an = new Animator(sk, set, tw, "/Animation/goHero/");
		HeroController ctl = new HeroController(an);
		Breakables.Item pot = b.items().getFirst();
		// 항아리 바로 앞 (정면 −Z) 에 선다
		ctl.position.set(pot.center.x, pot.center.y - 16, pot.center.z + 40);
		ctl.yaw = 0;
		CombatOwner owner = new CombatOwner() {
			public float hp() { return 100; }
			public boolean onGround() { return true; }
			public int bladeLevel() { return 1; }
			public boolean godMode() { return false; }
			public boolean artemisEquipped() { return false; }
			public int selectedMagic() { return 0; }
			public int classId() { return 0; }
			public Vector3f position() { return ctl.position; }
			public float yaw() { return ctl.yaw; }
		};
		Combatant c = new Combatant(new CombatData(perm, CombatData.heroFiles(), set), an, owner);
		ScriptedInput in = new ScriptedInput(0, List.of(new ScriptedInput.Step(0.1f, 0.05f, PadButton.SQUARE)));
		List<String> broken = new ArrayList<>();
		for (float t = 0; t < 1.5f; t += 1 / 60f) {
			in.advance(1 / 60f);
			c.update(in, 1 / 60f);
			if (c.active()) {
				ctl.updateCombat(c, in, 0, 1 / 60f, null);
			}
			b.update(c, w, broken::add);
			w.tick(1 / 60f, ctl.position.x, ctl.position.y, ctl.position.z);
		}
		assertTrue(broken.contains(pot.owner), "□ 공격으로 항아리 파괴: " + broken);
		assertTrue(pot.broken());
		w.tick(0.1f, 0, 0, 0);
		Object n = host.levelData().get(0);
		assertTrue(n instanceof Number num && num.intValue() >= 1, "DestructionSensor(101) → LevelData0 증가: " + n);
	}
}
