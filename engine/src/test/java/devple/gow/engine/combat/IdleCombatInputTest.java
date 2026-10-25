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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ANY_INPUT (분기 입력 12, 폴링 0x207FE0) = 새로 눌린 패드 비트 0–7 (L2 R2 L1 R1 △ ○ × □) 또는 스틱 &gt; 0.5 [확실: 코드]. 싸운 뒤 전투 대기
 * IDLECOMBAT (M80 / M85, ANY_INPUT → 기술 끝) 에서 R2 (사용) 를 누르면 기술이 끝나 같은 틱에 내비게이션이 R2 를 받는다 — 예전 엔진은 L3 를 넣고 R2 를 빼
 * PAND01A 레버를 싸운 뒤에 잡지 못했다 (43 §34).
 */
class IdleCombatInputTest {

	private static Path rom() {
		Path rom = Path.of(System.getProperty("gow.rom", "../gow-rom"));
		Assumptions.assumeTrue(Files.exists(Content.file(rom, "R_HERO0.WAD")), "gow-rom 없음");
		return rom;
	}

	/** □ 한 번 → 연속기 끝 → 전투 대기, 3 초에 버튼 b. @return {3 초 직전 기술, 3.1 초 기술} (없으면 −1) */
	private static int[] idleThen(PadButton b) throws Exception {
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
			public boolean onGround() { return true; }
			public int stateMask() { return 0x2; }
			public int bladeLevel() { return 2; }
			public boolean godMode() { return false; }
			public boolean artemisEquipped() { return false; }
			public int selectedMagic() { return 0; }
			public int classId() { return -1; }
			public Vector3f position() { return pos; }
			public float yaw() { return 0; }
		};
		Combatant c = new Combatant(new CombatData(perm, CombatData.heroFiles(), set), an, owner);
		ScriptedInput in = new ScriptedInput(0, List.of(new ScriptedInput.Step(0.1f, 0.05f, PadButton.SQUARE), new ScriptedInput.Step(3f, 0.05f, b)));
		float dt = 1 / 60f;
		int before = -1, after = -1;
		for (int i = 1; i <= 186; i++) {
			in.advance(dt);
			c.update(in, dt);
			an.update(dt); // 기술 시간 = 애니 시간 (HeroController.updateCombat 과 같이)
			if (i == 179) {
				before = c.active() ? c.move().id() : -1;
			}
		}
		after = c.active() ? c.move().id() : -1;
		return new int[]{before, after};
	}

	@Test
	void useButtonEndsIdleCombat() throws Exception {
		int[] r2 = idleThen(PadButton.R2);
		assertTrue(r2[0] == 80 || r2[0] == 85, "□ 연속기 뒤 전투 대기 (IDLECOMBAT 80/85): " + r2[0]);
		assertTrue(r2[1] == -1, "R2 (패드 비트 1) 는 ANY_INPUT → 기술 끝 (내비게이션이 잡기를 받음): " + r2[1]);
		int[] l3 = idleThen(PadButton.L3);
		assertNotNull(l3);
		assertFalse(l3[1] == -1, "L3 (패드 비트 9) 는 ANY_INPUT 이 아님 → 전투 대기 그대로: " + l3[1]);
	}
}
