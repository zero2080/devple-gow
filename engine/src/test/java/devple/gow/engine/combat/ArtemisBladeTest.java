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

/**
 * 아르테미스의 검: L1+R1 → BERSERKENTER(555) → SCR_EnableBerserkBlade 로 장착 → □ = BERCOMBOH1(560), 피해 × 1.75(단계 0)
 * → L1+R1 → BERSERKEXIT(556) → SCR_DisableBerserkBlade 로 해제. (HeroRig 와 같은 규칙을 테스트 안에서 재현)
 */
class ArtemisBladeTest {

	@Test
	void equipAttackUnequip() throws Exception {
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
		boolean[] equipped = {false};
		CombatOwner owner = new CombatOwner() {
			public float hp() { return 100; }
			public boolean onGround() { return true; }
			public int bladeLevel() { return 1; }
			public boolean godMode() { return false; }
			public boolean artemisEquipped() { return equipped[0]; }
			public boolean artemisOwned() { return true; }
			public int selectedMagic() { return 0; }
			public int classId() { return 0; }
			public Vector3f position() { return ctl.position; }
			public float yaw() { return ctl.yaw; }
		};
		Combatant c = new Combatant(new CombatData(perm, CombatData.heroFiles(), set), an, owner);
		DamageRules rules = new DamageRules(tw);
		c.setDamageScale(() -> rules.playerScale(1, 0, 1, false, equipped[0] ? 0 : -1));
		TrainingDummy dummy = new TrainingDummy(new Vector3f(0, 0, -50), 1000, 1000);
		c.setTarget(dummy);
		List<Integer> moves = new ArrayList<>();
		List<Float> dmg = new ArrayList<>();
		c.onLog(s -> {
			if (s.startsWith("기술 ")) {
				moves.add(Integer.parseInt(s.split(" ")[1]));
			} else if (s.contains("적중")) {
				dmg.add(Float.parseFloat(s.replaceAll(".*피해 ([0-9.]+).*", "$1")));
			}
		});
		ScriptedInput in = new ScriptedInput(0, List.of(
				new ScriptedInput.Step(0.1f, 0.4f, PadButton.L1),
				new ScriptedInput.Step(0.3f, 0.05f, PadButton.R1),
				new ScriptedInput.Step(2.0f, 0.05f, PadButton.SQUARE),
				// 해제도 L1+R1: 아르테미스 방어(BERBLOCKPARRY/IDLE, L1 홀드) 중 R1 → BERSERKEXIT
				new ScriptedInput.Step(4.5f, 0.6f, PadButton.L1),
				new ScriptedInput.Step(4.8f, 0.05f, PadButton.R1)));
		float dt = 1 / 60f;
		boolean wasEquipped = false;
		for (float t = 0; t < 7f; t += dt) {
			in.advance(dt);
			c.update(in, dt);
			if (c.activeScript(0xe9283e7e) != null) {
				equipped[0] = true;
			} else if (c.activeScript(0x33681849) != null) {
				equipped[0] = false;
			}
			wasEquipped |= equipped[0];
			if (c.active()) {
				ctl.updateCombat(c, in, 0, dt, dummy.pos);
			} else {
				ctl.update(in, 0, dt);
			}
			dummy.update(dt);
			ctl.separate(dummy.pos, 10);
		}
		assertTrue(moves.contains(555), "BERSERKENTER: " + moves);
		assertTrue(wasEquipped, "장착 이벤트");
		assertTrue(moves.stream().anyMatch(m -> m >= 560 && m < 580), "BER 콤보: " + moves);
		assertFalse(dmg.isEmpty(), "BER 공격 적중");
		// BERCOMBOH1 기본 5 × 1.75 (Normal, 코스튬 0)
		assertTrue(dmg.stream().anyMatch(d -> Math.abs(d - 5 * 1.75f) < 0.06f), "피해 " + dmg);
		assertTrue(moves.contains(556), "BERSERKEXIT: " + moves);
		assertFalse(equipped[0], "해제");
	}
}
