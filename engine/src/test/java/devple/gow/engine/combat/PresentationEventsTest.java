package devple.gow.engine.combat;

import devple.gow.engine.anim.Animator;
import devple.gow.engine.game.Tweaks;
import devple.gow.engine.input.ScriptedInput;
import devple.gow.engine.render.Camera;
import devple.gow.engine.render.CameraShake;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * 강공격 연출 (0x206030): 이벤트 5 CAMERA_SHAKE (템플릿 → 0x1F09C8 / 0x1F0A30), 7 GLOBAL_TIME (0x337C1C 지속 · 0x337C20 배율).
 * △△△ 마무리 combo3F (76) 는 진행률 0.35 에서 GLOBAL_TIME a 0.3 초 · b 0.1.
 */
class PresentationEventsTest {

	@Test
	void heavyFinisherSlowsGlobalTime() throws Exception {
		Path rom = Path.of(System.getProperty("gow.rom", "../gow-rom"));
		Assumptions.assumeTrue(Files.exists(Content.file(rom, Region.of(rom).perm())), "gow-rom 없음");
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
			public int bladeLevel() { return 3; }
			public boolean godMode() { return false; }
			public boolean artemisEquipped() { return false; }
			public int selectedMagic() { return 0; }
			public int classId() { return 0; }
			public Vector3f position() { return pos; }
			public float yaw() { return 0; }
		};
		Combatant c = new Combatant(new CombatData(perm, CombatData.heroFiles(), set), an, owner);
		List<float[]> warps = new ArrayList<>();
		c.onGlobalTime((a, b) -> warps.add(new float[]{a, b, c.progress()}));
		c.forceMove(76);
		var idle = new ScriptedInput(0, List.of());
		for (int i = 0; i < 120 && c.active() && c.move().id() == 76; i++) {
			c.update(idle, 1 / 60f);
			an.update(1 / 60f); // 애니 진행은 영웅 컨트롤러가 (HeroController.updateCombat)
		}
		assertFalse(warps.isEmpty(), "combo3F GLOBAL_TIME");
		assertEquals(0.3f, warps.getFirst()[0], 1e-3f);
		assertEquals(0.1f, warps.getFirst()[1], 1e-3f);

		// 흔들림 002 "med": Duration 0.6, 회전 1.0°, 13 Hz — 끝나면 0, 진행 중 새 흔들림은 무시
		CameraShake sh = new CameraShake();
		Camera cam = new Camera();
		sh.start(tw, 2);
		assertTrue(sh.active());
		float max = 0;
		for (int i = 0; i < 30; i++) {
			sh.update(1 / 60f, cam);
			max = Math.max(max, Math.abs(cam.shakePitchDeg));
		}
		assertTrue(max > 0.5f && max <= 1.0f, "회전 진폭 " + max);
		sh.start(tw, 4);
		for (int i = 0; i < 10; i++) {
			sh.update(1 / 60f, cam);
		}
		assertFalse(sh.active(), "0.6 초 뒤 끝 (진행 중 004 는 무시됨)");
		assertEquals(0f, cam.shakePitchDeg);
	}
}
